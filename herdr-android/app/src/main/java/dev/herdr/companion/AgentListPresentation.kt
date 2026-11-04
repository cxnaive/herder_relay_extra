package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject

internal object AgentListPresentation {
    data class Workspace(val group: AgentPresentation.Group, val agents: List<JSONObject>)
    data class Computer(val id: String, val label: String, val workspaces: List<Workspace>,
        val totalWorkspaces: Int, val totalAgents: Int) {
        val matchingAgents get() = workspaces.sumOf { it.agents.size }
    }

    fun computers(relays: JSONArray, inventory: Map<String, JSONArray>, workspaces: Map<String, JSONArray>, search: String): List<Computer> {
        val groups = AgentPresentation.groups(inventory, workspaces).groupBy { it.relay }
        val labels = linkedMapOf<String, String>()
        for (index in 0 until relays.length()) {
            val relay = relays.optJSONObject(index) ?: continue
            val id = relay.optString("id")
            if (id.isNotBlank()) labels[id] = relay.optString("label").ifBlank { "电脑" }
        }
        for (id in groups.keys) labels.putIfAbsent(id, "电脑")
        val query = search.trim()
        return labels.mapNotNull { (id, label) ->
            val all = groups[id].orEmpty()
            val deviceMatches = label.contains(query, true)
            val visible = all.mapNotNull { group ->
                val workspaceMatches = query.isEmpty() || deviceMatches || group.title.contains(query, true) ||
                    group.path.contains(query, true) || group.server.contains(query, true)
                val agents = if (workspaceMatches) group.agents else group.agents.filter { it.toString().contains(query, true) }
                if (workspaceMatches || agents.isNotEmpty()) Workspace(group, agents) else null
            }
            if (query.isNotEmpty() && !deviceMatches && visible.isEmpty()) null
            else Computer(id, label, visible, all.size, all.sumOf { it.agents.size })
        }
    }
}
