package tw.yang.ghostroom

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.view.Display
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
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

    val active: Boolean get() = track != null

    fun start(permission: Intent) {
        stop()
        val cap = ScreenCapturerAndroid(permission, object : MediaProjection.Callback() {
            override fun onStop() = onStopped()
        })
        val src = factory.createVideoSource(true)
        val tex = SurfaceTextureHelper.create("screen", egl.eglBaseContext)
        cap.initialize(tex, ctx, src.capturerObserver)
        val (w, h) = captureSize()
        cap.startCapture(w, h, FPS)
        capturer = cap
        source = src
        helper = tex
        track = factory.createVideoTrack("screen", src)
    }

    fun stop() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        track?.dispose()
        source?.dispose()
        helper?.dispose()
        capturer = null
        track = null
        source = null
        helper = null
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

    companion object {
        const val FPS = 24
        private const val MAX_WIDTH = 720
    }
}
