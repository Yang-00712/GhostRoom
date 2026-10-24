package tw.yang.ghostroom

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.view.Display
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

/** 一組畫質設定。寬是縮圖後的短邊上限，fps 是每秒格數上限，kbps 是位元率。 */
data class Profile(val id: String, val width: Int, val fps: Int, val kbps: Int)

/** 錄整個螢幕成一條影像。授權一次後一直開著，每次 iPhone 連線共用同一條。 */
class ScreenShare(
    private val ctx: Context,
    private val factory: PeerConnectionFactory,
    private val egl: EglBase,
    private val onStopped: () -> Unit,
) {
    var track: VideoTrack? = null
        private set
    var profile: Profile = SMOOTH
        private set
    private var capturer: ScreenCapturerAndroid? = null
    private var source: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null
    private var tap: FrameTap? = null

    val active: Boolean get() = track != null
    val frames: Long get() = tap?.captured ?: 0
    val repeats: Long get() = tap?.repeated ?: 0

    fun start(permission: Intent) {
        stop()
        val cap = ScreenCapturerAndroid(permission, object : MediaProjection.Callback() {
            override fun onStop() = onStopped()
        })
        val src = factory.createVideoSource(true)
        val tex = SurfaceTextureHelper.create("screen", egl.eglBaseContext)
        val frameTap = FrameTap(src.capturerObserver, profile.fps)
        cap.initialize(tex, ctx, frameTap)
        val (w, h) = captureSize(profile.width)
        cap.startCapture(w, h, profile.fps)
        capturer = cap
        source = src
        helper = tex
        tap = frameTap
        track = factory.createVideoTrack("screen", src)
    }

    /** 連線中切換畫質：不用重新授權，直接改錄影的解析度和格數。 */
    fun applyProfile(p: Profile) {
        profile = p
        tap?.setFps(p.fps)
        val (w, h) = captureSize(p.width)
        runCatching { capturer?.changeCaptureFormat(w, h, p.fps) }
    }

    fun stop() {
        runCatching { capturer?.stopCapture() }
        tap?.close()
        capturer?.dispose()
        track?.dispose()
        source?.dispose()
        helper?.dispose()
        capturer = null
        track = null
        source = null
        helper = null
        tap = null
    }

    @Suppress("DEPRECATION")
    private fun captureSize(maxWidth: Int): Pair<Int, Int> {
        val size = Point()
        ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(size)
        val w = minOf(size.x, size.y)
        val h = maxOf(size.x, size.y)
        val scale = minOf(1.0, maxWidth.toDouble() / w)
        val even = { v: Double -> (v.roundToInt() / 2) * 2 }
        return even(w * scale) to even(h * scale)
    }

    /**
     * 兩件事：
     * 1. 入門機編碼器壓不動太多格，太快來的畫面直接丟掉，把格數鎖在上限 —— 這是「卡」的主因。
     * 2. Android 只在畫面有變化時才給新的一格，靜止時 iPhone 收不到連續影像就不會播，所以留一份重送。
     * 全部在錄影那條執行緒上跑。丟掉的畫面不轉送即可，原始畫面由錄影端自行回收，不會漏。
     */
    private class FrameTap(private val target: CapturerObserver, fps: Int) : CapturerObserver {
        @Volatile var captured = 0L
        @Volatile var repeated = 0L
        @Volatile private var minIntervalMs = interval(fps)
        private var cached: VideoFrame.I420Buffer? = null
        private var rotation = 0
        private var lastFrameAt = 0L
        private var lastForwardAt = 0L
        private var lastCacheAt = 0L
        private var running = false
        private var closed = false
        private var helperHandler: android.os.Handler? = null

        fun setFps(fps: Int) {
            minIntervalMs = interval(fps)
        }

        override fun onCapturerStarted(success: Boolean) {
            running = success
            target.onCapturerStarted(success)
        }

        override fun onCapturerStopped() {
            running = false
            target.onCapturerStopped()
        }

        override fun onFrameCaptured(frame: VideoFrame) {
            captured++
            val now = SystemClock.elapsedRealtime()
            lastFrameAt = now
            rotation = frame.rotation
            if (helperHandler == null) startRepeater()
            if (now - lastCacheAt > CACHE_EVERY_MS) {
                lastCacheAt = now
                frame.buffer.toI420()?.let { copy ->
                    cached?.release()
                    cached = copy
                }
            }
            // 格數上限：太快來的就丟掉，不轉送。錄影端會自己回收這一格。
            if (now - lastForwardAt >= minIntervalMs) {
                lastForwardAt = now
                target.onFrameCaptured(frame)
            }
        }

        private fun startRepeater() {
            val h = android.os.Handler(android.os.Looper.myLooper() ?: return)
            helperHandler = h
            h.post(object : Runnable {
                override fun run() {
                    if (closed) return
                    val buf = cached
                    if (running && buf != null && SystemClock.elapsedRealtime() - lastFrameAt > REPEAT_AFTER_MS) {
                        buf.retain()
                        val frame = VideoFrame(buf, rotation, SystemClock.elapsedRealtimeNanos())
                        target.onFrameCaptured(frame)
                        frame.release()
                        repeated++
                        lastForwardAt = SystemClock.elapsedRealtime()
                    }
                    h.postDelayed(this, REPEAT_EVERY_MS)
                }
            })
        }

        fun close() {
            val h = helperHandler ?: run { closed = true; cached?.release(); cached = null; return }
            h.post {
                closed = true
                cached?.release()
                cached = null
            }
        }

        companion object {
            private const val REPEAT_AFTER_MS = 400L
            private const val REPEAT_EVERY_MS = 300L
            private const val CACHE_EVERY_MS = 500L
            private fun interval(fps: Int) = (1000L / fps.coerceIn(5, 60)) - 4
        }
    }

    companion object {
        val SMOOTH = Profile("smooth", 540, 20, 1800)
        val CLEAR = Profile("clear", 720, 30, 3500)
        fun profileOf(id: String?): Profile = if (id == "clear") CLEAR else SMOOTH
    }
}
