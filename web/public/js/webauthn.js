import { b64uEncode, b64uDecode } from "./crypto.js";

const u8 = (buf) => new Uint8Array(buf instanceof ArrayBuffer ? buf : buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength));

function cbor(bytes) {
  let i = 0;
  const len = (info) => {
    if (info < 24) return info;
    const n = 1 << (info - 24);
    let v = 0;
    for (let k = 0; k < n; k++) v = v * 256 + bytes[i++];
    return v;
  };
  const item = () => {
    const head = bytes[i++];
    const major = head >> 5;
    const n = len(head & 31);
    switch (major) {
      case 0: return n;
      case 1: return -1 - n;
      case 2: return bytes.subarray(i, (i += n));
      case 3: return new TextDecoder().decode(bytes.subarray(i, (i += n)));
      case 4: return Array.from({ length: n }, item);
      case 5: {
        const m = new Map();
        for (let k = 0; k < n; k++) m.set(item(), item());
        return m;
      }
      default: throw new Error("無法讀取 Face ID 金鑰格式");
    }
  };
  const value = item();
  return { value, end: i };
}

const SPKI_P256 = Uint8Array.from([
  0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
  0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04,
]);

export function spkiFromAttestation(attestationObject) {
  const authData = cbor(u8(attestationObject)).value.get("authData");
  const idLen = (authData[53] << 8) | authData[54];
  const cose = cbor(authData.subarray(55 + idLen)).value;
  if (cose.get(3) !== -7) throw new Error("這支 iPhone 的金鑰類型不支援");
  const out = new Uint8Array(SPKI_P256.length + 64);
  out.set(SPKI_P256);
  out.set(cose.get(-2), SPKI_P256.length);
  out.set(cose.get(-3), SPKI_P256.length + 32);
  return out;
}

export function supported() {
  return !!(window.PublicKeyCredential && navigator.credentials?.create);
}

export async function enroll() {
  const cred = await navigator.credentials.create({
    publicKey: {
      rp: { id: location.hostname, name: "GhostRoom" },
      user: { id: crypto.getRandomValues(new Uint8Array(16)), name: "GhostRoom iPhone", displayName: "GhostRoom iPhone" },
      challenge: crypto.getRandomValues(new Uint8Array(32)),
      pubKeyCredParams: [{ type: "public-key", alg: -7 }],
      authenticatorSelection: { authenticatorAttachment: "platform", userVerification: "required", residentKey: "preferred" },
      attestation: "none",
      timeout: 60_000,
    },
  });
  const spki = cred.response.getPublicKey?.() ? u8(cred.response.getPublicKey()) : spkiFromAttestation(cred.response.attestationObject);
  return { credId: b64uEncode(u8(cred.rawId)), spki: b64uEncode(spki) };
}

export async function assert(credId, nonce) {
  const a = await navigator.credentials.get({
    publicKey: {
      challenge: b64uDecode(nonce),
      rpId: location.hostname,
      allowCredentials: [{ type: "public-key", id: b64uDecode(credId) }],
      userVerification: "required",
      timeout: 60_000,
    },
  });
  return {
    type: "webauthn",
    credId,
    nonce,
    authData: b64uEncode(u8(a.response.authenticatorData)),
    clientData: b64uEncode(u8(a.response.clientDataJSON)),
    sig: b64uEncode(u8(a.response.signature)),
  };
}

export function explain(err) {
  if (err?.name === "NotAllowedError") return "Face ID 取消或逾時，請再按一次";
  if (err?.name === "InvalidStateError") return "這支 iPhone 已經有 GhostRoom 的 Face ID 金鑰，請到「設定 → 密碼」刪掉 GhostRoom 後再綁定";
  if (err?.name === "SecurityError") return "Face ID 只能在 GhostRoom 正式網址使用，請從主畫面的 GhostRoom 打開";
  return `Face ID 失敗：${err?.message || err}`;
}
