import { parsePairCode, deriveKeys } from "./crypto.js";
import { Signal } from "./signal.js";
import { ViewerSession } from "./rtc.js";
import * as faceId from "./webauthn.js";

const PAIR_KEY = "gr.pair";
const RELAY_KEY = "gr.forceRelay";
const CHALLENGE_TTL_MS = 100_000;
const RETRY_DELAYS_S = [1, 2, 4, 8, 15];
const $ = (id) => document.getElementById(id);

const store = {
  get(key) {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  set(key, value) {
    try {
      if (value == null) localStorage.removeItem(key);
      else localStorage.setItem(key, value);
      return true;
    } catch {
      return false;
    }
  },
};

let pair = null;
let signal = null;
let session = null;
let ticket = null;
let challenge = null;
let challengeAsked = 0;
let pendingEnroll = null;
let want = false;
let retryCount = 0;
let retryTimer = null;
let screenNote = "";
let currentStream = null;

function say(el, text, kind = "") {
  el.textContent = text || "";
  el.className = `status ${kind}`.trim();
}

function log(text) {
  const li = document.createElement("li");
  li.textContent = `${new Date().toLocaleTimeString("zh-TW", { hour12: false })}　${text}`;
  const list = $("logList");
  list.prepend(li);
  while (list.children.length > 40) list.lastChild.remove();
}

function show(view) {
  for (const v of ["pair", "bind", "main"]) $(`${v}View`).hidden = v !== view;
}

const SERVER_TEXT = {
  connecting: "🟡 連線中",
  online: "🟢 已連線",
  offline: "🔴 斷線，重試中",
  error: "🔴 停止",
};

const ready = () => !!(signal?.open && signal.peerOnline);
const live = () => !!(session && !session.closed);

function refreshButtons() {
  const btn = $("connectBtn");
  btn.textContent = live() ? "⏹ 中斷" : ticket ? "🔗 連線" : "🔗 Face ID 連線";
  btn.disabled = !live() && !ready();
  $("bindBtn").disabled = !ready() || !!pendingEnroll;
}

function askChallenge(force = false) {
  if (!pair?.credId || ticket || !ready()) return;
  const fresh = challenge && Date.now() - challenge.at < CHALLENGE_TTL_MS;
  if (fresh && !force) return;
  if (Date.now() - challengeAsked < 3000) return;
  challengeAsked = Date.now();
  signal.send("challenge", {});
}

function onSignalState(s) {
  if (s.server) {
    $("serverPill").textContent = SERVER_TEXT[s.server];
    if (s.server === "online") log(s.turn ? "已連上伺服器（中繼可用）" : "已連上伺服器（中繼未設定，只能直連）");
    if (s.server === "offline") log("伺服器斷線，自動重連中");
  }
  if (s.turn != null) $("turnState").textContent = s.turn ? "🟢 可用" : "⚠️ 未設定";
  if (s.peer != null) {
    const changed = signal.peerOnline !== s.peer;
    signal.peerOnline = s.peer;
    const text = s.peer ? "🟢 在線待命" : "⚪ 離線";
    $("peerState").textContent = text;
    $("bindPeer").textContent = text;
    if (changed && s.server !== "offline") log(s.peer ? "Android 上線" : "Android 離線");
    if (s.peer) {
      askChallenge();
      if (want && !live()) retryNow();
    }
  }
  if (s.error) {
    say($("mainMsg"), s.error, "error");
    say($("bindMsg"), s.error, "error");
    log(s.error);
  }
  if (s.warn) log(s.warn);
  refreshButtons();
}

function screenProblem(screen) {
  if (!screen.shared) return "⚠️ Android 還沒允許傳送畫面（回家在 Android 按「📺 允許傳送畫面」）";
  if (screen.locked && screen.secure) return "🔒 Android 用指紋／密碼鎖著，遠端解不開";
  if (!screen.on) return "💤 Android 螢幕關著（要在 Android 允許「顯示在上層」才能自動點亮）";
  if (screen.locked) return "🔒 Android 停在鎖定畫面";
  return "";
}

/** 只把 srcObject 清掉不夠：iPhone 會把它當成還在播的影片，卡在動態島和鎖定畫面。 */
function releaseVideo(v) {
  v.pause();
  v.srcObject = null;
  v.removeAttribute("src");
  v.load();
}

function clearNowPlaying() {
  if (!("mediaSession" in navigator)) return;
  try {
    navigator.mediaSession.metadata = null;
    navigator.mediaSession.playbackState = "none";
  } catch {}
}

function showScreen(stream) {
  const v = $("screenVideo");
  if (!stream) {
    currentStream = null;
    releaseVideo(v);
    clearNowPlaying();
    $("screenBox").hidden = true;
    $("screenBox").classList.remove("focus");
    $("focusBtn").textContent = "⛶ 放大";
    $("videoState").textContent = "—";
    screenNote = "";
    return;
  }
  currentStream = stream;
  v.srcObject = stream;
  $("screenBox").hidden = false;
  v.play().catch(() => {});
}

function toggleFocus() {
  const on = $("screenBox").classList.toggle("focus");
  $("focusBtn").textContent = on ? "✖ 縮小" : "⛶ 放大";
}

function onSessionState(s) {
  if (s.ticket) ticket = s.ticket;
  if (s.stream) showScreen(s.stream);
  if (s.info?.screen) {
    const note = screenProblem(s.info.screen);
    if (note && note !== screenNote) log(note);
    screenNote = note;
    if (note) $("videoState").textContent = note;
  }
  if (s.video && !screenNote) {
    $("videoState").textContent = `${s.video.w}×${s.video.h}・${s.video.fps} fps・${(s.video.kbps / 1000).toFixed(1)} Mbps`;
  }
  if (s.rtc === "connecting") {
    say($("mainMsg"), "正在建立連線…");
    log("開始連線");
  }
  if (s.rtc === "open") {
    retryCount = 0;
    say($("mainMsg"), "已連上 Android，加密通道已建立", "ok");
    log("加密通道已建立");
  }
  if (s.rtc === "closed") {
    for (const id of ["routeState", "rttState"]) $(id).textContent = "—";
    showScreen(null);
    if (!s.reason) {
      say($("mainMsg"), "已中斷");
      log("已中斷");
    } else if (s.denied) {
      want = false;
      ticket = null;
      say($("mainMsg"), `${s.reason}。請按「Face ID 連線」重新驗證`, "error");
      log(s.reason);
      askChallenge(true);
    } else {
      log(s.reason);
      if (want && s.retry) scheduleRetry(s.reason);
      else say($("mainMsg"), s.reason, "error");
    }
  }
  if (s.route) $("routeState").textContent = s.route === "relay" ? "🛰 經中繼" : "⚡ 直連";
  if (s.rtt != null) $("rttState").textContent = `${s.rtt} ms`;
  if (s.info) {
    $("modelState").textContent = `${s.info.model}（Android ${s.info.android}）`;
    $("batteryState").textContent = s.info.battery >= 0 ? `${s.info.battery}%${s.info.charging ? " ⚡" : ""}` : "—";
  }
  refreshButtons();
}

function openSession(auth) {
  session = new ViewerSession({
    signal,
    forceRelay: $("forceRelay").checked,
    onState: onSessionState,
    onData: () => {},
  });
  session.start(auth).catch((err) => session.close(`無法開始連線：${err.message}`, { retry: true }));
}

function scheduleRetry(reason) {
  clearTimeout(retryTimer);
  if (!ticket) {
    want = false;
    say($("mainMsg"), `${reason}。請按「Face ID 連線」重新驗證`, "error");
    askChallenge(true);
    return;
  }
  if (retryCount >= RETRY_DELAYS_S.length) {
    want = false;
    say($("mainMsg"), `自動重連 ${RETRY_DELAYS_S.length} 次都失敗：${reason}`, "error");
    return;
  }
  const sec = RETRY_DELAYS_S[retryCount++];
  say($("mainMsg"), `${reason}，${sec} 秒後自動重連（第 ${retryCount} 次）`);
  retryTimer = setTimeout(retryNow, sec * 1000);
}

function retryNow() {
  clearTimeout(retryTimer);
  if (!want || live() || !ticket || document.visibilityState !== "visible") return;
  if (!ready()) {
    say($("mainMsg"), "等 Android 和伺服器恢復，恢復後自動重連…");
    return;
  }
  openSession({ type: "ticket", ticket });
}

async function connectToggle() {
  if (live()) {
    want = false;
    clearTimeout(retryTimer);
    session.close();
    return;
  }
  retryCount = 0;
  if (ticket) {
    want = true;
    openSession({ type: "ticket", ticket });
    return;
  }
  if (!challenge || Date.now() - challenge.at > CHALLENGE_TTL_MS) {
    askChallenge(true);
    say($("mainMsg"), "正在向 Android 要驗證碼，請 2 秒後再按一次");
    return;
  }
  const c = challenge;
  challenge = null;
  let auth;
  try {
    auth = await faceId.assert(pair.credId, c.nonce);
  } catch (err) {
    say($("mainMsg"), faceId.explain(err), "error");
    askChallenge(true);
    return;
  }
  want = true;
  openSession(auth);
}

function savePair() {
  return store.set(PAIR_KEY, JSON.stringify(pair));
}

async function bind() {
  const msg = $("bindMsg");
  if (!faceId.supported()) return say(msg, "這個瀏覽器不支援 Face ID 金鑰，請用 iPhone 的 Safari 或主畫面的 GhostRoom", "error");
  try {
    pendingEnroll = await faceId.enroll();
  } catch (err) {
    return say(msg, faceId.explain(err), "error");
  }
  refreshButtons();
  say(msg, "等 Android 確認…");
  if (!(await signal.send("enroll", pendingEnroll))) {
    pendingEnroll = null;
    say(msg, "伺服器斷線中，請稍後再按一次", "error");
  }
  refreshButtons();
}

function onEnrolled(d) {
  const enrolled = pendingEnroll;
  pendingEnroll = null;
  if (!d.ok || !enrolled) {
    say($("bindMsg"), d.reason || "Android 拒絕綁定", "error");
    refreshButtons();
    return;
  }
  pair.credId = enrolled.credId;
  savePair();
  log("Face ID 綁定完成");
  show("main");
  say($("mainMsg"), "綁定完成，按「Face ID 連線」試試看", "ok");
  askChallenge(true);
  refreshButtons();
}

async function onRelay(kind, d) {
  if (kind === "challenge") {
    challenge = { nonce: d.nonce, at: Date.now() };
    refreshButtons();
  } else if (kind === "enrolled") {
    onEnrolled(d);
  } else {
    await session?.handle(kind, d).catch((err) => log(`連線資料錯誤：${err.message}`));
  }
}

async function startSignal() {
  signal?.stop();
  const keys = await deriveKeys(pair.secret);
  signal = new Signal({ server: pair.server, keys, role: "viewer", onState: onSignalState, onMessage: onRelay });
  signal.start();
  show(pair.credId ? "main" : "bind");
}

async function acceptPairCode(text) {
  const msg = $("pairMsg");
  try {
    const parsed = parsePairCode(text);
    if (parsed.server !== location.origin && location.hostname !== "localhost") {
      throw new Error(`這組 QR 是給 ${parsed.server} 用的，請從那個網址加到主畫面再配對`);
    }
    pair = parsed;
    if (!savePair()) {
      say(msg, "無法儲存配對：Safari 擋住了網站資料，請關閉「私密瀏覽」後再試", "error");
      return;
    }
    say(msg, "");
    $("pairText").value = "";
    ticket = null;
    challenge = null;
    await startSignal();
    log("配對碼已收到，等待 Face ID 綁定");
  } catch (err) {
    say(msg, err.message, "error");
  }
}

let scanStream = null;
let scanTimer = null;

async function startScan() {
  const msg = $("pairMsg");
  if (!window.jsQR) return say(msg, "掃描元件還沒載入，請稍等一下再按", "error");
  try {
    scanStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" }, audio: false });
  } catch {
    return say(msg, "無法開啟相機：請到「設定 → Safari → 相機」允許，或改用貼上配對碼", "error");
  }
  const video = $("scanVideo");
  video.srcObject = scanStream;
  await video.play();
  $("scanBox").hidden = false;
  $("scanBtn").hidden = true;
  say(msg, "把 Android 上的 QR 對準框內…");
  const canvas = document.createElement("canvas");
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  scanTimer = setInterval(() => {
    if (!video.videoWidth) return;
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    ctx.drawImage(video, 0, 0);
    const code = window.jsQR(ctx.getImageData(0, 0, canvas.width, canvas.height).data, canvas.width, canvas.height);
    if (code?.data) {
      stopScan();
      acceptPairCode(code.data);
    }
  }, 250);
}

function stopScan() {
  clearInterval(scanTimer);
  scanStream?.getTracks().forEach((t) => t.stop());
  scanStream = null;
  releaseVideo($("scanVideo"));
  clearNowPlaying();
  $("scanBox").hidden = true;
  $("scanBtn").hidden = false;
}

function resetPairing() {
  want = false;
  clearTimeout(retryTimer);
  session?.close();
  signal?.stop();
  signal = null;
  pair = null;
  ticket = null;
  challenge = null;
  pendingEnroll = null;
  store.set(PAIR_KEY, null);
  $("serverPill").textContent = "⚪ 未連線";
  for (const id of ["pairMsg", "bindMsg", "mainMsg"]) say($(id), "");
  show("pair");
}

function unpair() {
  if (!confirm("清除後要重新掃 QR、重新綁 Face ID 才能連線，確定嗎？")) return;
  resetPairing();
}

/** 切到背景就把影片放掉，回來再接上，避免 iPhone 把它當成背景播放。 */
function sleep() {
  stopScan();
  releaseVideo($("screenVideo"));
  clearNowPlaying();
}

function wake() {
  if (document.visibilityState !== "visible") return;
  if (currentStream && live() && !$("screenVideo").srcObject) showScreen(currentStream);
  signal?.wake();
  askChallenge();
  if (want && !live()) setTimeout(retryNow, 800);
}

function init() {
  $("scanBtn").onclick = startScan;
  $("scanStop").onclick = () => {
    stopScan();
    say($("pairMsg"), "");
  };
  $("pairBtn").onclick = () => acceptPairCode($("pairText").value);
  $("bindBtn").onclick = bind;
  $("bindCancel").onclick = resetPairing;
  $("connectBtn").onclick = connectToggle;
  $("focusBtn").onclick = toggleFocus;
  $("unpairBtn").onclick = unpair;
  $("forceRelay").checked = store.get(RELAY_KEY) === "1";
  $("forceRelay").onchange = (e) => store.set(RELAY_KEY, e.target.checked ? "1" : "0");

  document.addEventListener("visibilitychange", () => (document.visibilityState === "visible" ? wake() : sleep()));
  window.addEventListener("pagehide", sleep);
  for (const ev of ["pageshow", "focus", "online"]) window.addEventListener(ev, wake);
  setInterval(() => document.visibilityState === "visible" && askChallenge(), 30_000);

  try {
    pair = JSON.parse(store.get(PAIR_KEY) || "null");
  } catch {}
  if (pair?.secret && pair?.server) startSignal();
  else show("pair");

  if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
}

init();
