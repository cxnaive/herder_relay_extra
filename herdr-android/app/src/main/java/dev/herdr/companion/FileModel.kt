package dev.herdr.companion

import java.util.Locale

object FileRules {
    fun isMarkdown(path: String) = path.substringAfterLast('.').lowercase(Locale.ROOT) in listOf("md", "markdown", "mdown")
    fun safeName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\x00-\\x1f/:*?\"<>|]"), "_").take(180).ifBlank { "download" }
    fun parent(path: String) = path.substringBeforeLast('/', "")
    fun size(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
