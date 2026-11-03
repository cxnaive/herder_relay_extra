package dev.herdr.companion

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Only one bounded chunk is in memory; a failed/cancelled file is never exported. */
class DownloadSink(val file: File, private val path: String, private val revision: String, val size: Long) {
    private val output = FileOutputStream(file)
    var received = 0L; private set
    private var complete = false
    fun accept(data: JSONObject): Boolean {
        check(!complete)
        require(!data.optBoolean("metadata_only") && data.optString("path") == path)
        require(data.optString("download_revision") == revision && data.getLong("size") == size)
        require(data.getLong("offset") == received)
        val encoded = data.getString("data"); require(encoded.length <= 720 * 1024)
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= 512 * 1024 && bytes.size.toLong() <= size - received)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == data.getString("chunk_sha256"))
        val end = received + bytes.size
        val cursor = data.optString("next_cursor")
        require(if (end == size) cursor.isEmpty() else bytes.isNotEmpty() && cursor == end.toString())
        output.write(bytes); received = end
        if (received == size) { output.close(); complete = true }
        return complete
    }
    fun discard() { runCatching { output.close() }; file.delete() }
    companion object {
        fun validateMetadata(data: JSONObject, path: String) {
            require(data.optBoolean("metadata_only") && data.getString("path") == path)
            require(data.getLong("size") in 0..9_007_199_254_740_991L)
            require(Regex("[0-9a-f]{64}").matches(data.getString("download_revision")))
            require(data.optString("data").isEmpty())
        }
    }
}
