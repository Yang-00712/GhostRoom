package tw.yang.ghostroom

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteropTest {
    private val keys = Keys(Pairing.b64uDecode(Vectors.SECRET))
    private val spki = Pairing.b64uDecode(Vectors.SPKI)
    private val authData = Pairing.b64uDecode(Vectors.AUTH_DATA)
    private val clientData = Pairing.b64uDecode(Vectors.CLIENT_DATA)
    private val sig = Pairing.b64uDecode(Vectors.SIG)

    @Test
    fun derivesSameRoomAndAuthAsIphone() {
        assertEquals(Vectors.ROOM, keys.room)
        assertEquals(Vectors.AUTH, keys.auth)
    }

    @Test
    fun opensBoxSealedByIphone() {
        val msg = keys.open("viewer", Vectors.BOX, Vectors.BOX_TS)
        assertEquals("offer", msg.getString("k"))
        val d = msg.getJSONObject("d")
        assertEquals("vector", d.getString("sid"))
        assertEquals(42, d.getInt("n"))
        assertEquals("中文", d.getString("s"))
    }

    @Test
    fun rejectsWrongRoleReplayAndOldBox() {
        assertTrue(runCatching { Keys(Pairing.b64uDecode(Vectors.SECRET)).open("device", Vectors.BOX, Vectors.BOX_TS) }.isFailure)
        val fresh = Keys(Pairing.b64uDecode(Vectors.SECRET))
        fresh.open("viewer", Vectors.BOX, Vectors.BOX_TS)
        assertTrue(runCatching { fresh.open("viewer", Vectors.BOX, Vectors.BOX_TS) }.isFailure)
        assertTrue(runCatching { Keys(Pairing.b64uDecode(Vectors.SECRET)).open("viewer", Vectors.BOX, Vectors.BOX_TS + 10 * 60_000) }.isFailure)
    }

    @Test
    fun sealThenOpenRoundTrip() {
        val box = keys.seal("device", "answer", JSONObject().put("sid", "x"))
        assertEquals("x", Keys(Pairing.b64uDecode(Vectors.SECRET)).open("device", box).getJSONObject("d").getString("sid"))
    }

    @Test
    fun verifiesIphoneFaceIdSignature() {
        assertNull(WebAuthn.verify(spki, Vectors.RP_ID, Vectors.ORIGIN, Vectors.NONCE, authData, clientData, sig))
    }

    @Test
    fun rejectsTamperedFaceId() {
        val badSig = sig.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNotNull(WebAuthn.verify(spki, Vectors.RP_ID, Vectors.ORIGIN, Vectors.NONCE, authData, clientData, badSig))
        assertNotNull(WebAuthn.verify(spki, Vectors.RP_ID, "https://evil.pages.dev", Vectors.NONCE, authData, clientData, sig))
        assertNotNull(WebAuthn.verify(spki, "evil.pages.dev", Vectors.ORIGIN, Vectors.NONCE, authData, clientData, sig))
        assertNotNull(WebAuthn.verify(spki, Vectors.RP_ID, Vectors.ORIGIN, "other-nonce", authData, clientData, sig))
        val noUv = authData.copyOf().also { it[32] = (it[32].toInt() and 0x04.inv()).toByte() }
        assertNotNull(WebAuthn.verify(spki, Vectors.RP_ID, Vectors.ORIGIN, Vectors.NONCE, noUv, clientData, sig))
    }

    private fun faceIdAuth(nonce: String = Vectors.NONCE, credId: String = Vectors.CRED_ID) = JSONObject()
        .put("type", "webauthn").put("nonce", nonce).put("credId", credId)
        .put("authData", Vectors.AUTH_DATA).put("clientData", Vectors.CLIENT_DATA).put("sig", Vectors.SIG)

    @Test
    fun gatekeeperFaceIdThenTicket() {
        var now = 1_000_000L
        val gate = Gatekeeper({ now }, { Vectors.NONCE })
        val viewer = Viewer(Vectors.CRED_ID, spki)
        gate.challenge()
        val first = gate.check(faceIdAuth(), viewer, Vectors.RP_ID, Vectors.ORIGIN)
        assertTrue(first.reason, first.ok)
        assertNotNull(first.ticket)

        val reused = gate.check(faceIdAuth(), viewer, Vectors.RP_ID, Vectors.ORIGIN)
        assertFalse("驗證碼只能用一次", reused.ok)

        now += 10 * 60_000
        val byTicket = gate.check(JSONObject().put("type", "ticket").put("ticket", first.ticket), viewer, Vectors.RP_ID, Vectors.ORIGIN)
        assertTrue(byTicket.reason, byTicket.ok)
        assertFalse("舊憑證換新後失效", gate.check(JSONObject().put("type", "ticket").put("ticket", first.ticket), viewer, Vectors.RP_ID, Vectors.ORIGIN).ok)

        now += Gatekeeper.TICKET_IDLE_MS + 1
        assertFalse("閒置太久憑證過期", gate.check(JSONObject().put("type", "ticket").put("ticket", byTicket.ticket), viewer, Vectors.RP_ID, Vectors.ORIGIN).ok)
    }

    @Test
    fun gatekeeperTouchKeepsTicketAlive() {
        var now = 0L
        val gate = Gatekeeper({ now }, { Vectors.NONCE })
        val viewer = Viewer(Vectors.CRED_ID, spki)
        gate.challenge()
        val ticket = gate.check(faceIdAuth(), viewer, Vectors.RP_ID, Vectors.ORIGIN).ticket
        repeat(4) {
            now += Gatekeeper.TICKET_IDLE_MS - 1000
            gate.touch()
        }
        assertTrue(gate.check(JSONObject().put("type", "ticket").put("ticket", ticket), viewer, Vectors.RP_ID, Vectors.ORIGIN).ok)
    }

    @Test
    fun gatekeeperRejectsOthersAndLocksOut() {
        var now = 0L
        val gate = Gatekeeper({ now }, { Vectors.NONCE })
        val viewer = Viewer(Vectors.CRED_ID, spki)
        assertFalse(gate.check(faceIdAuth(), null, Vectors.RP_ID, Vectors.ORIGIN).ok)
        assertFalse("沒要過驗證碼", gate.check(faceIdAuth(), viewer, Vectors.RP_ID, Vectors.ORIGIN).ok)
        gate.challenge()
        assertFalse("別支 iPhone", gate.check(faceIdAuth(credId = "someone-else"), viewer, Vectors.RP_ID, Vectors.ORIGIN).ok)
        assertFalse(gate.check(JSONObject().put("type", "ticket").put("ticket", "guess"), viewer, Vectors.RP_ID, Vectors.ORIGIN).ok)
        assertFalse(gate.check(null, viewer, Vectors.RP_ID, Vectors.ORIGIN).ok)
        assertFalse(gate.check(null, viewer, Vectors.RP_ID, Vectors.ORIGIN).ok)
        gate.challenge()
        val locked = gate.check(faceIdAuth(), viewer, Vectors.RP_ID, Vectors.ORIGIN)
        assertFalse("錯 5 次後連正確的也擋", locked.ok)
        assertTrue(locked.reason.contains("暫停"))
        now += Gatekeeper.FAIL_WINDOW_MS + 1
        gate.challenge()
        assertTrue("10 分鐘後恢復", gate.check(faceIdAuth(), viewer, Vectors.RP_ID, Vectors.ORIGIN).ok)
    }
}
