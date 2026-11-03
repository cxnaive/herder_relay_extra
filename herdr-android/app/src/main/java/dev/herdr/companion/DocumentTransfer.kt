package dev.herdr.companion

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Assemble small authenticated frames; never combine bytes from different file revisions. */
object DocumentTransfer {
    /** Metadata is independent of preview bytes, so large files still open. */
    fun preview(path: String, valid: () -> Boolean,
        request: (String, JSONObject, (JSONObject?, String?) -> Unit) -> Unit,
        done: (JSONObject?, String?) -> Unit) {
        request("workspace_file_download", JSONObject().put("path", path).put("metadata_only", true)) response@ { data, error ->
            if (!valid()) return@response
            if (error != null || data == null) { done(null, error ?: "未返回文件信息"); return@response }
            try { DownloadSink.validateMetadata(data, path) } catch (_: Exception) { done(null, "文件信息无效，请重新打开"); return@response }
            if (!data.optBoolean("preview_available")) { done(data, null); return@response }
            read(path, valid, { payload, callback -> request("workspace_file_read", payload, callback) }) { preview, failure ->
                // Even a decode/read failure must not remove the file's download entry.
                if (failure != null || preview == null) done(JSONObject(data.toString()).put("preview_available", false).put("preview_reason", failure ?: "预览失败"), null)
                else done(preview, null)
            }
        }
    }

    fun read(path: String, valid: () -> Boolean, request: (JSONObject, (JSONObject?, String?) -> Unit) -> Unit,
        done: (JSONObject?, String?) -> Unit) {
        val output = ByteArrayOutputStream()
        val started = System.nanoTime()
        var digest = ""; var size = -1; var kind = ""; var finished = false
        fun fail(message: String) { if (!finished) { finished = true; done(null, message) } }
        fun next(cursor: String) {
            if (finished || !valid()) return
            if (System.nanoTime() - started > 120_000_000_000L) { fail("文档读取超时，请重试"); return }
            val payload = JSONObject().put("path", path)
            if (cursor.isNotBlank()) payload.put("cursor", cursor).put("expected_revision", digest)
            request(payload) response@ { data, error ->
                if (finished || !valid()) return@response
                if (error != null || data == null) { fail(error ?: "没有收到文档分片"); return@response }
                try {
                    require(data.has("offset") && data.optInt("offset", -1) == output.size())
                    require(data.optString("path") == path)
                    val total = data.optInt("size", -1); require(total in 0..ReviewDecoding.MAX_BYTES)
                    val hash = data.optString("sha256"); require(Regex("[0-9a-f]{64}").matches(hash))
                    if (size < 0) { size = total; digest = hash; kind = data.optString("kind") }
                    require(total == size && hash == digest && kind == data.optString("kind"))
                    val encoded = data.optString("data"); require(encoded.length <= 720 * 1024)
                    val bytes = Base64.getDecoder().decode(encoded); require(bytes.size <= 512 * 1024 && output.size() + bytes.size <= size)
                    output.write(bytes)
                    val more = data.optString("next_cursor")
                    if (more.isNotBlank()) {
                        require(bytes.isNotEmpty() && more == output.size().toString() && output.size() < size)
                        next(more)
                    } else {
                        require(output.size() == size)
                        val all = output.toByteArray()
                        val actual = MessageDigest.getInstance("SHA-256").digest(all).joinToString("") { "%02x".format(it) }
                        require(actual == digest)
                        val result = JSONObject(data.toString()).put("data", Base64.getEncoder().encodeToString(all))
                        result.remove("offset"); result.remove("next_cursor"); finished = true; done(result, null)
                    }
                } catch (_: Exception) { fail("文档分片不完整或文件在读取时变化，请重新打开") }
            }
        }
        next("")
    }
}
