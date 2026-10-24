package tw.yang.ghostroom

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 iPhone 傳來的點擊、滑動打在這支 Android 上。座標是 0~1 的比例，乘上真實螢幕大小。
 * 這是所有遠端控制 App 共用的標準做法；GhostRoom 連線時一定會顯示常駐通知。
 */
class ControlService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        Status.update { control = "🟢 已開啟" }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clear()
        super.onDestroy()
    }

    private fun clear() {
        if (instance === this) instance = null
        Status.update { control = "⚠️ 未開啟" }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun handle(msg: JSONObject) {
        when (msg.optString("t")) {
            "gesture" -> gesture(msg.optJSONArray("pts") ?: return)
            "key" -> main.post {
                performGlobalAction(
                    when (msg.optString("k")) {
                        "home" -> GLOBAL_ACTION_HOME
                        "recents" -> GLOBAL_ACTION_RECENTS
                        else -> GLOBAL_ACTION_BACK
                    }
                )
            }
        }
    }

    private fun realSize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun gesture(pts: JSONArray) {
        if (pts.length() == 0) return
        val (w, h) = realSize()
        val path = Path()
        var last = 0L
        for (i in 0 until pts.length()) {
            val p = pts.getJSONArray(i)
            val x = (p.getDouble(0).coerceIn(0.0, 1.0) * w).toFloat()
            val y = (p.getDouble(1).coerceIn(0.0, 1.0) * h).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            last = p.optLong(2, last)
        }
        // 只有一個點（純點擊）時給 path 一點點長度，不然手勢無效
        if (pts.length() == 1) {
            val p = pts.getJSONArray(0)
            path.lineTo((p.getDouble(0) * w).toFloat() + 1f, (p.getDouble(1) * h).toFloat() + 1f)
        }
        val duration = last.coerceIn(TAP_MS, MAX_MS)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        main.post { dispatchGesture(gesture, null, null) }
    }

    companion object {
        @Volatile
        var instance: ControlService? = null
            private set

        val enabled: Boolean get() = instance != null

        private const val TAP_MS = 40L
        private const val MAX_MS = 12_000L
    }
}
