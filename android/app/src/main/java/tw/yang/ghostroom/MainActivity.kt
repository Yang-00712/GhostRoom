package tw.yang.ghostroom

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var serverText: TextView
    private lateinit var iphoneText: TextView
    private lateinit var sessionText: TextView
    private lateinit var turnText: TextView
    private lateinit var boundText: TextView
    private lateinit var messageText: TextView
    private lateinit var standbyBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var qrBtn: Button
    private lateinit var qrImage: ImageView
    private val hideQr = Runnable { showQr(false) }
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = CANVAS
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(build())
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
    }

    override fun onResume() {
        super.onResume()
        Status.listen(refresh)
        render()
    }

    override fun onPause() {
        Status.unlisten(refresh)
        showQr(false)
        super.onPause()
    }

    private fun render() {
        serverText.text = Status.server
        iphoneText.text = Status.iphone
        sessionText.text = Status.session
        turnText.text = Status.turn
        boundText.text = if (Pairing.viewer(this) != null) "🔐 已綁定 Face ID" else "⚠️ 還沒綁定"
        if (qrImage.visibility == View.VISIBLE && !Pairing.enrollOpen(this)) showQr(false)
        messageText.text = Status.message
        standbyBtn.text = if (Pairing.standby(this)) "⏹ 停止待命" else "▶ 開始待命"
        val pm = getSystemService(PowerManager::class.java)
        batteryBtn.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    private fun build(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        col += TextView(this).apply {
            text = "GhostRoom"
            textSize = 26f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }

        col += card().apply {
            this += title("📡 待命狀態")
            serverText = row(this, "伺服器")
            iphoneText = row(this, "iPhone")
            sessionText = row(this, "連線")
            turnText = row(this, "中繼")
            messageText = TextView(this@MainActivity).apply {
                setTextColor(WARNING)
                textSize = 13f
                setPadding(0, dp(8), 0, 0)
            }
            this += messageText
            standbyBtn = button("▶ 開始待命", primary = true) {
                if (Pairing.standby(this@MainActivity)) SignalService.stop(this@MainActivity) else SignalService.start(this@MainActivity)
                main.postDelayed({ render() }, 200)
            }
            this += standbyBtn
            batteryBtn = button("🔋 允許背景執行（不然會被系統關掉）") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
            this += batteryBtn
        }

        col += card().apply {
            this += title("🔑 配對 iPhone")
            boundText = row(this, "綁定")
            this += hint("iPhone 打開 GhostRoom，按「📷 掃描配對 QR」對準這裡，再按「🔐 用 Face ID 綁定」。只有 QR 顯示的 2 分鐘內能綁定新的 iPhone，QR 不要給別人看。")
            qrBtn = button("📷 顯示配對 QR") { showQr(qrImage.visibility != View.VISIBLE) }
            this += qrBtn
            qrImage = ImageView(this@MainActivity).apply {
                visibility = View.GONE
                setPadding(dp(8), dp(12), dp(8), dp(4))
                adjustViewBounds = true
            }
            this += qrImage
            this += button("🚫 解除 iPhone 綁定") { confirmUnbind() }
            this += button("🔄 重新產生配對（舊 iPhone 會失效）") { confirmRegenerate() }
        }

        col += card().apply {
            this += title("⚙ 伺服器網址")
            val input = EditText(this@MainActivity).apply {
                setText(Pairing.server(this@MainActivity))
                setTextColor(TEXT)
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                background = shape(INPUT, OUTLINE, 8)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            this += input
            this += button("💾 儲存網址") {
                val url = input.text.toString().trim()
                if (!url.startsWith("https://")) {
                    Status.update { message = "網址要用 https:// 開頭" }
                    return@button
                }
                Pairing.setServer(this@MainActivity, url)
                restartIfRunning()
                Status.update { message = "已儲存。iPhone 需要重新掃 QR" }
            }
        }

        return ScrollView(this).apply {
            fitsSystemWindows = true
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFFFFFF.toInt(), 0xFFEDFEFD.toInt(), 0xFFD9F8F7.toInt()))
            addView(col)
        }
    }

    private fun showQr(show: Boolean) {
        main.removeCallbacks(hideQr)
        if (show) {
            qrImage.setImageBitmap(qrBitmap(Pairing.pairCode(this)))
            qrImage.visibility = View.VISIBLE
            qrBtn.text = "🙈 收起 QR"
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            Pairing.openEnroll(this, QR_MS)
            main.postDelayed(hideQr, QR_MS)
        } else {
            qrImage.setImageDrawable(null)
            qrImage.visibility = View.GONE
            qrBtn.text = "📷 顯示配對 QR"
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            Pairing.closeEnroll(this)
        }
    }

    private fun confirmRegenerate() {
        AlertDialog.Builder(this)
            .setTitle("重新產生配對？")
            .setMessage("舊的 iPhone 會再也連不上，要重新掃新的 QR。")
            .setPositiveButton("重新產生") { _, _ ->
                Pairing.regenerate(this)
                restartIfRunning()
                showQr(true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmUnbind() {
        AlertDialog.Builder(this)
            .setTitle("解除 iPhone 綁定？")
            .setMessage("iPhone 會立刻斷線，要重新掃 QR、綁 Face ID 才能再連。")
            .setPositiveButton("解除") { _, _ ->
                Pairing.clearViewer(this)
                restartIfRunning()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun restartIfRunning() {
        if (!Pairing.standby(this)) return
        SignalService.stop(this)
        main.postDelayed({ SignalService.start(this) }, 500)
    }

    private fun qrBitmap(text: String): Bitmap {
        val size = 640
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) TEXT else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(SURFACE, OUTLINE, 10)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(ACCENT_INK)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(8))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(MUTED)
        setPadding(0, 0, 0, dp(4))
    }

    private fun row(parent: LinearLayout, label: String): TextView {
        val line = LinearLayout(this).apply { setPadding(0, dp(3), 0, dp(3)) }
        line += TextView(this).apply {
            text = label
            setTextColor(MUTED)
            textSize = 15f
            minWidth = dp(76)
        }
        val value = TextView(this).apply {
            setTextColor(TEXT)
            textSize = 15f
        }
        line += value
        parent += line
        return value
    }

    private fun button(text: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(TEXT)
        if (primary) typeface = Typeface.DEFAULT_BOLD
        background = if (primary) {
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF89E0D0.toInt(), 0xFF48CABC.toInt(), 0xFF23AF9F.toInt())).apply { cornerRadius = dp(8).toFloat() }
        } else {
            shape(SURFACE, OUTLINE, 8)
        }
        stateListAnimator = null
        minHeight = dp(46)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun shape(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(1.5f), stroke)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private operator fun LinearLayout.plusAssign(view: View) = addView(view)

    companion object {
        private const val QR_MS = 120_000L
        private val CANVAS = 0xFFECFEFD.toInt()
        private val SURFACE = 0xFFFBFFFF.toInt()
        private val INPUT = 0xFFFCFFFF.toInt()
        private val OUTLINE = 0xFF9ADFE2.toInt()
        private val TEXT = 0xFF073554.toInt()
        private val MUTED = 0xFF426B7C.toInt()
        private val ACCENT_INK = 0xFF075E78.toInt()
        private val WARNING = 0xFF89601B.toInt()
    }
}
