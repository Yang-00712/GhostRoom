package tw.yang.ghostroom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Pairing.standby(ctx)) SignalService.start(ctx)
    }
}
