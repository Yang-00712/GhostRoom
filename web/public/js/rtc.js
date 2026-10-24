const STATS_MS = 2000;
const CONNECT_TIMEOUT_MS = 20_000;

export class ViewerSession {
  constructor({ signal, forceRelay, onState, onData }) {
    Object.assign(this, { signal, forceRelay, onState, onData });
    this.sid = crypto.randomUUID();
    this.pending = [];
    this.closed = false;
  }

  async start(auth) {
    const pc = (this.pc = new RTCPeerConnection({
      iceServers: this.signal.ice,
      iceTransportPolicy: this.forceRelay ? "relay" : "all",
    }));
    this.dc = pc.createDataChannel("ctl", { ordered: true });
    this.dc.onopen = () => {
      this.onState({ rtc: "open" });
      this.pingTimer = setInterval(() => this.ping(), STATS_MS);
      this.ping();
    };
    this.dc.onclose = () => this.close("和 Android 的連線中斷了", { retry: true });
    this.dc.onmessage = (e) => this.message(e.data);
    pc.onicecandidate = (e) => e.candidate && this.signal.send("ice", { sid: this.sid, c: e.candidate.toJSON() });
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === "failed") {
        this.close(this.forceRelay ? "中繼連不上：TURN 可能還沒設定" : "連不上 Android：網路被擋，請試「強制走中繼」", { retry: true });
      }
    };
    this.timeout = setTimeout(() => this.dc.readyState !== "open" && this.close("20 秒內沒連上，請確認 Android 在待命", { retry: true }), CONNECT_TIMEOUT_MS);

    this.onState({ rtc: "connecting" });
    await pc.setLocalDescription(await pc.createOffer());
    const sent = await this.signal.send("offer", { sid: this.sid, sdp: pc.localDescription.sdp, auth });
    if (!sent) this.close("伺服器斷線中，稍後自動重試", { retry: true });
  }

  async handle(kind, d) {
    if (!this.pc || this.closed || d?.sid !== this.sid) return;
    if (kind === "answer") {
      if (d.ticket) this.onState({ ticket: d.ticket });
      await this.pc.setRemoteDescription({ type: "answer", sdp: d.sdp });
      for (const c of this.pending.splice(0)) await this.pc.addIceCandidate(c).catch(() => {});
    } else if (kind === "ice") {
      if (this.pc.remoteDescription) await this.pc.addIceCandidate(d.c).catch(() => {});
      else this.pending.push(d.c);
    } else if (kind === "denied") {
      this.close(d.reason || "Android 拒絕了這次連線", { denied: true });
    } else if (kind === "bye") {
      this.close(d.reason || "Android 結束了連線", { retry: true });
    }
  }

  send(obj) {
    if (this.dc?.readyState === "open") this.dc.send(JSON.stringify(obj));
  }

  ping() {
    this.send({ t: "ping", ts: performance.now() });
    this.route();
  }

  message(raw) {
    let m;
    try {
      m = JSON.parse(raw);
    } catch {
      return;
    }
    if (m.t === "pong") this.onState({ rtt: Math.round(performance.now() - m.ts), info: m.info });
    else this.onData(m);
  }

  async route() {
    const stats = await this.pc.getStats();
    let pair;
    stats.forEach((s) => {
      if (s.type === "transport" && s.selectedCandidatePairId) pair = stats.get(s.selectedCandidatePairId);
    });
    if (!pair) stats.forEach((s) => s.type === "candidate-pair" && s.nominated && s.state === "succeeded" && (pair = s));
    if (!pair) return;
    const local = stats.get(pair.localCandidateId);
    const remote = stats.get(pair.remoteCandidateId);
    const relay = local?.candidateType === "relay" || remote?.candidateType === "relay";
    this.onState({ route: relay ? "relay" : "direct" });
  }

  /** reason 沒給 = 使用者自己按中斷；retry = 可以自動重連；denied = Android 拒絕，不要重試。 */
  close(reason, { retry = false, denied = false } = {}) {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.pingTimer);
    clearTimeout(this.timeout);
    if (!denied) this.signal.send("bye", { sid: this.sid });
    this.dc?.close();
    this.pc?.close();
    this.onState({ rtc: "closed", reason, retry, denied });
  }
}
