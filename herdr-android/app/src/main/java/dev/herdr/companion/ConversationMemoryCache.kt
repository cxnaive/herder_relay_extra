package dev.herdr.companion

/** Process-memory only: no plaintext transcripts or expired subscriptions are saved to disk. */
class ConversationMemoryCache(private val maxBytes: Int = 8 * 1024 * 1024, private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private data class Item(val json: String, val bytes: Int, val expires: Long)
    private val items = LinkedHashMap<WorkspaceBinding, Item>(4, .75f, true)
    private var bytes = 0
    fun put(binding: WorkspaceBinding, state: ConversationState) {
        items.remove(binding)?.let { bytes -= it.bytes }
        if (binding.target.session.isBlank() || state.entries.isEmpty() || state.entries.size > 2000) return
        val json = state.cachedPage().toString(); val size = json.toByteArray(Charsets.UTF_8).size
        if (size > maxBytes) return
        items[binding] = Item(json, size, now() + 10 * 60 * 1000); bytes += size
        while (bytes > maxBytes || items.size > 4) { val first = items.entries.first(); bytes -= first.value.bytes; items.remove(first.key) }
    }
    fun get(binding: WorkspaceBinding): ConversationState? {
        val item = items[binding] ?: return null
        if (item.expires <= now()) { items.remove(binding); bytes -= item.bytes; return null }
        return ConversationState().also { it.restoreCached(org.json.JSONObject(item.json)) }
    }
    fun clear() { items.clear(); bytes = 0 }
}
