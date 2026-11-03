package dev.herdr.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class DocumentTransferTest {
    private val bytes = "%PDF-1.7\nexample".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun chunk(offset: Int, end: Int) = JSONObject().put("path", "paper.pdf").put("kind", "pdf").put("size", bytes.size).put("offset", offset)
        .put("sha256", hash).put("next_cursor", if (end < bytes.size) end.toString() else "")
        .put("data", Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end)))
    @Test fun assemblesAuthenticatedChunksAndChecksFinalHash() {
        var result: JSONObject? = null; var count = 0
        DocumentTransfer.read("paper.pdf", { true }, { request, done ->
            count++; val offset = request.optString("cursor").toIntOrNull() ?: 0
            if (offset > 0) assertEquals(hash, request.getString("expected_revision"))
            done(chunk(offset, minOf(offset + 5, bytes.size)), null)
        }) { data, error -> assertNull(error); result = data }
        assertTrue(count > 1); assertArrayEquals(bytes, Base64.getDecoder().decode(result!!.getString("data")))
        assertFalse(result!!.has("revision")); assertFalse(result!!.has("offset"))
    }
    @Test fun refusesWrongRevisionOffsetAndOversizedFiles() {
        for (change in listOf<(JSONObject) -> Unit>({ it.put("sha256", "b".repeat(64)) }, { it.put("offset", 3) }, { it.put("size", ReviewDecoding.MAX_BYTES + 1) }, { it.put("path", "other.pdf") })) {
            var failed = false
            DocumentTransfer.read("paper.pdf", { true }, { _, done -> done(chunk(0, bytes.size).apply(change), null) }) { data, error -> assertNull(data); failed = error != null }
            assertTrue(failed)
        }
    }
    @Test fun stopsOnNavigationAndDoesNotDeliverLateChunks() {
        var valid = true; var callback: ((JSONObject?, String?) -> Unit)? = null; var delivered = false
        DocumentTransfer.read("paper.pdf", { valid }, { _, done -> callback = done }) { _, _ -> delivered = true }
        valid = false; callback!!(chunk(0, 5), null); assertFalse(delivered)
    }
}
