package tw.yang.ghostroom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class SignalService : Service() {
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private lateinit var keys: Keys
    private lateinit var factory: PeerConnectionFactory
    private var ws: WebSocket? = null
    private var ready = false
    private var stopped = false
    private var retry = 0
    private var retryTimer: ScheduledFuture<*>? = null
    private var pingTimer: ScheduledFuture<*>? = null
    private var ice: List<PeerConnection.IceServer> = emptyList()
    private var session: PeerSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            post { if (ws == null && !stopped) reconnectNow() }
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
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
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
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        exec.execute {
            stopped = true
            session?.end("Android 停止待命")
            retryTimer?.cancel(false)
            pingTimer?.cancel(false)
            ws?.close(1000, "bye")
            ws = null
            factory.dispose()
            Status.update { server = "⚪ 停止"; iphone = "⚪ 不在線"; session = "—" }
        }
        exec.shutdown()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "待命", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, SignalService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("GhostRoom 待命中")
            .setContentText("iPhone 連線時這裡會顯示")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "⏹ 停止待命", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFY_ID, notification)
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
        retry = 0
        ws?.cancel()
        ws = null
        connect()
    }

    private fun scheduleRetry(delayMs: Long) {
        retryTimer?.cancel(false)
        retryTimer = exec.schedule({ connect() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(JSONObject().put("t", "hello").put("role", "device").put("auth", keys.auth).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = post {
            if (webSocket === ws) receive(text)
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
        pingTimer?.cancel(false)
        Status.update { iphone = "⚪ 不在線" }
        when (code) {
            4001 -> {
                stopped = true
                Status.update { server = "🔴 停止"; message = "另一台 Android 用同一組配對上線了，這台已停止" }
                return
            }
            4029 -> {
                Status.update { server = "🔴 鎖住"; message = "密碼錯太多次，10 分鐘後自動再試" }
                return scheduleRetry(10 * 60_000L)
            }
        }
        Status.update { server = "🔴 斷線，重試中" }
        scheduleRetry(minOf(30_000L, 1000L shl minOf(retry++, 5)))
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
                pingTimer?.cancel(false)
                pingTimer = exec.scheduleWithFixedDelay({ ws?.send("ping") }, 25, 25, TimeUnit.SECONDS)
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

    private fun onRelay(kind: String, d: JSONObject) {
        val sid = d.optString("sid")
        when (kind) {
            "offer" -> {
                session?.end("新的連線取代了舊連線")
                Status.update { session = "🟡 連線中" }
                acquireWake()
                session = PeerSession(this, factory, sid, ice, exec, ::sendRelay, ::onSessionEnded).also {
                    it.acceptOffer(d.getString("sdp"))
                }
            }
            "ice" -> if (session?.sid == sid) session?.addIce(d.getJSONObject("c"))
            "bye" -> if (session?.sid == sid) session?.end("iPhone 結束了連線")
        }
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
        const val ACTION_STOP = "tw.yang.ghostroom.STOP"

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
