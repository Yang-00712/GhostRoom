package tw.yang.ghostroom

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** 一次 iPhone 連線。所有狀態只在 exec 這條執行緒上改。 */
class PeerSession(
    private val ctx: Context,
    private val factory: PeerConnectionFactory,
    val sid: String,
    private val iceServers: List<PeerConnection.IceServer>,
    private val exec: ScheduledExecutorService,
    private val send: (String, JSONObject) -> Unit,
    private val onEnded: (PeerSession, String) -> Unit,
    private val onLive: (Boolean) -> Unit,
    private val onActivity: () -> Unit,
    private val video: VideoTrack?,
    private val screenInfo: () -> JSONObject,
    private val onControl: (JSONObject) -> Unit,
) {
    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null
    private val pending = mutableListOf<IceCandidate>()
    private var remoteSet = false
    private var closed = false
    private var dropTimer: ScheduledFuture<*>? = null

    private var ticket: String? = null
    private var tx = JSONObject()
    private var hasVideo = false

    fun acceptOffer(sdp: String, ticket: String?) {
        this.ticket = ticket
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val conn = factory.createPeerConnection(config, observer) ?: return end("無法建立連線元件")
        pc = conn
        conn.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() = exec.execute { onRemoteSet() }
            override fun onSetFailure(error: String?) = exec.execute { end("iPhone 的連線資料有誤：$error") }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun onRemoteSet() {
        val conn = pc ?: return
        remoteSet = true
        pending.forEach { conn.addIceCandidate(it) }
        pending.clear()
        video?.let { track ->
            val sender = conn.addTrack(track, listOf("screen"))
            hasVideo = sender != null
            sender?.let(::tune)
        }
        conn.createAnswer(object : Sdp() {
            override fun onCreateSuccess(desc: SessionDescription?) = exec.execute {
                if (desc == null || closed) return@execute
                conn.setLocalDescription(object : Sdp() {
                    override fun onSetSuccess() = exec.execute {
                        send("answer", JSONObject().put("sid", sid).put("sdp", desc.description).put("ticket", ticket))
                    }
                    override fun onSetFailure(error: String?) = exec.execute { end("無法建立回應：$error") }
                }, desc)
            }
            override fun onCreateFailure(error: String?) = exec.execute { end("無法建立回應：$error") }
        }, MediaConstraints())
    }

    fun addIce(c: JSONObject) {
        val sdp = c.optString("candidate")
        if (sdp.isEmpty()) return
        val candidate = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), sdp)
        if (remoteSet) pc?.addIceCandidate(candidate) else pending += candidate
    }

    fun end(reason: String) {
        if (closed) return
        closed = true
        onLive(false)
        dropTimer?.cancel(false)
        channel?.unregisterObserver()
        channel?.close()
        channel?.dispose()
        channel = null
        pc?.senders?.forEach { it.setTrack(null, false) }
        pc?.dispose()
        pc = null
        onEnded(this, reason)
    }

    private fun reply(obj: JSONObject) {
        val ch = channel ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        ch.send(DataChannel.Buffer(ByteBuffer.wrap(obj.toString().toByteArray(Charsets.UTF_8)), false))
    }

    private fun onChannelMessage(text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (m.optString("t")) {
            "ping" -> {
                onActivity()
                refreshTx()
                reply(JSONObject().put("t", "pong").put("ts", m.opt("ts")).put("info", deviceInfo()))
            }
            "gesture", "key" -> {
                onActivity()
                onControl(m)
            }
        }
    }

    private fun deviceInfo(): JSONObject {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return JSONObject()
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", bm.isCharging)
            .put("screen", screenInfo())
            .put("video", hasVideo)
            .put("control", ControlService.enabled)
            .put("tx", tx)
    }

    /** 編碼器那段的數字，給 iPhone 顯示診斷用。結果下一次 ping 才帶回去。 */
    private fun refreshTx() {
        pc?.getStats { report ->
            val out = report.statsMap.values.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" } ?: return@getStats
            val m = out.members
            val json = JSONObject()
                .put("enc", (m["framesEncoded"] as? Number)?.toLong() ?: 0)
                .put("sent", (m["framesSent"] as? Number)?.toLong() ?: 0)
                .put("bytes", m["bytesSent"]?.toString() ?: "0")
                .put("impl", m["encoderImplementation"]?.toString() ?: "")
                .put("limit", m["qualityLimitationReason"]?.toString() ?: "")
            runCatching { exec.execute { tx = json } }
        }
    }

    /** 行動網路下也順：限制位元率，畫面變化大時寧可降一點畫質也不要卡。 */
    private fun tune(sender: RtpSender) {
        val p = sender.parameters
        p.degradationPreference = RtpParameters.DegradationPreference.BALANCED
        p.encodings.forEach {
            it.maxBitrateBps = MAX_BITRATE
            it.maxFramerate = ScreenShare.FPS
        }
        sender.setParameters(p)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) = exec.execute {
            if (closed) return@execute
            val json = JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
            send("ice", JSONObject().put("sid", sid).put("c", json))
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = exec.execute {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                    dropTimer?.cancel(false)
                    onLive(true)
                    Status.update { session = "🟢 已連線" }
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    Status.update { session = "🟡 訊號不穩" }
                    dropTimer = exec.schedule({ end("網路中斷太久") }, 15, TimeUnit.SECONDS)
                }
                PeerConnection.IceConnectionState.FAILED -> end("網路連不上")
                else -> Unit
            }
        }

        override fun onDataChannel(dc: DataChannel) = exec.execute {
            if (closed) return@execute dc.dispose()
            channel = dc
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previous: Long) = Unit
                override fun onStateChange() = exec.execute {
                    if (dc.state() == DataChannel.State.CLOSED && channel === dc) end("iPhone 結束了連線")
                }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    exec.execute { onChannelMessage(String(bytes, Charsets.UTF_8)) }
                }
            })
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    companion object {
        private const val MAX_BITRATE = 1_500_000
    }
}
