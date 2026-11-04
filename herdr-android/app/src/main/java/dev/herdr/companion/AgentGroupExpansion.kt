package dev.herdr.companion

import android.content.Context
import org.json.JSONArray

/** Display preferences only: folding never changes connections, subscriptions, or monitoring. */
internal class AgentGroupExpansion(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val collapsed = preferences.getStringSet("collapsed", emptySet()).orEmpty().toMutableSet()
    private var query = ""
    private val searchOverrides = mutableMapOf<String, Boolean>()

    fun updateSearch(value: String) {
        val normalized = value.trim()
        if (query == normalized) return
        query = normalized
        searchOverrides.clear()
    }
    fun expanded(key: String, search: String): Boolean {
        updateSearch(search)
        return if (query.isNotEmpty()) searchOverrides[key] ?: true else key !in collapsed
    }
    fun toggle(key: String, search: String) {
        val next = !expanded(key, search)
        if (query.isNotEmpty()) { searchOverrides[key] = next; return }
        if (next) collapsed.remove(key) else collapsed.add(key)
        preferences.edit().putStringSet("collapsed", collapsed.toSet()).apply()
    }
    companion object {
        const val PREFERENCES = "agent-group-expansion"
        fun computerKey(relay: String): String = JSONArray().put("computer").put(relay).toString()
        fun workspaceKey(group: AgentPresentation.Group): String = JSONArray().put("workspace")
            .put(group.relay).put(group.server).put(group.id).toString()
    }
}
