const te = new TextEncoder();
const td = new TextDecoder();
const MAX_SKEW_MS = 5 * 60_000;

export function b64uEncode(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function b64uDecode(text) {
  const s = atob(text.replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(s, (c) => c.charCodeAt(0));
}

const hex = (bytes) => [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");

async function hmac(secret, label) {
  const key = await crypto.subtle.importKey("raw", secret, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, te.encode(label)));
}

export function parsePairCode(text) {
  const [tag, secret, server] = text.trim().split("|");
  if (tag !== "GR1" || !secret || !/^https:\/\/[^\s|]+$/.test(server || "")) throw new Error("不是 GhostRoom 的配對碼");
  const bytes = b64uDecode(secret);
  if (bytes.length !== 32) throw new Error("配對碼長度不對，請重新掃描");
  return { secret, server: server.replace(/\/+$/, "") };
}

export async function deriveKeys(secretB64u) {
  const secret = b64uDecode(secretB64u);
  const encRaw = await hmac(secret, "ghostroom/enc");
  return {
    room: hex(await hmac(secret, "ghostroom/room")).slice(0, 32),
    auth: b64uEncode(await hmac(secret, "ghostroom/auth")),
    aes: await crypto.subtle.importKey("raw", encRaw, "AES-GCM", false, ["encrypt", "decrypt"]),
  };
}

export async function seal(keys, fromRole, kind, data) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const nonce = b64uEncode(crypto.getRandomValues(new Uint8Array(9)));
  const body = te.encode(JSON.stringify({ k: kind, ts: Date.now(), n: nonce, d: data }));
  const ad = te.encode(`${keys.room}|${fromRole}`);
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: ad }, keys.aes, body));
  const out = new Uint8Array(iv.length + ct.length);
  out.set(iv);
  out.set(ct, iv.length);
  return b64uEncode(out);
}

export async function open(keys, fromRole, box, seen) {
  const raw = b64uDecode(box);
  const ad = te.encode(`${keys.room}|${fromRole}`);
  const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: raw.subarray(0, 12), additionalData: ad }, keys.aes, raw.subarray(12));
  const msg = JSON.parse(td.decode(plain));
  const now = Date.now();
  if (Math.abs(now - msg.ts) > MAX_SKEW_MS) throw new Error("訊息時間差太大，請確認兩支手機的時間都是自動設定");
  if (seen.has(msg.n)) throw new Error("重複的訊息");
  seen.set(msg.n, now);
  for (const [n, t] of seen) if (now - t > MAX_SKEW_MS * 2) seen.delete(n);
  return msg;
}
