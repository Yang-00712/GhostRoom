package tw.yang.ghostroom

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object Status {
    @Volatile var server = "⚪ 停止"
    @Volatile var iphone = "⚪ 不在線"
    @Volatile var session = "—"
    @Volatile var turn = "—"
    @Volatile var screen = "⚠️ 未允許"
    @Volatile var control = "⚠️ 未開啟"
    @Volatile var message = ""

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun update(block: Status.() -> Unit) {
        block()
        main.post { listeners.forEach { it() } }
    }

    fun listen(listener: () -> Unit) {
        listeners += listener
    }

    fun unlisten(listener: () -> Unit) {
        listeners -= listener
    }
}
