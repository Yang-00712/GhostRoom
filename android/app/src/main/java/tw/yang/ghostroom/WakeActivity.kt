package tw.yang.ghostroom

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** 看不見的畫面：點亮螢幕並解開「滑動解鎖」，然後馬上關掉自己。指紋或密碼鎖解不開。 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = finish()
            override fun onDismissCancelled() = finish()
            override fun onDismissError() = finish()
        })
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 3000)
    }
}
