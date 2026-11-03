package dev.herdr.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class DownloadSinkTest {
    private val revision = "c".repeat(64)
    private fun chunk(bytes: ByteArray, offset: Long, size: Long) = JSONObject().put("path", "bundle.zip").put("size", size)
        .put("offset", offset).put("download_revision", revision).put("data", Base64.getEncoder().encodeToString(bytes))
        .put("chunk_sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        .put("next_cursor", if (offset + bytes.size < size) (offset + bytes.size).toString() else "")
    @Test fun streamsMoreThanPreviewLimitToDiskWithoutAccumulatingWholeFile() {
        val file = File.createTempFile("download", ".bin")
        val size = ReviewDecoding.MAX_BYTES.toLong() + 113
        val sink = DownloadSink(file, "bundle.zip", revision, size)
        val expected = MessageDigest.getInstance("SHA-256")
        try {
            while (sink.received < size) {
                val bytes = ByteArray(minOf(512 * 1024L, size - sink.received).toInt()) { ((it + sink.received) % 251).toByte() }
                expected.update(bytes)
                val offset = sink.received
                assertEquals(offset + bytes.size == size, sink.accept(chunk(bytes, offset, size)))
            }
            assertEquals(size, file.length())
            val actual = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; actual.update(buffer, 0, n) } }
            assertArrayEquals(expected.digest(), actual.digest())
        } finally { sink.discard() }
        assertFalse(file.exists())
    }
    @Test fun rejectsChangedRevisionBadOffsetsHashesAndCursorThenCleansPartialFile() {
        for (mutate in listOf<(JSONObject) -> Unit>({ it.put("download_revision", "d".repeat(64)) }, { it.put("offset", 0) },
            { it.put("path", "secret") }, { it.put("chunk_sha256", "0".repeat(64)) }, { it.put("next_cursor", "99") }, { it.put("size", 9) })) {
            val file = File.createTempFile("download", ".bin"); val sink = DownloadSink(file, "bundle.zip", revision, 6)
            assertFalse(sink.accept(chunk(byteArrayOf(1,2,3), 0, 6)))
            assertTrue(runCatching { sink.accept(chunk(byteArrayOf(4,5,6), 3, 6).apply(mutate)) }.isFailure)
            sink.discard(); assertFalse(file.exists())
        }
    }
    @Test fun emptyFileIsAValidDownload() {
        val file = File.createTempFile("download", ".bin"); val sink = DownloadSink(file, "bundle.zip", revision, 0)
        assertTrue(sink.accept(chunk(byteArrayOf(), 0, 0))); assertEquals(0, file.length()); sink.discard()
    }
    @Test fun metadataOnlyOpenSkipsPreviewAndRetainsFileActionsData() {
        val meta = JSONObject().put("path", "bundle.zip").put("size", 60L * 1024 * 1024).put("kind", "binary").put("metadata_only", true)
            .put("download_revision", revision).put("preview_available", false).put("preview_reason", "超过预览限制")
        var calls = 0; var opened: JSONObject? = null
        DocumentTransfer.preview("bundle.zip", { true }, { type, request, done ->
            calls++; assertEquals("workspace_file_download", type); assertTrue(request.getBoolean("metadata_only")); done(meta, null)
        }) { data, error -> assertNull(error); opened = data }
        assertEquals(1, calls); assertEquals(60L * 1024 * 1024, opened!!.getLong("size")); assertFalse(opened!!.has("data"))
    }
    @Test fun failedPreviewStillOpensMetadataRatherThanLosingDownloadEntry() {
        val meta = JSONObject().put("path", "bundle.zip").put("size", 10).put("kind", "pdf").put("metadata_only", true)
            .put("download_revision", revision).put("preview_available", true)
        var opened: JSONObject? = null
        DocumentTransfer.preview("bundle.zip", { true }, { type, _, done -> if (type == "workspace_file_download") done(meta, null) else done(null, "read failed") }) { data, error -> assertNull(error); opened = data }
        assertFalse(opened!!.getBoolean("preview_available")); assertEquals("read failed", opened!!.getString("preview_reason"))
    }
    @Test fun unrestrictedScopeNeverAttachesToWritesOrAgentCommands() {
        for (type in listOf("workspace_file", "workspace_file_read", "workspace_file_download", "workspace_list")) {
            assertEquals("filesystem", FileReadScope.apply(JSONObject().put("type", type), true).getString("file_scope"))
            assertEquals("workspace", FileReadScope.apply(JSONObject().put("type", type), false).getString("file_scope"))
        }
        for (type in listOf("workspace_file_write", "send_prompt", "workspace_git_diff")) assertFalse(FileReadScope.apply(JSONObject().put("type", type), true).has("file_scope"))
    }
}
