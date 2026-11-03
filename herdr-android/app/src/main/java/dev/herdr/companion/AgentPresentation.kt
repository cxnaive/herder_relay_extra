package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject

object AgentPresentation {
    data class Group(val relay: String, val server: String, val id: String, val title: String, val path: String, val number: Int, val agents: List<JSONObject>)
    fun title(agent: JSONObject): String = agent.optString("tab_label").ifBlank { agent.optString("tab_name") }
        .ifBlank { agent.optString("name") }.ifBlank { agent.optString("agent", "Agent") }
    fun server(agent: JSONObject) = agent.optString("server_session_id").ifBlank { "primary" }
    fun groups(inventory: Map<String, JSONArray>, workspaces: Map<String, JSONArray>): List<Group> {
        val result = mutableListOf<Group>()
        for ((relay, entries) in inventory) {
            val agents = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
            for ((key, members) in agents.groupBy { server(it) to it.optString("workspace_id").ifBlank { "cwd:" + it.optString("cwd") } }) {
                val metadata = workspaces[relay]?.let { rows -> (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    .find { server(it) == key.first && it.optString("workspace_id") == key.second } }
                val path = metadata?.optString("cwd").orEmpty().ifBlank { members.first().optString("cwd") }
                val title = metadata?.optString("label").orEmpty().ifBlank { members.first().optString("workspace_name") }
                    .ifBlank { path.trimEnd('/').substringAfterLast('/').ifBlank { "未命名工作空间" } }
                val sorted = members.sortedWith(compareBy<JSONObject> { it.optInt("tab_order", it.optInt("tab_number", Int.MAX_VALUE)) }
                    .thenBy { it.optString("tab_id") }.thenBy { it.optString("pane_id") })
                result += Group(relay, key.first, key.second, title, path, metadata?.optInt("number", Int.MAX_VALUE) ?: Int.MAX_VALUE, sorted)
            }
        }
        for ((relay, entries) in workspaces) for (i in 0 until entries.length()) {
            val ws = entries.optJSONObject(i) ?: continue
            val id = ws.optString("workspace_id"); val server = server(ws)
            if (id.isBlank() || result.any { it.relay == relay && it.server == server && it.id == id }) continue
            val path = ws.optString("cwd")
            result += Group(relay, server, id, ws.optString("label").ifBlank { path.trimEnd('/').substringAfterLast('/').ifBlank { "未命名工作空间" } }, path, ws.optInt("number", Int.MAX_VALUE), emptyList())
        }
        return result.sortedWith(compareBy<Group> { it.relay }.thenBy { it.server }.thenBy { it.number }.thenBy { it.title }.thenBy { it.id })
    }
}
