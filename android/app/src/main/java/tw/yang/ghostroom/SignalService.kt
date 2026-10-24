package tw.yang.ghostroom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class SignalService : Service() {
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private lateinit var keys: Keys
    private lateinit var factory: PeerConnectionFactory
    private lateinit var egl: EglBase
    private lateinit var screen: ScreenShare
    private var screenLock: PowerManager.WakeLock? = null
    private var live = false
    private val gate = Gatekeeper()
    private var ws: WebSocket? = null
    private var ready = false
    private var stopped = false
    private var retry = 0
    private var lastRx = 0L
    private var network: Network? = null
    private var retryTimer: ScheduledFuture<*>? = null
    private var pingTimer: ScheduledFuture<*>? = null
    private var iceTimer: ScheduledFuture<*>? = null
    private var ice: List<PeerConnection.IceServer> = emptyList()
    private var session: PeerSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = post {
            val changed = this@SignalService.network != null && this@SignalService.network != network
            this@SignalService.network = network
            if (!stopped && (changed || ws == null)) reconnectNow()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun post(block: () -> Unit) {
        if (!exec.isShutdown) runCatching { exec.execute(block) }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
        egl = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        screen = ScreenShare(applicationContext, factory, egl) { post { screenStopped() } }
        keys = Keys(Pairing.secret(this))
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)
        exec.execute { connect() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Pairing.setStandby(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PROJECTION) {
            @Suppress("DEPRECATION")
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_DATA)
            if (data != null) {
                promote(media = true)
                post { startScreen(data) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        exec.execute {
            stopped = true
            session?.end("Android 停止待命")
            retryTimer?.cancel(false)
            cancelTimers()
            ws?.close(1000, "bye")
            ws = null
            screen.stop()
            wakeScreen(false)
            factory.dispose()
            egl.release()
            Status.update { server = "⚪ 停止"; iphone = "⚪ 不在線"; session = "—" }
        }
        exec.shutdown()
        super.onDestroy()
    }

    private fun notification(live: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, SignalService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle(if (live) "📱 iPhone 正在連線" else "GhostRoom 待命中")
            .setContentText(if (live) "不是你在用的話，按「停止待命」" else "iPhone 連線時這裡會顯示")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "⏹ 停止待命", stop).build())
            .build()
    }

    private fun startAsForeground() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "待命", NotificationManager.IMPORTANCE_LOW))
        promote(media = false)
    }

    private fun promote(media: Boolean) {
        val n = notification(live)
        when {
            Build.VERSION.SDK_INT >= 34 -> startForeground(
                NOTIFY_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or (if (media) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0),
            )
            media -> startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else -> startForeground(NOTIFY_ID, n)
        }
    }

    private fun startScreen(data: Intent) {
        runCatching { screen.start(data) }
            .onSuccess { Status.update { this.screen = "🟢 可傳送"; message = "已允許傳送畫面" } }
            .onFailure { e ->
                screen.stop()
                promote(media = false)
                Status.update { this.screen = "⚠️ 未允許"; message = "無法開始傳送畫面：${e.message}" }
            }
    }

    private fun screenStopped() {
        screen.stop()
        runCatching { promote(media = false) }
        Status.update { this.screen = "⚠️ 未允許"; message = "傳送畫面的授權結束了，請在 Android 重新按「📺 允許傳送畫面」" }
    }

    private fun screenInfo(): JSONObject {
        val km = getSystemService(KeyguardManager::class.java)
        return JSONObject()
            .put("shared", screen.active)
            .put("on", getSystemService(PowerManager::class.java).isInteractive)
            .put("locked", km.isKeyguardLocked)
            .put("secure", km.isDeviceSecure)
            .put("frames", screen.frames)
            .put("repeats", screen.repeats)
    }

    /** 有人連進來看畫面時點亮螢幕；設定成滑動解鎖的話順便解開。 */
    @Suppress("DEPRECATION")
    private fun wakeScreen(on: Boolean) {
        if (!on) {
            screenLock?.takeIf { it.isHeld }?.release()
            screenLock = null
            return
        }
        if (!screen.active || screenLock?.isHeld == true) return
        screenLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "ghostroom:screen")
            .apply { acquire(4 * 60 * 60_000L) }
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val dark = !getSystemService(PowerManager::class.java).isInteractive
        if ((locked || dark) && Settings.canDrawOverlays(this)) {
            runCatching { startActivity(Intent(this, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
        }
    }

    private fun connect() {
        if (stopped) return
        ready = false
        Status.update { server = "🟡 連線中" }
        val url = Pairing.server(this).replaceFirst("https://", "wss://") + "/api/ws?room=" + keys.room
        ws = http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private fun reconnectNow() {
        retryTimer?.cancel(false)
        cancelTimers()
        retry = 0
        ws?.cancel()
        ws = null
        ready = false
        connect()
    }

    private fun scheduleRetry(delayMs: Long) {
        retryTimer?.cancel(false)
        retryTimer = exec.schedule({ connect() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun cancelTimers() {
        pingTimer?.cancel(false)
        iceTimer?.cancel(false)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(JSONObject().put("t", "hello").put("role", "device").put("auth", keys.auth).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = post {
            if (webSocket === ws) {
                lastRx = System.currentTimeMillis()
                receive(text)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post {
            if (webSocket === ws) dropped(code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post {
            if (webSocket === ws) dropped(0)
        }
    }

    private fun dropped(code: Int) {
        ws = null
        ready = false
        cancelTimers()
        Status.update { iphone = "⚪ 不在線" }
        when (code) {
            4001 -> {
                stopped = true
                Status.update { server = "🔴 停止"; message = "另一台 Android 用同一組配對上線了，這台已停止" }
                return
            }
            4029 -> {
                Status.update { server = "🔴 鎖住"; message = "嘗試太多次，10 分鐘後自動再試" }
                return scheduleRetry(10 * 60_000L)
            }
        }
        Status.update { server = "🔴 斷線，重試中" }
        scheduleRetry(minOf(MAX_BACKOFF_MS, 500L shl minOf(retry++, 5)) + Random.nextLong(500))
    }

    private fun receive(text: String) {
        if (text == "pong") return
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("t")) {
            "ready" -> {
                ready = true
                retry = 0
                ice = parseIce(msg.optJSONArray("ice"))
                val turn = msg.optBoolean("turn")
                cancelTimers()
                pingTimer = exec.scheduleWithFixedDelay({ heartbeat() }, PING_S, PING_S, TimeUnit.SECONDS)
                iceTimer = exec.scheduleWithFixedDelay({ ws?.send("{\"t\":\"ice\"}") }, ICE_REFRESH_H, ICE_REFRESH_H, TimeUnit.HOURS)
                Status.update {
                    server = "🟢 已連線"
                    this.turn = if (turn) "🟢 可用" else "⚠️ 未設定"
                    iphone = if (msg.optBoolean("peer")) "🟢 在線" else "⚪ 不在線"
                    message = ""
                }
            }
            "ice" -> ice = parseIce(msg.optJSONArray("ice"))
            "peer" -> Status.update { iphone = if (msg.optBoolean("online")) "🟢 在線" else "⚪ 不在線" }
            "relay" -> {
                val inner = try {
                    keys.open("viewer", msg.getString("box"))
                } catch (e: IllegalStateException) {
                    Status.update { message = e.message ?: "" }
                    return
                } catch (e: Exception) {
                    Status.update { message = "收到無法解開的訊息（可能被竄改），已忽略" }
                    return
                }
                onRelay(inner.getString("k"), inner.optJSONObject("d") ?: JSONObject())
            }
        }
    }

    /** 伺服器那頭其實已經斷了但沒通知時，靠這裡發現。 */
    private fun heartbeat() {
        if (System.currentTimeMillis() - lastRx > SILENT_MS) {
            Status.update { server = "🟡 連線沒回應，重連中" }
            reconnectNow()
            return
        }
        ws?.send("ping")
    }

    private fun onRelay(kind: String, d: JSONObject) {
        val sid = d.optString("sid")
        when (kind) {
            "challenge" -> sendRelay("challenge", JSONObject().put("nonce", gate.challenge()))
            "enroll" -> enroll(d)
            "offer" -> offer(sid, d)
            "ice" -> if (session?.sid == sid) session?.addIce(d.getJSONObject("c"))
            "bye" -> if (session?.sid == sid) session?.end("iPhone 結束了連線")
        }
    }

    private fun enroll(d: JSONObject) {
        val reply = JSONObject()
        if (!Pairing.enrollOpen(this)) {
            sendRelay("enrolled", reply.put("ok", false).put("reason", "Android 沒有在顯示配對 QR：請在 Android 按「📷 顯示配對 QR」，2 分鐘內再按綁定"))
            return
        }
        val viewer = runCatching {
            Viewer(d.getString("credId"), Pairing.b64uDecode(d.getString("spki"))).also { WebAuthn.parseKey(it.spki) }
        }.getOrNull()
        if (viewer == null || viewer.credId.isEmpty()) {
            sendRelay("enrolled", reply.put("ok", false).put("reason", "Face ID 金鑰格式不對，請再試一次"))
            return
        }
        Pairing.setViewer(this, viewer)
        gate.reset()
        sendRelay("enrolled", reply.put("ok", true))
        Status.update { message = "已綁定 iPhone（Face ID）" }
    }

    private fun offer(sid: String, d: JSONObject) {
        val server = Uri.parse(Pairing.server(this))
        val origin = "${server.scheme}://${server.encodedAuthority}"
        val decision = gate.check(d.optJSONObject("auth"), Pairing.viewer(this), server.host ?: "", origin)
        if (!decision.ok) {
            sendRelay("denied", JSONObject().put("sid", sid).put("reason", decision.reason))
            Status.update { message = "擋下一次連線：${decision.reason}" }
            return
        }
        session?.end("新的連線取代了舊連線")
        Status.update { session = "🟡 連線中" }
        acquireWake()
        session = PeerSession(this, factory, sid, ice, exec, ::sendRelay, ::onSessionEnded, ::onLive, gate::touch, screen.track, ::screenInfo, ::onControl, ::onQuality).also {
            it.acceptOffer(d.getString("sdp"), decision.ticket)
        }
    }

    private fun onControl(msg: JSONObject) {
        ControlService.instance?.handle(msg)
    }

    private fun onQuality(id: String) {
        val p = ScreenShare.profileOf(id)
        screen.applyProfile(p)
        session?.setProfile(p)
        Status.update { message = if (p.id == "clear") "畫質：清晰" else "畫質：流暢" }
    }

    private fun onLive(live: Boolean) {
        this.live = live
        if (live) Status.update { iphone = "🟢 在線" }
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, notification(live))
        wakeScreen(live)
    }

    private fun onSessionEnded(ended: PeerSession, reason: String) {
        if (session !== ended) return
        session = null
        releaseWake()
        sendRelay("bye", JSONObject().put("sid", ended.sid).put("reason", reason))
        Status.update { session = "—"; message = reason }
    }

    private fun sendRelay(kind: String, data: JSONObject) {
        if (!ready) return
        ws?.send(JSONObject().put("t", "relay").put("box", keys.seal("device", kind, data)).toString())
    }

    private fun parseIce(arr: JSONArray?): List<PeerConnection.IceServer> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            val urls = s.optJSONArray("urls")?.let { u -> (0 until u.length()).map { u.getString(it) } } ?: listOf(s.getString("urls"))
            PeerConnection.IceServer.builder(urls)
                .setUsername(s.optString("username"))
                .setPassword(s.optString("credential"))
                .createIceServer()
        }
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ghostroom:session")
            .apply { acquire(4 * 60 * 60_000L) }
    }

    private fun releaseWake() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val CHANNEL = "standby"
        private const val NOTIFY_ID = 1
        private const val PING_S = 20L
        private const val SILENT_MS = 50_000L
        private const val ICE_REFRESH_H = 2L
        private const val MAX_BACKOFF_MS = 15_000L
        const val ACTION_STOP = "tw.yang.ghostroom.STOP"
        const val ACTION_PROJECTION = "tw.yang.ghostroom.PROJECTION"
        const val EXTRA_DATA = "data"

        fun start(ctx: Context) {
            Pairing.setStandby(ctx, true)
            ctx.startForegroundService(Intent(ctx, SignalService::class.java))
        }

        fun stop(ctx: Context) {
            Pairing.setStandby(ctx, false)
            ctx.stopService(Intent(ctx, SignalService::class.java))
        }
    }
}
