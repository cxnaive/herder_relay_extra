package dev.herdr.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** A stable session identity: never a title, cwd, recycled pane number or generation. */
object MonitorIdentity {
    fun key(relay: String, agent: JSONObject): String {
        val session = agent.optString("agent_session_id").ifBlank { agent.optString("session_id") }
        val terminal = agent.optString("terminal_id")
        require(relay.isNotBlank() && (session.isNotBlank() || terminal.isNotBlank()))
        val tuple = JSONArray().put(relay).put(AgentPresentation.server(agent)).put(agent.optString("workspace_id"))
            .put(if (session.isNotBlank()) "session:$session" else "terminal:$terminal").toString()
        return MessageDigest.getInstance("SHA-256").digest(tuple.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun matchesRoute(relay: String, agent: JSONObject, route: JSONObject): Boolean = runCatching {
        relay == route.getString("relay") && key(relay, agent) == route.getString("key")
    }.getOrDefault(false)
    fun route(relay: String, agent: JSONObject) = JSONObject().put("relay", relay).put("key", key(relay, agent))
}

class MonitorPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("background-monitor", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { check(prefs.edit().putBoolean("enabled", value).commit()) }
    fun muted(key: String): Boolean = key in prefs.getStringSet("muted", emptySet()).orEmpty()
    fun mute(key: String, value: Boolean) {
        val keys = prefs.getStringSet("muted", emptySet()).orEmpty().toMutableSet()
        if (value) keys.add(key) else keys.remove(key)
        require(keys.size <= 1024) { "单独关闭提醒的 Agent 数量已达上限" }
        check(prefs.edit().putStringSet("muted", keys).commit())
    }
    fun ledger(): String = prefs.getString("ledger", "{}").orEmpty()
    fun saveLedger(value: String) { check(prefs.edit().putString("ledger", value).commit()) }
    fun clearLedger() { check(prefs.edit().remove("ledger").commit()) }
    fun setError(value: String) { prefs.edit().putString("error", value).apply() }
    fun error(): String = prefs.getString("error", "").orEmpty()
}

/** Durable at-most-once observations. First snapshots establish a baseline, not old alerts. */
class CompletionLedger(raw: String = "{}") {
    private val rows = linkedMapOf<String, JSONObject>()
    init { runCatching {
        require(raw.length <= 2 * 1024 * 1024)
        val data = JSONObject(raw)
        data.keys().asSequence().take(1024).forEach { key -> data.optJSONObject(key)?.let { rows[key] = it } }
    } }
    fun forget(key: String) { rows.remove(key) }
    fun encode(): String = JSONObject().apply { rows.forEach { (key, value) -> put(key, value) } }.toString()
    fun observe(key: String, state: JSONObject, now: Long): Boolean {
        val source = state.optString("source")
        val status = state.optString("status")
        val turn = state.optString("turn_id")
        if (turn.length > 128 || turn.any { it.isISOControl() }) return false
        val previous = rows[key]?.takeIf { now - it.optLong("seen_at") in 0..RETENTION }
        val known = status in setOf("working", "blocked", "done", "idle", "completed", "finished", "interrupted", "failed", "stopped")
        if (!known || source !in setOf("codex_runtime", "herdr")) return false
        // Runtime completion requires a real turn ID; generic idle is not proof of completion.
        if (source == "codex_runtime" && status == "done" && (turn.isBlank() || turn.length > 128)) return false
        if (source == "codex_runtime" && status in setOf("working", "blocked") && turn.isBlank()) return false
        val sameSource = previous?.optString("source") == source
        val completedTurns = previous?.optJSONArray("completed")?.let { values -> (0 until values.length()).map { values.optString(it) }.takeLast(8).toMutableList() } ?: mutableListOf()
        val completed = if (source == "codex_runtime") {
            status == "done" && turn !in completedTurns && sameSource && (previous!!.optString("turn") != turn || previous.optString("status") in setOf("working", "blocked"))
        } else {
            status in setOf("done", "idle", "completed", "finished") && sameSource && previous!!.optString("status") in setOf("working", "blocked")
        }
        if (source == "codex_runtime" && status == "done" && turn !in completedTurns) completedTurns.add(turn)
        val seenAt = previous?.optLong("seen_at")?.takeIf { now - it in 0..3600000 } ?: now
        rows[key] = JSONObject().put("source", source).put("status", status).put("turn", turn).put("seen_at", seenAt).put("completed", JSONArray(completedTurns.takeLast(8)))
        rows.entries.removeAll { now - it.value.optLong("seen_at") > RETENTION }
        while (rows.size > 1024) rows.remove(rows.keys.first())
        return completed
    }
    companion object { private const val RETENTION = 30L * 24 * 60 * 60 * 1000 }
}
