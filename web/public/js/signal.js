import { seal, open } from "./crypto.js";

const PING_MS = 15_000;
const SILENT_MS = 40_000;
const WAKE_TIMEOUT_MS = 3000;
const ICE_REFRESH_MS = 2 * 3600_000;
const MAX_BACKOFF_MS = 15_000;

const FATAL = {
  4001: "另一支 iPhone 接手了連線",
  4003: "配對密鑰不對，請在 Android 上重新顯示 QR 再配對",
  4004: "Android 還沒上線過，請先在 Android 上按「開始待命」",
  4029: "嘗試太多次，請 10 分鐘後再試",
};

export class Signal {
  constructor({ server, keys, role, onState, onMessage }) {
    Object.assign(this, { server, keys, role, onState, onMessage });
    this.seen = new Map();
    this.retry = 0;
    this.stopped = true;
    this.ice = null;
    this.lastRx = 0;
    this.inbound = Promise.resolve();
  }

  start() {
    this.stopped = false;
    this.connect();
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.retryTimer);
    this.clearTimers();
    const ws = this.ws;
    this.ws = null;
    ws?.close(1000, "bye");
  }

  get open() {
    return this.ws?.readyState === WebSocket.OPEN && this.ready;
  }

  clearTimers() {
    clearInterval(this.pingTimer);
    clearInterval(this.iceTimer);
    clearTimeout(this.wakeTimer);
  }

  connect() {
    if (this.stopped) return;
    this.ready = false;
    this.onState({ server: "connecting" });
    const url = `${this.server.replace(/^http/, "ws")}/api/ws?room=${this.keys.room}`;
    const ws = (this.ws = new WebSocket(url));
    ws.onopen = () => ws.send(JSON.stringify({ t: "hello", role: this.role, auth: this.keys.auth }));
    ws.onmessage = (e) => {
      if (this.ws !== ws) return;
      this.lastRx = Date.now();
      this.inbound = this.inbound.then(() => this.receive(e.data)).catch(() => {});
    };
    ws.onclose = (e) => this.closed(ws, e.code);
  }

  closed(ws, code) {
    if (this.ws !== ws) return;
    this.ws = null;
    this.ready = false;
    this.clearTimers();
    const fatal = FATAL[code];
    if (fatal) {
      this.stopped = true;
      this.onState({ server: "error", peer: false, error: fatal });
      return;
    }
    this.onState({ server: "offline", peer: false });
    if (this.stopped) return;
    const wait = Math.min(MAX_BACKOFF_MS, 500 * 2 ** this.retry++) + Math.random() * 500;
    clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => this.connect(), wait);
  }

  /** 連線看起來還在、其實已經死掉時（iPhone 從背景回來最常見），主動丟掉重連。 */
  drop() {
    const ws = this.ws;
    if (!ws) return;
    this.closed(ws, 0);
    try {
      ws.close(4000, "stale");
    } catch {}
  }

  /** 回到前景、網路恢復時呼叫：確認連線真的活著。 */
  wake() {
    if (this.stopped) return;
    const ws = this.ws;
    if (!ws || ws.readyState > WebSocket.OPEN) return this.reconnectNow();
    if (ws.readyState !== WebSocket.OPEN) return;
    if (Date.now() - this.lastRx > SILENT_MS) return this.drop();
    const before = this.lastRx;
    ws.send("ping");
    clearTimeout(this.wakeTimer);
    this.wakeTimer = setTimeout(() => this.ws === ws && this.lastRx === before && this.drop(), WAKE_TIMEOUT_MS);
  }

  reconnectNow() {
    if (this.stopped || this.ws) return;
    clearTimeout(this.retryTimer);
    this.retry = 0;
    this.connect();
  }

  async receive(data) {
    if (data === "pong") return;
    let msg;
    try {
      msg = JSON.parse(data);
    } catch {
      return;
    }
    if (msg.t === "ready") {
      this.ready = true;
      this.retry = 0;
      this.ice = msg.ice;
      this.turn = msg.turn;
      this.clearTimers();
      this.pingTimer = setInterval(() => {
        if (Date.now() - this.lastRx > SILENT_MS) return this.drop();
        this.ws?.send("ping");
      }, PING_MS);
      this.iceTimer = setInterval(() => this.ws?.send(JSON.stringify({ t: "ice" })), ICE_REFRESH_MS);
      this.onState({ server: "online", peer: msg.peer, turn: msg.turn });
    } else if (msg.t === "ice") {
      this.ice = msg.ice;
    } else if (msg.t === "peer") {
      this.onState({ peer: msg.online });
    } else if (msg.t === "relay") {
      const from = this.role === "viewer" ? "device" : "viewer";
      let inner;
      try {
        inner = await open(this.keys, from, msg.box, this.seen);
      } catch (err) {
        const known = err instanceof Error && !(err instanceof DOMException);
        this.onState({ warn: known ? err.message : "收到無法解開的訊息（可能被竄改），已忽略" });
        return;
      }
      await this.onMessage(inner.k, inner.d);
    }
  }

  async send(kind, data) {
    if (!this.open) return false;
    const box = await seal(this.keys, this.role, kind, data);
    if (!this.open) return false;
    this.ws.send(JSON.stringify({ t: "relay", box }));
    return true;
  }
}
