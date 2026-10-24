package tw.yang.ghostroom

import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

data class Viewer(val credId: String, val spki: ByteArray)

object WebAuthn {
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun parseKey(spki: ByteArray) = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

    /** 通過回傳 null，否則回傳原因。 */
    fun verify(
        spki: ByteArray,
        rpId: String,
        origin: String,
        challenge: String,
        authData: ByteArray,
        clientData: ByteArray,
        sig: ByteArray,
    ): String? {
        val cd = runCatching { JSONObject(String(clientData, Charsets.UTF_8)) }.getOrNull() ?: return "驗證資料格式錯誤"
        if (cd.optString("type") != "webauthn.get") return "驗證類型不對"
        if (cd.optString("challenge") != challenge) return "驗證碼不符"
        if (cd.optString("origin") != origin) return "網址不符"
        if (authData.size < 37) return "驗證資料太短"
        if (!MessageDigest.isEqual(authData.copyOfRange(0, 32), sha256(rpId.toByteArray(Charsets.UTF_8)))) return "網域不符"
        val flags = authData[32].toInt()
        if ((flags and 0x01) == 0) return "沒有按下確認"
        if ((flags and 0x04) == 0) return "沒有通過 Face ID"
        val ok = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(parseKey(spki))
                update(authData)
                update(sha256(clientData))
                verify(sig)
            }
        }.getOrDefault(false)
        return if (ok) null else "Face ID 簽章不對"
    }
}

class Decision(val ok: Boolean, val reason: String = "", val ticket: String? = null)

/** 決定一次連線能不能進來：Face ID 簽章或短期連線憑證，錯太多次就暫停。 */
class Gatekeeper(
    private val clock: () -> Long = System::currentTimeMillis,
    private val newNonce: (() -> String)? = null,
) {
    private val random = SecureRandom()
    private val nonces = LinkedHashMap<String, Long>()
    private val fails = ArrayDeque<Long>()
    private var ticketHash: ByteArray? = null
    private var ticketExpiry = 0L

    @Synchronized
    fun challenge(): String {
        val now = clock()
        nonces.entries.removeAll { it.value < now }
        while (nonces.size >= MAX_NONCES) nonces.remove(nonces.keys.first())
        val nonce = newNonce?.invoke() ?: Pairing.b64uEncode(ByteArray(32).also { random.nextBytes(it) })
        nonces[nonce] = now + NONCE_TTL_MS
        return nonce
    }

    /** 連線中持續有動靜就延長憑證。 */
    @Synchronized
    fun touch() {
        if (ticketHash != null) ticketExpiry = clock() + TICKET_IDLE_MS
    }

    @Synchronized
    fun reset() {
        nonces.clear()
        ticketHash = null
        ticketExpiry = 0
    }

    @Synchronized
    fun check(auth: JSONObject?, viewer: Viewer?, rpId: String, origin: String): Decision {
        val now = clock()
        while (fails.isNotEmpty() && now - fails.first() > FAIL_WINDOW_MS) fails.removeFirst()
        if (fails.size >= MAX_FAILS) return Decision(false, "驗證失敗太多次，Android 暫停接受連線 10 分鐘")
        if (viewer == null) return Decision(false, "這支 Android 還沒綁定 iPhone，請重新配對並綁定 Face ID")

        when (auth?.optString("type")) {
            "ticket" -> {
                val hash = ticketHash
                val given = WebAuthn.sha256(auth.optString("ticket").toByteArray(Charsets.UTF_8))
                if (hash != null && now < ticketExpiry && MessageDigest.isEqual(hash, given)) return grant(now)
                return fail(now, "連線憑證過期了")
            }
            "webauthn" -> {
                val nonce = auth.optString("nonce")
                val expiry = nonces.remove(nonce)
                if (expiry == null || expiry < now) return fail(now, "驗證碼過期了")
                if (auth.optString("credId") != viewer.credId) return fail(now, "不是綁定的那支 iPhone")
                val reason = runCatching {
                    WebAuthn.verify(
                        viewer.spki, rpId, origin, nonce,
                        Pairing.b64uDecode(auth.getString("authData")),
                        Pairing.b64uDecode(auth.getString("clientData")),
                        Pairing.b64uDecode(auth.getString("sig")),
                    )
                }.getOrElse { "驗證資料格式錯誤" }
                if (reason != null) return fail(now, reason)
                return grant(now)
            }
            else -> return fail(now, "缺少 Face ID 驗證")
        }
    }

    private fun grant(now: Long): Decision {
        val ticket = Pairing.b64uEncode(ByteArray(32).also { random.nextBytes(it) })
        ticketHash = WebAuthn.sha256(ticket.toByteArray(Charsets.UTF_8))
        ticketExpiry = now + TICKET_IDLE_MS
        return Decision(true, ticket = ticket)
    }

    private fun fail(now: Long, reason: String): Decision {
        fails.addLast(now)
        return Decision(false, reason)
    }

    companion object {
        const val NONCE_TTL_MS = 120_000L
        const val TICKET_IDLE_MS = 30 * 60_000L
        const val FAIL_WINDOW_MS = 10 * 60_000L
        const val MAX_FAILS = 5
        private const val MAX_NONCES = 5
    }
}
