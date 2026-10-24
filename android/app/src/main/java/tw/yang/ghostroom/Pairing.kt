package tw.yang.ghostroom

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object Pairing {
    private const val PREFS = "ghostroom"
    private const val KEY_SECRET = "secret"
    private const val KEY_SERVER = "server"
    private const val KEY_STANDBY = "standby"

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun secret(ctx: Context): ByteArray {
        prefs(ctx).getString(KEY_SECRET, null)?.let { return b64uDecode(it) }
        return regenerate(ctx)
    }

    fun regenerate(ctx: Context): ByteArray {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit().putString(KEY_SECRET, b64uEncode(bytes)).apply()
        return bytes
    }

    fun server(ctx: Context): String = prefs(ctx).getString(KEY_SERVER, null) ?: BuildConfig.DEFAULT_SERVER

    fun setServer(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_SERVER, url.trim().trimEnd('/')).apply()
    }

    fun standby(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_STANDBY, false)

    fun setStandby(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_STANDBY, on).apply()
    }

    fun pairCode(ctx: Context): String = "GR1|${b64uEncode(secret(ctx))}|${server(ctx)}"

    fun b64uEncode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun b64uDecode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}

class Keys(secret: ByteArray) {
    val room: String = hmac(secret, "ghostroom/room").joinToString("") { "%02x".format(it) }.substring(0, 32)
    val auth: String = Pairing.b64uEncode(hmac(secret, "ghostroom/auth"))
    private val aes = SecretKeySpec(hmac(secret, "ghostroom/enc"), "AES")
    private val random = SecureRandom()
    private val seen = LinkedHashMap<String, Long>()

    private fun hmac(secret: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(label.toByteArray(Charsets.UTF_8))
    }

    fun seal(fromRole: String, kind: String, data: JSONObject): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val nonce = Pairing.b64uEncode(ByteArray(9).also { random.nextBytes(it) })
        val body = JSONObject()
            .put("k", kind)
            .put("ts", System.currentTimeMillis())
            .put("n", nonce)
            .put("d", data)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, iv))
        cipher.updateAAD("$room|$fromRole".toByteArray(Charsets.UTF_8))
        return Pairing.b64uEncode(iv + cipher.doFinal(body))
    }

    /** 解不開、時間差太大或重複都會丟例外。 */
    @Synchronized
    fun open(fromRole: String, box: String): JSONObject {
        val raw = Pairing.b64uDecode(box)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, raw, 0, 12))
        cipher.updateAAD("$room|$fromRole".toByteArray(Charsets.UTF_8))
        val msg = JSONObject(String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8))
        val now = System.currentTimeMillis()
        if (abs(now - msg.getLong("ts")) > MAX_SKEW_MS) throw IllegalStateException("訊息時間差太大，請確認兩支手機的時間都是自動設定")
        val n = msg.getString("n")
        if (seen.containsKey(n)) throw IllegalStateException("重複的訊息")
        seen[n] = now
        seen.entries.removeAll { now - it.value > MAX_SKEW_MS * 2 }
        return msg
    }

    companion object {
        private const val MAX_SKEW_MS = 5 * 60_000L
    }
}
