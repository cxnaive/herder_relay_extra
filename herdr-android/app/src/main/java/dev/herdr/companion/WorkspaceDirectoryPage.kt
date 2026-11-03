package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject

/** A page contains direct children only; an old response cannot masquerade as a new directory. */
data class WorkspaceDirectoryPage(val root: String, val path: String, val entries: JSONArray,
    val cursor: String, val revision: String, val total: Int, val skipped: Int) {
    companion object {
        fun accept(data: JSONObject, expectedPath: String, previous: WorkspaceDirectoryPage? = null): WorkspaceDirectoryPage {
            require(data.getString("path") == expectedPath) { "目录响应不匹配，请刷新" }
            val root = data.getString("root"); val revision = data.getString("revision")
            require(root.startsWith('/') && revision.isNotBlank()) { "目录信息不完整，请刷新" }
            if (previous != null) require(previous.path == expectedPath && previous.root == root && previous.revision == revision) { "目录已变化，请刷新后继续" }
            val rows = data.getJSONArray("entries"); require(rows.length() <= 200)
            val entries = linkedMapOf<String, JSONObject>()
            previous?.entries?.let { for (i in 0 until it.length()) entries[it.getJSONObject(i).getString("path")] = it.getJSONObject(i) }
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); val path = row.getString("path"); val name = row.getString("name")
                require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it in listOf("", ".", "..") } && !path.contains('\u0000'))
                require(FileRules.parent(path) == expectedPath && path.substringAfterLast('/') == name && row.getString("kind") in listOf("file", "directory")) { "目录内容不匹配，请刷新" }
                require(path !in entries) { "目录分页重复，请刷新" }
                entries[path] = row
            }
            val next = data.optString("next_cursor")
            require(next.length <= 4096 && (next.isEmpty() || rows.length() > 0) && (previous == null || next.isEmpty() || next != previous.cursor)) { "目录分页游标未前进" }
            val total = data.getInt("total"); val skipped = data.optInt("skipped")
            require(total >= entries.size && skipped >= 0 && (next.isNotBlank() || total == entries.size)) { "目录分页不完整，请刷新" }
            return WorkspaceDirectoryPage(root, expectedPath, JSONArray(entries.values.toList()), next, revision, total, skipped)
        }
    }
}
