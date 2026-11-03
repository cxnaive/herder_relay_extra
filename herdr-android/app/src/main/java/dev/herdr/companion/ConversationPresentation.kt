package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.file.Paths

object ConversationPresentation {
    data class Image(val source: String, val label: String, val workspacePath: String?)
    private val markdownImage = Regex("!\\[([^]]*)]\\((?:<([^>]+)>|([^\\s)]+))(?:\\s+\"[^\"]*\")?\\)")
    private val imageSuffix = Regex("(?i)\\.(png|jpe?g|webp|gif|bmp)$")
    fun workspacePath(value: String, cwd: String): String? = runCatching {
        if (cwd.isBlank() || value.contains('\u0000') || value.startsWith("//")) return null
        val source = if (value.startsWith("file://")) Paths.get(URI(value)) else {
            if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value)) return null
            Paths.get(value)
        }
        val root = Paths.get(cwd).normalize()
        if (!root.isAbsolute) return null
        val path = (if (source.isAbsolute) source else root.resolve(source)).normalize()
        if (!path.startsWith(root) || path == root) return null
        root.relativize(path).toString()
    }.getOrNull()
    fun images(entry: JSONObject, cwd: String): List<Image> {
        val result = linkedMapOf<String, Image>()
        fun add(source: String, caption: String = "") {
            if (result.size >= 8 || source.isBlank() || source.length > 16 * 1024 * 1024) return
            val inline = source.startsWith("data:image/")
            if (!inline && !imageSuffix.containsMatchIn(source)) return
            val path = if (inline) null else workspacePath(source, cwd)
            val label = caption.ifBlank { if (inline) "图像" else source.substringAfterLast('/').take(160) }
            result.putIfAbsent(path?.let { "workspace:$it" } ?: source, Image(source, label, path))
        }
        fun fromText(text: String) { markdownImage.findAll(text).forEach { add(it.groupValues[2].ifBlank { it.groupValues[3] }, it.groupValues[1]) } }
        fromText(entry.optString("text"))
        val images = entry.optJSONArray("images") ?: JSONArray()
        for (i in 0 until images.length()) {
            val image = images.optJSONObject(i)
            if (image == null) add(images.optString(i)) else add(image.optString("data_url").ifBlank { image.optString("path") }, image.optString("name"))
        }
        val tools = entry.optJSONArray("tools") ?: JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            fromText(tool.optString("output"))
            val input = tool.optJSONObject("input") ?: runCatching { JSONObject(tool.optString("input")) }.getOrNull()
            if (input != null) for (key in listOf("path", "file_path", "filePath", "image_path", "filename", "url")) add(input.optString(key))
        }
        return result.values.toList()
    }
    fun toolSummary(tools: JSONArray): String {
        val rows = (0 until tools.length()).mapNotNull { tools.optJSONObject(it) }
        val running = rows.count { it.optString("state") in listOf("running", "pending", "in_progress", "started") }
        val errors = rows.count { it.optBoolean("error") || it.optString("state") in listOf("error", "failed") }
        return when {
            errors > 0 -> "${rows.size} 项工具调用 · $errors 项失败"
            running > 0 -> "${rows.size} 项工具调用 · $running 项进行中"
            else -> "${rows.size} 项工具调用"
        }
    }
}
