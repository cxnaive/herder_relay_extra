package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject

/** Recover a contiguous recent turn from actual pages, not from the presence of an old cached user bubble. */
class ConversationRecentRecovery(private val state: ConversationState, private val maxPages: Int = 32, private val maxBytes: Int = 2 * 1024 * 1024) {
    private var head: JSONObject? = null
    private var window = linkedMapOf<String, JSONObject>()
    private var anchor = ""
    private var pages = 0
    private var bytes = 0
    var next = ""; private set
    private val gap get() = next.isNotBlank() && (window.values.none { it.optString("role") == "user" } || (anchor.isNotBlank() && anchor !in window))
    val needsMore get() = gap && pages < maxPages && bytes < maxBytes && window.size < 1800
    val incomplete get() = gap
    val size get() = window.size
    fun accept(value: JSONObject) {
        require(value.optBoolean("available") && value.optString("state") !in listOf("preparing", "failed"))
        val revision = value.optString("source_revision")
        if (head == null) {
            head = JSONObject(value.toString())
            anchor = if (state.revision == revision) state.entries.keys.lastOrNull().orEmpty() else ""
        } else require(revision == head!!.optString("source_revision")) { "会话版本已变化" }
        val rows = value.optJSONArray("entries") ?: JSONArray()
        val earlier = linkedMapOf<String, JSONObject>()
        for (i in 0 until rows.length()) rows.optJSONObject(i)?.let { earlier[it.getString("id")] = it }
        // The fresh tail has newer tool results than older snapshots of overlapping entries.
        earlier.putAll(window); window = earlier
        val cursor = value.optString("next_cursor")
        require(pages == 0 || cursor.isEmpty() || cursor != next) { "历史游标未前进" }
        next = cursor; pages++; bytes += value.toString().toByteArray(Charsets.UTF_8).size
    }
    fun page(): JSONObject = JSONObject(requireNotNull(head).toString()).put("entries", JSONArray(window.values.toList())).put("next_cursor", next)
}
