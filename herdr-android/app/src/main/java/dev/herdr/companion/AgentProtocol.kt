package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject

object AgentProtocol {
    fun command(relay: String, agent: JSONObject, type: String): JSONObject = JSONObject().put("type", type).put("protocol", 3)
        .put("pane_id", agent.getString("pane_id")).put("cwd", agent.optString("cwd"))
        .put("server_session_id", agent.optString("server_session_id").ifBlank { "primary" })
        .put("target", AgentTarget.fromAgent(relay, agent).json())
    fun targetMatches(relay: String, agent: JSONObject, message: JSONObject): Boolean {
        val target = message.optJSONObject("target") ?: return false
        return AgentTarget.fromAgent(relay, agent) == AgentTarget.fromAgent(relay, target)
    }
    fun applyDelta(previous: String, delta: JSONObject, fingerprint: String): String? = runCatching {
        if (delta.optString("base_fingerprint") != fingerprint) return null
        val segments = delta.optJSONArray("segments")
        if (segments == null || segments.length() == 0) return if (delta.optString("content_fingerprint") == fingerprint) previous else null
        val lines = Regex(".*?\n|.+$", RegexOption.DOT_MATCHES_ALL).findAll(previous).map { it.value }.toList() + if (previous.endsWith('\n')) listOf("") else emptyList()
        val output = StringBuilder()
        for (i in 0 until segments.length()) {
            val part = segments.getJSONObject(i); val count = part.optInt("copy_lines")
            if (count > 0) { val start = part.optInt("copy_start"); if (start < 0 || start.toLong() + count > lines.size) return null; for (line in start until start + count) output.append(lines[line]) }
            else output.append(part.optString("text"))
            if (output.length > 2 * 1024 * 1024) return null
        }
        output.toString()
    }.getOrNull()
    fun approval(current: JSONObject, captured: JSONObject, index: Int): JSONObject? {
        if (current.optString("approval_fingerprint").isBlank() || current.optString("approval_fingerprint") != captured.optString("approval_fingerprint") || current.optString("event_id") != captured.optString("event_id")) return null
        val options = captured.optJSONArray("options") ?: return null
        if (index !in 0 until options.length()) return null
        return JSONObject().put("type", "respond").put("index", index).put("total", options.length()).put("choice", options.getString(index))
            .put("event_id", captured.optString("event_id")).put("approval_fingerprint", captured.getString("approval_fingerprint"))
    }
}

class ConversationState {
    val entries = linkedMapOf<String, JSONObject>()
    var revision = ""; private set
    var subscription = ""; private set
    var sequence = 0L; private set
    var next = ""; private set
    var loadedOlder = false; private set
    fun resetCursor() { next = ""; loadedOlder = false }
    fun cachedPage(): JSONObject = JSONObject().put("available", true).put("source_revision", revision).put("next_cursor", next)
        .put("entries", JSONArray(entries.values.toList())).put("loaded_older", loadedOlder)
    fun restoreCached(value: JSONObject) { page(value); loadedOlder = value.optBoolean("loaded_older"); subscription = ""; sequence = 0 }
    fun page(value: JSONObject, older: Boolean = false, preserveLoaded: Boolean = false): Boolean {
        if (!value.optBoolean("available") || value.optString("state") in listOf("preparing", "failed")) return false
        val newRevision = value.optString("source_revision")
        if (older && newRevision != revision) return false
        val rows = value.optJSONArray("entries") ?: JSONArray()
        val preserve = !older && preserveLoaded && revision.isNotBlank() && revision == newRevision
        val oldNext = next; val hadOlder = loadedOlder
        val retained = if (older || preserve) LinkedHashMap(entries) else linkedMapOf()
        entries.clear()
        if (preserve) entries.putAll(retained)
        for (i in 0 until rows.length()) rows.optJSONObject(i)?.let { entries[it.getString("id")] = it }
        if (older) entries.putAll(retained)
        next = if (preserve && hadOlder) oldNext else value.optString("next_cursor")
        loadedOlder = older || (preserve && hadOlder)
        if (!older) {
            revision = newRevision; subscription = value.optString("subscription_id"); sequence = value.optLong("sequence")
            val provisional = value.optJSONArray("provisional")
            if (provisional != null) for (i in 0 until provisional.length()) provisional.optJSONObject(i)?.let { entries[it.getString("id")] = it }
        }
        return true
    }
    fun update(batch: JSONObject): Boolean {
        val kind = batch.optString("kind")
        if (batch.optString("source_revision") != revision || batch.optString("subscription_id") != subscription || kind == "reset") return false
        val newSequence = batch.optLong("sequence", -1)
        if (kind == "heartbeat") return newSequence == sequence
        if (newSequence != sequence + 1) return false
        val remove = batch.optJSONArray("remove") ?: JSONArray(); for (i in 0 until remove.length()) entries.remove(remove.getString(i))
        val rows = batch.optJSONArray("entries") ?: JSONArray(); for (i in 0 until rows.length()) rows.optJSONObject(i)?.let { entries[it.getString("id")] = it }
        sequence = newSequence
        return entries.size <= 2000
    }
}

/** A preparation response continues the same history request, never a fresh recent-page request. */
data class ConversationHistoryRequest(val older: Boolean, val cursor: String, val observe: Boolean) {
    fun payload(): JSONObject = JSONObject().put("limit", 24).put("cursor", cursor).put("observe", observe)
    fun afterPreparing(value: JSONObject): ConversationHistoryRequest =
        copy(cursor = value.optString("next_cursor").ifBlank { cursor })
}
