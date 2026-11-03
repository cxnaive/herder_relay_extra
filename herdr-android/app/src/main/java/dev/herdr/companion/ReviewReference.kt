package dev.herdr.companion

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Paths
import java.util.Locale

/** References never select a new filesystem root or turn a URL into a relay read. */
data class ReviewReference(val path: String, val label: String = FileRules.safeName(path), val page: Int = 1,
    val line: Int = 0, val anchor: String = "", val inlineImage: String? = null) {
    val kind: String get() = when {
        inlineImage != null -> "image"
        path.substringAfterLast('.').lowercase(Locale.ROOT) == "pdf" -> "pdf"
        FileRules.isMarkdown(path) -> "markdown"
        Regex("(?i)\\.(png|jpe?g|webp|gif|bmp)$").containsMatchIn(path) -> "image"
        else -> "file"
    }
}

object ReviewReferences {
    private val lineSuffix = Regex(":([1-9][0-9]*)(?::[1-9][0-9]*)?$")
    private val parser = Parser.builder().also(CodexFileCitations::configure).build()
    fun local(raw: String, cwd: String, relativeTo: String = "", label: String = "", unrestricted: Boolean = false): ReviewReference? = runCatching {
        if (raw.isBlank() || raw.length > 8192 || raw.any { it.code < 32 } || raw.startsWith("//")) return null
        var value = raw.trim()
        var anchor = value.substringAfter('#', "")
        value = value.substringBefore('#')
        if (value.startsWith("file://")) value = Paths.get(URI(value)).toString()
        else {
            if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value) && !lineSuffix.containsMatchIn(value)) return null
            value = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        }
        var line = 0
        lineSuffix.find(value)?.let { line = it.groupValues[1].toIntOrNull() ?: 0; value = value.substring(0, it.range.first) }
        if (value.isBlank() && relativeTo.isNotBlank()) value = relativeTo
        else if (!value.startsWith('/') && relativeTo.isNotBlank()) value = Paths.get(relativeTo).parent?.resolve(value)?.toString() ?: value
        if (value.any { it.code < 32 }) return null
        if (unrestricted && !value.startsWith('/') && relativeTo.isBlank()) value = Paths.get(cwd).resolve(value).toString()
        val path = ConversationPresentation.workspacePath(value, if (unrestricted) "/" else cwd) ?: return null
        anchor = URLDecoder.decode(anchor.replace("+", "%2B"), "UTF-8")
        Regex("^[Ll]([1-9][0-9]*)(?:[Cc][0-9]+)?(?:-[Ll]?[0-9]+)?$").matchEntire(anchor)?.let { line = it.groupValues[1].toIntOrNull() ?: 0 }
        val page = Regex("(?i)^page=([1-9][0-9]*)$").matchEntire(anchor)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        ReviewReference(path, label.ifBlank { FileRules.safeName(path) }, page.coerceAtMost(100000), line.coerceAtMost(1000000), anchor)
    }.getOrNull()

    fun documents(entry: JSONObject, cwd: String, unrestricted: Boolean = false): List<ReviewReference> {
        val found = linkedMapOf<String, ReviewReference>()
        fun add(source: String, label: String = "") {
            if (found.size >= 8) return
            val ref = local(source, cwd, label = label, unrestricted = unrestricted) ?: return
            if (ref.kind != "image") found.putIfAbsent(ref.path + "#" + ref.anchor + ":" + ref.line, ref)
        }
        fun scan(text: String) {
            parser.parse(text.take(256 * 1024)).accept(object : AbstractVisitor() {
                override fun visit(link: Link) { add(link.destination); super.visit(link) }
                override fun visit(code: Code) {
                    val value = code.literal
                    if (value.contains(":codex-file-citation{")) return
                    if (!value.contains('\n') && (value.contains('/') || Regex("\\.[A-Za-z0-9]{1,12}(?:[:#].*)?$").containsMatchIn(value) || value in listOf("README", "LICENSE", "Dockerfile", "Makefile"))) add(value)
                }
                override fun visit(text: Text) {
                    if (text.parent is Link) return
                    // Plain absolute/explicit-relative paths also get a tappable file card.
                    Regex("(?:^|[\\s（(])((?:/|\\./)[^\\s<>\"，。；）)]+)").findAll(text.literal).forEach { add(it.groupValues[1].trimEnd('.', ',', ';')) }
                }
            })
        }
        scan(entry.optString("text"))
        val tools = entry.optJSONArray("tools")
        for (i in 0 until (tools?.length() ?: 0)) {
            val tool = tools?.optJSONObject(i) ?: continue
            val input = tool.optJSONObject("input") ?: runCatching { JSONObject(tool.optString("input")) }.getOrNull()
            if (input != null) for (key in listOf("path", "file_path", "filePath", "filename")) add(input.optString(key))
        }
        return found.values.toList()
    }

    fun markdownImages(text: String, cwd: String, relativeTo: String, unrestricted: Boolean = false): List<ReviewReference> {
        val found = linkedMapOf<String, ReviewReference>()
        parser.parse(text.take(1024 * 1024)).accept(object : AbstractVisitor() {
            override fun visit(image: Image) {
                if (found.size >= 12) return
                local(image.destination, cwd, relativeTo, unrestricted = unrestricted)?.takeIf { it.kind == "image" }?.let { found.putIfAbsent(it.path, it) }
            }
        })
        return found.values.toList()
    }
}
