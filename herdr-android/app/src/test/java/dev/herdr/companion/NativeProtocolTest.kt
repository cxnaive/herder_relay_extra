package dev.herdr.companion

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.*
import java.security.spec.*

class NativeProtocolTest {
    private fun vector() = JSONObject(javaClass.getResourceAsStream("/e2ee-v2.json")!!.bufferedReader().readText())
    @Test fun kotlinHandshakeAndFramesMatchGoAndBrowserVectors() {
        val v = vector(); val cv = v.getJSONObject("client")
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, RelayCrypto.decode(cv.getString("private_key"))), params))
        val pair = KeyPair(RelayCrypto.publicKey(RelayCrypto.decode(cv.getString("public_key"))), privateKey)
        for (binary in listOf(false, true)) {
            val auth = JSONObject().put("kind", "credential").put("id", "credential-test").put("version", 7).put("secret", RelayCrypto.encode(v.getString("relay_key").toByteArray()))
            val handshake = RelayHandshake(auth, pair, RelayCrypto.decode(cv.getString("nonce")))
            assertEquals(cv.getString("proof"), handshake.hello().getString("proof"))
            val cipher = handshake.complete(v.getJSONObject("server").getJSONObject("hello"), binary)
            val finish = v.getJSONObject("records").getJSONObject("client_finish")
            val wire = cipher.encrypt(finish.getString("plaintext"))
            if (binary) assertArrayEquals(RelayCrypto.decode(finish.getString("binary_frame")), wire)
            else assertEquals(finish.getString("ciphertext"), JSONObject(wire.toString(Charsets.UTF_8)).getString("ciphertext"))
            val incoming = v.getJSONObject("records").getJSONObject("s2c")
            val frame = if (binary) RelayCrypto.decode(incoming.getString("binary_frame")) else incoming.getJSONObject("frame").toString().toByteArray()
            assertEquals(incoming.getString("plaintext"), cipher.decrypt(frame))
            assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(frame) }
        }
    }
    @Test fun gatewayChunksEnforceOrderingLengthAndBounds() {
        val bytes = ByteArray(2 * 1024 * 1024 + 7) { (it % 251).toByte() }; val chunks = RelayChunks.split(bytes)
        val assembler = RelayChunks(); var result: ByteArray? = null
        chunks.forEach { result = assembler.push(it) }; assertArrayEquals(bytes, result)
        assertThrows(IllegalArgumentException::class.java) { RelayChunks().push(byteArrayOf(1, 3, 127, -1, -1, -1)) }
        assertThrows(IllegalArgumentException::class.java) { RelayChunks().push(byteArrayOf(1, 2, 7)) }
    }
    @Test fun pairingDoesNotRetainMasterAndRejectsExpiredInsecureOrInjectedOrigin() {
        val link = PairingLink.parse("https://phone.example/#setup=0123456789abcdef0123456789abcdef&gateways=wss%3A%2F%2Fgw.example")
        assertEquals("", link.relay.getString("token")); assertEquals("bootstrap", link.invitation.getString("id"))
        assertEquals(32, RelayCrypto.decode(link.relay.getString("rendezvousKey")).size)
        assertThrows(IllegalArgumentException::class.java) { PairingLink.parse("http://phone.example/#setup=0123456789abcdef0123456789abcdef") }
        assertThrows(IllegalArgumentException::class.java) { PairingLink.parse("https://phone.example/#setup=0123456789abcdef0123456789abcdef&relay=wss%3A%2F%2Fuser%3Apass%40gw.example") }
    }
    @Test fun staleApprovalDeltaAndConversationSequenceCannotBeApplied() {
        val approval = JSONObject().put("approval_fingerprint", "old").put("event_id", "event").put("options", JSONArray().put("Allow").put("Deny"))
        assertNotNull(AgentProtocol.approval(approval, approval, 0))
        assertNull(AgentProtocol.approval(JSONObject(approval.toString()).put("approval_fingerprint", "new"), approval, 0))
        assertNull(AgentProtocol.applyDelta("one\ntwo\n", JSONObject().put("base_fingerprint", "wrong"), "old"))
        assertEquals("one\nchanged\n", AgentProtocol.applyDelta("one\ntwo\n", JSONObject().put("base_fingerprint", "old").put("segments", JSONArray().put(JSONObject().put("copy_start", 0).put("copy_lines", 1)).put(JSONObject().put("text", "changed\n"))), "old"))
        val state = ConversationState()
        state.page(JSONObject().put("available", true).put("source_revision", "r1").put("subscription_id", "s1").put("sequence", 0).put("entries", JSONArray().put(JSONObject().put("id", "one").put("text", "old"))))
        assertFalse(state.update(JSONObject().put("source_revision", "r1").put("subscription_id", "s1").put("kind", "update").put("sequence", 2)))
        assertEquals("old", state.entries["one"]!!.getString("text"))
    }
}
