import { parsePairCode, deriveKeys } from "./crypto.js";
import { Signal } from "./signal.js";
import { ViewerSession } from "./rtc.js";

const PAIR_KEY = "gr.pair";
const RELAY_KEY = "gr.forceRelay";
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

let signal = null;
let session = null;

function say(el, text, kind = "") {
  el.textContent = text || "";
  el.className = `status ${kind}`.trim();
}

function log(text) {
  const li = document.createElement("li");
  li.textContent = `${new Date().toLocaleTimeString("zh-TW", { hour12: false })}　${text}`;
  const list = $("logList");
  list.prepend(li);
  while (list.children.length > 30) list.lastChild.remove();
}

function show(view) {
  $("pairView").hidden = view !== "pair";
  $("mainView").hidden = view !== "main";
}

const SERVER_TEXT = {
  connecting: "🟡 連線中",
  online: "🟢 已連線",
  offline: "🔴 斷線，重試中",
  error: "🔴 停止",
};

function refreshConnectButton() {
  const btn = $("connectBtn");
  const live = session && !session.closed;
  btn.textContent = live ? "⏹ 中斷" : "🔗 連線";
  btn.disabled = !live && !(signal?.open && signal.peerOnline);
}

function onSignalState(s) {
  if (s.server) {
    $("serverPill").textContent = SERVER_TEXT[s.server];
    if (s.server === "online") log(s.turn ? "已連上伺服器（中繼可用）" : "已連上伺服器（中繼未設定，只能直連）");
    if (s.server === "offline") log("伺服器斷線，稍後自動重連");
  }
  if (s.turn != null) $("turnState").textContent = s.turn ? "🟢 可用" : "⚠️ 未設定";
  if (s.peer != null) {
    signal.peerOnline = s.peer;
    $("peerState").textContent = s.peer ? "🟢 在線待命" : "⚪ 離線";
    if (s.server !== "offline") log(s.peer ? "Android 上線" : "Android 離線");
  }
  if (s.error) {
    say($("mainMsg"), s.error, "error");
    log(s.error);
  }
  if (s.warn) log(s.warn);
  refreshConnectButton();
}

function onSessionState(s) {
  if (s.rtc === "connecting") {
    say($("mainMsg"), "正在建立連線…");
    log("開始連線");
  }
  if (s.rtc === "open") {
    say($("mainMsg"), "已連上 Android，加密通道已建立", "ok");
    log("加密通道已建立");
  }
  if (s.rtc === "closed") {
    say($("mainMsg"), s.reason || "已中斷", s.reason ? "error" : "");
    log(s.reason || "已中斷");
    for (const id of ["routeState", "rttState"]) $(id).textContent = "—";
  }
  if (s.route) $("routeState").textContent = s.route === "relay" ? "🛰 經中繼" : "⚡ 直連";
  if (s.rtt != null) $("rttState").textContent = `${s.rtt} ms`;
  if (s.info) {
    $("modelState").textContent = `${s.info.model}（Android ${s.info.android}）`;
    $("batteryState").textContent = s.info.battery >= 0 ? `${s.info.battery}%${s.info.charging ? " ⚡" : ""}` : "—";
  }
  refreshConnectButton();
}

async function startSignal(pair) {
  signal?.stop();
  const keys = await deriveKeys(pair.secret);
  signal = new Signal({
    server: pair.server,
    keys,
    role: "viewer",
    onState: onSignalState,
    onMessage: (kind, d) => session?.handle(kind, d).catch((err) => log(`連線資料錯誤：${err.message}`)),
  });
  signal.start();
  show("main");
}

async function connectToggle() {
  if (session && !session.closed) {
    session.close();
    return;
  }
  session = new ViewerSession({
    signal,
    forceRelay: $("forceRelay").checked,
    onState: onSessionState,
    onData: () => {},
  });
  try {
    await session.start();
  } catch (err) {
    session.close(`無法開始連線：${err.message}`);
  }
}

async function acceptPairCode(text) {
  const msg = $("pairMsg");
  try {
    const pair = parsePairCode(text);
    if (!store.set(PAIR_KEY, JSON.stringify(pair))) {
      say(msg, "無法儲存配對：Safari 擋住了網站資料，請關閉「私密瀏覽」後再試", "error");
      return;
    }
    say(msg, "");
    $("pairText").value = "";
    await startSignal(pair);
    log("配對完成");
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
  $("scanBox").hidden = true;
  $("scanBtn").hidden = false;
}

function unpair() {
  if (!confirm("清除後要重新掃 QR 才能連線，確定嗎？")) return;
  session?.close();
  signal?.stop();
  signal = null;
  store.set(PAIR_KEY, null);
  $("serverPill").textContent = "⚪ 未連線";
  say($("pairMsg"), "");
  show("pair");
}

function init() {
  $("scanBtn").onclick = startScan;
  $("scanStop").onclick = () => {
    stopScan();
    say($("pairMsg"), "");
  };
  $("pairBtn").onclick = () => acceptPairCode($("pairText").value);
  $("connectBtn").onclick = connectToggle;
  $("unpairBtn").onclick = unpair;
  $("forceRelay").checked = store.get(RELAY_KEY) === "1";
  $("forceRelay").onchange = (e) => store.set(RELAY_KEY, e.target.checked ? "1" : "0");

  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") signal?.reconnectNow();
    else stopScan();
  });

  let pair = null;
  try {
    pair = JSON.parse(store.get(PAIR_KEY) || "null");
  } catch {}
  if (pair?.secret && pair?.server) startSignal(pair);
  else show("pair");

  if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
}

init();
