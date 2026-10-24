import { DurableObject } from "cloudflare:workers";

const ROLES = ["device", "viewer"];
const HELLO_TIMEOUT_MS = 10_000;
const MAX_FAILS = 10;
const FAIL_WINDOW_MS = 10 * 60_000;
const MAX_MESSAGE = 64 * 1024;
const TURN_TTL_S = 6 * 3600;
const STUN_ONLY = [{ urls: ["stun:stun.cloudflare.com:3478"] }];

const other = (role) => (role === "device" ? "viewer" : "device");

async function sha256hex(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function sameHex(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export async function routeToRoom(request, env) {
  const url = new URL(request.url);
  if (url.pathname !== "/api/ws") return new Response("not found", { status: 404 });
  const room = url.searchParams.get("room") || "";
  if (!/^[0-9a-f]{32}$/.test(room)) return new Response("bad room", { status: 400 });
  if (request.headers.get("Upgrade") !== "websocket") return new Response("websocket only", { status: 426 });
  return env.ROOMS.get(env.ROOMS.idFromName(room)).fetch(request);
}

export default {
  fetch: routeToRoom,
};

export class Room extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch() {
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({ role: null, at: Date.now() });
    if ((await this.ctx.storage.getAlarm()) == null) await this.ctx.storage.setAlarm(Date.now() + HELLO_TIMEOUT_MS + 500);
    return new Response(null, { status: 101, webSocket: client });
  }

  async alarm() {
    let next = Infinity;
    for (const ws of this.ctx.getWebSockets()) {
      const att = ws.deserializeAttachment() || {};
      if (att.role) continue;
      const deadline = att.at + HELLO_TIMEOUT_MS;
      if (Date.now() >= deadline - 1000) ws.close(4000, "hello timeout");
      else next = Math.min(next, deadline);
    }
    if (next !== Infinity) await this.ctx.storage.setAlarm(next + 500);
  }

  sockets(role) {
    return this.ctx.getWebSockets().filter((ws) => {
      const att = ws.deserializeAttachment();
      return att && att.role === role && ws.readyState === WebSocket.OPEN;
    });
  }

  async webSocketMessage(ws, raw) {
    if (typeof raw !== "string" || raw.length > MAX_MESSAGE) return ws.close(1009, "message too big");
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch {
      return ws.close(1007, "bad json");
    }
    const att = ws.deserializeAttachment() || {};
    if (!att.role) return this.hello(ws, msg);

    if (msg.t === "relay" && typeof msg.box === "string") {
      const out = JSON.stringify({ t: "relay", box: msg.box });
      for (const peer of this.sockets(other(att.role))) peer.send(out);
    } else if (msg.t === "ice") {
      ws.send(JSON.stringify({ t: "ice", ...(await this.iceServers(att.role)) }));
    }
  }

  async hello(ws, msg) {
    if (msg.t !== "hello" || !ROLES.includes(msg.role) || typeof msg.auth !== "string" || msg.auth.length > 128) {
      return ws.close(4000, "hello required");
    }
    const now = Date.now();
    let fails = (await this.ctx.storage.get("fails")) || { n: 0, since: now };
    if (now - fails.since > FAIL_WINDOW_MS) fails = { n: 0, since: now };
    if (fails.n >= MAX_FAILS) return ws.close(4029, "locked");

    const hash = await sha256hex(msg.auth);
    let stored = await this.ctx.storage.get("authHash");
    if (!stored) {
      if (msg.role !== "device") return ws.close(4004, "device not registered");
      await this.ctx.storage.put("authHash", hash);
      stored = hash;
    }
    if (!sameHex(hash, stored)) {
      fails.n += 1;
      await this.ctx.storage.put("fails", fails);
      return ws.close(4003, "bad auth");
    }
    if (fails.n) await this.ctx.storage.delete("fails");

    for (const old of this.sockets(msg.role)) old.close(4001, "replaced");
    ws.serializeAttachment({ role: msg.role });
    const peers = this.sockets(other(msg.role));
    ws.send(JSON.stringify({ t: "ready", peer: peers.length > 0, ...(await this.iceServers(msg.role)) }));
    const online = JSON.stringify({ t: "peer", online: true });
    for (const peer of peers) peer.send(online);
  }

  async webSocketClose(ws, code) {
    try {
      ws.close(code === 1005 || code === 1006 ? 1000 : code, "closed");
    } catch {}
    this.left(ws);
  }

  async webSocketError(ws) {
    this.left(ws);
  }

  left(ws) {
    const att = ws.deserializeAttachment() || {};
    if (!att.role || this.sockets(att.role).some((s) => s !== ws)) return;
    const offline = JSON.stringify({ t: "peer", online: false });
    for (const peer of this.sockets(other(att.role))) peer.send(offline);
  }

  async iceServers(role) {
    const { TURN_KEY_ID, TURN_KEY_API_TOKEN } = this.env;
    if (!TURN_KEY_ID || !TURN_KEY_API_TOKEN) return { ice: STUN_ONLY, turn: false };
    try {
      const res = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${TURN_KEY_ID}/credentials/generate-ice-servers`, {
        method: "POST",
        headers: { Authorization: `Bearer ${TURN_KEY_API_TOKEN}`, "Content-Type": "application/json" },
        body: JSON.stringify({ ttl: TURN_TTL_S, customIdentifier: role }),
      });
      if (!res.ok) throw new Error(`TURN ${res.status}`);
      const { iceServers } = await res.json();
      const ice = iceServers.map((s) => ({ ...s, urls: [].concat(s.urls).filter((u) => !/:53\b/.test(u)) }));
      return { ice, turn: true };
    } catch {
      return { ice: STUN_ONLY, turn: false };
    }
  }
}
