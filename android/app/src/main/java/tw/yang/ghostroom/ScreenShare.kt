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

/** 錄整個螢幕成一條影像。授權一次後一直開著，每次 iPhone 連線共用同一條。 */
class ScreenShare(
    private val ctx: Context,
    private val factory: PeerConnectionFactory,
    private val egl: EglBase,
    private val onStopped: () -> Unit,
) {
    var track: VideoTrack? = null
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
        val frameTap = FrameTap(src.capturerObserver, tex)
        cap.initialize(tex, ctx, frameTap)
        val (w, h) = captureSize()
        cap.startCapture(w, h, FPS)
        capturer = cap
        source = src
        helper = tex
        tap = frameTap
        track = factory.createVideoTrack("screen", src)
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
    private fun captureSize(): Pair<Int, Int> {
        val size = Point()
        ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(size)
        val w = minOf(size.x, size.y)
        val h = maxOf(size.x, size.y)
        val scale = minOf(1.0, MAX_WIDTH.toDouble() / w)
        val even = { v: Double -> (v.roundToInt() / 2) * 2 }
        return even(w * scale) to even(h * scale)
    }

    /**
     * Android 只在畫面有變化時才給新的一格。畫面靜止時 iPhone 收不到連續影像就不會開始播，
     * 所以留一份最近的畫面，太久沒新畫面就重送。全部在錄影那條執行緒上跑。
     */
    private class FrameTap(private val target: CapturerObserver, private val helper: SurfaceTextureHelper) : CapturerObserver {
        @Volatile var captured = 0L
        @Volatile var repeated = 0L
        private var cached: VideoFrame.I420Buffer? = null
        private var rotation = 0
        private var lastFrameAt = 0L
        private var lastCacheAt = 0L
        private var running = false
        private var closed = false

        private val repeater = object : Runnable {
            override fun run() {
                if (closed) return
                val buf = cached
                if (running && buf != null && SystemClock.elapsedRealtime() - lastFrameAt > REPEAT_AFTER_MS) {
                    buf.retain()
                    val frame = VideoFrame(buf, rotation, SystemClock.elapsedRealtimeNanos())
                    target.onFrameCaptured(frame)
                    frame.release()
                    repeated++
                }
                helper.handler.postDelayed(this, REPEAT_EVERY_MS)
            }
        }

        init {
            helper.handler.post(repeater)
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
            if (now - lastCacheAt > CACHE_EVERY_MS) {
                lastCacheAt = now
                frame.buffer.toI420()?.let { copy ->
                    cached?.release()
                    cached = copy
                }
            }
            target.onFrameCaptured(frame)
        }

        fun close() {
            helper.handler.post {
                closed = true
                helper.handler.removeCallbacks(repeater)
                cached?.release()
                cached = null
            }
        }

        companion object {
            private const val REPEAT_AFTER_MS = 400L
            private const val REPEAT_EVERY_MS = 300L
            private const val CACHE_EVERY_MS = 500L
        }
    }

    companion object {
        const val FPS = 24
        private const val MAX_WIDTH = 720
    }
}
