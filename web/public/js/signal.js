import { seal, open } from "./crypto.js";

const FATAL = {
  4001: "另一支 iPhone 接手了連線",
  4003: "配對密鑰不對，請在 Android 上重新顯示 QR 再配對",
  4004: "Android 還沒上線過，請先在 Android 上按「開始待命」",
  4029: "密碼錯太多次，房間鎖住 10 分鐘",
};

export class Signal {
  constructor({ server, keys, role, onState, onMessage }) {
    Object.assign(this, { server, keys, role, onState, onMessage });
    this.seen = new Map();
    this.retry = 0;
    this.stopped = true;
    this.ice = null;
  }

  start() {
    this.stopped = false;
    this.connect();
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.retryTimer);
    clearInterval(this.pingTimer);
    this.ws?.close(1000, "bye");
    this.ws = null;
  }

  get open() {
    return this.ws?.readyState === WebSocket.OPEN && this.ready;
  }

  connect() {
    if (this.stopped) return;
    this.ready = false;
    this.onState({ server: "connecting" });
    const url = `${this.server.replace(/^http/, "ws")}/api/ws?room=${this.keys.room}`;
    const ws = (this.ws = new WebSocket(url));
    ws.onopen = () => ws.send(JSON.stringify({ t: "hello", role: this.role, auth: this.keys.auth }));
    ws.onmessage = (e) => this.receive(e.data);
    ws.onclose = (e) => {
      if (this.ws !== ws) return;
      clearInterval(this.pingTimer);
      this.ready = false;
      const fatal = FATAL[e.code];
      if (fatal) {
        this.stopped = true;
        this.onState({ server: "error", peer: false, error: fatal });
        return;
      }
      this.onState({ server: "offline", peer: false });
      const wait = Math.min(30_000, 1000 * 2 ** this.retry++);
      this.retryTimer = setTimeout(() => this.connect(), wait);
    };
  }

  reconnectNow() {
    if (this.stopped || this.open) return;
    clearTimeout(this.retryTimer);
    this.retry = 0;
    if (this.ws?.readyState === WebSocket.CONNECTING) return;
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
      clearInterval(this.pingTimer);
      this.pingTimer = setInterval(() => this.ws?.readyState === WebSocket.OPEN && this.ws.send("ping"), 25_000);
      this.onState({ server: "online", peer: msg.peer, turn: msg.turn });
    } else if (msg.t === "ice") {
      this.ice = msg.ice;
    } else if (msg.t === "peer") {
      this.onState({ peer: msg.online });
    } else if (msg.t === "relay") {
      const from = this.role === "viewer" ? "device" : "viewer";
      try {
        const inner = await open(this.keys, from, msg.box, this.seen);
        this.onMessage(inner.k, inner.d);
      } catch (err) {
        const known = err instanceof Error && !(err instanceof DOMException);
        this.onState({ warn: known ? err.message : "收到無法解開的訊息（可能被竄改），已忽略" });
      }
    }
  }

  async send(kind, data) {
    if (!this.open) return false;
    this.ws.send(JSON.stringify({ t: "relay", box: await seal(this.keys, this.role, kind, data) }));
    return true;
  }
}
