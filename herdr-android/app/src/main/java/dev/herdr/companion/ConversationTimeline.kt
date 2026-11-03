package dev.herdr.companion

import org.json.JSONObject

/** Keep prose chronological, but coalesce adjacent tool-only events across JSONL entries. */
object ConversationTimeline {
    sealed class Block
    data class Message(val entry: JSONObject) : Block()
    data class Tool(val key: String, val entryId: String, val value: JSONObject)
    data class Tools(val key: String, val tools: List<Tool>, val entries: List<JSONObject>) : Block()

    fun blocks(entries: Collection<JSONObject>): List<Block> {
        val blocks = mutableListOf<Block>()
        val tools = mutableListOf<Tool>()
        val sources = linkedMapOf<String, JSONObject>()
        fun flush() {
            if (tools.isNotEmpty()) blocks += Tools("tools:" + tools.first().key, tools.toList(), sources.values.toList())
            tools.clear(); sources.clear()
        }
        for (entry in entries) {
            val array = entry.optJSONArray("tools")
            val hasTools = array != null && array.length() > 0
            if (!hasTools && entry.optString("role") != "user" && entry.optString("text").isBlank() &&
                (entry.optJSONArray("images")?.length() ?: 0) == 0 && !entry.optBoolean("truncated")) continue
            val boundary = entry.optString("role") == "user" || entry.optString("text").isNotBlank() || !hasTools
            if (boundary) {
                flush()
                if (entry.optString("text").isNotBlank() || !hasTools || entry.optString("role") == "user") blocks += Message(entry)
            }
            if (array != null) {
                val id = entry.getString("id")
                for (index in 0 until array.length()) {
                    val tool = array.optJSONObject(index) ?: continue
                    val stable = tool.optString("id").ifBlank { index.toString() }
                    tools += Tool("$id:tool:$stable", id, tool)
                    sources[id] = entry
                }
            }
        }
        flush()
        return blocks
    }

    fun operation(tool: JSONObject): String {
        val name = tool.optString("name").substringAfterLast('.').lowercase()
        return when {
            name in listOf("exec", "exec_command", "bash", "shell", "terminal", "run_command", "write_stdin") -> "命令"
            name in listOf("view_image", "open_image", "image") -> "图片"
            name in listOf("read", "read_file", "cat") -> "读取"
            name in listOf("edit", "write", "write_file", "apply_patch") -> "修改"
            name.contains("search") || name in listOf("grep", "find", "rg", "glob") -> "搜索"
            else -> tool.optString("name").substringAfterLast('.').ifBlank { "工具" }
        }
    }

    fun overview(block: Tools): String {
        val groups = block.tools.groupingBy { operation(it.value) }.eachCount()
        return groups.entries.take(4).joinToString(" · ") { "${it.key} ${it.value}" } + if (groups.size > 4) " · …" else ""
    }

    fun detail(tool: JSONObject, field: String): String {
        val value = tool.opt(field)
        return when (value) {
            null, JSONObject.NULL -> ""
            is JSONObject -> value.toString(2)
            is org.json.JSONArray -> value.toString(2)
            else -> value.toString()
        }
    }

    fun preview(tool: JSONObject): String {
        val input = tool.optJSONObject("input") ?: runCatching { JSONObject(tool.optString("input")) }.getOrNull()
        val value = if (input == null) detail(tool, "input") else listOf("cmd", "command", "path", "file_path", "pattern", "query")
            .firstNotNullOfOrNull { key -> input.optString(key).takeIf { it.isNotBlank() } }.orEmpty()
        return value.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(160)
    }
}
