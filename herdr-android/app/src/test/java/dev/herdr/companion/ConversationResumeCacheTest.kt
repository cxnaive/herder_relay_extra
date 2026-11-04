package dev.herdr.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationResumeCacheTest {
    private fun page(revision: String, id: String, text: String) = JSONObject().put("available", true).put("source_revision", revision)
        .put("next_cursor", "old-page-cursor").put("subscription_id", "old-stream").put("sequence", 10)
        .put("entries", org.json.JSONArray().put(JSONObject().put("id", id).put("text", text)))
    private fun agent(id: String) = JSONObject().put("pane_id", "p").put("terminal_id", "t").put("generation", 1).put("agent_session_id", id).put("cwd", "/project")
    @Test fun cachedContentDoesNotKeepOldCursorOrSubscriptionAndIsReplacedByFreshRead() {
        val cache = ConversationMemoryCache(now = { 0L })
        val scope = WorkspaceBinding.fromAgent("computer", agent("a"))
        val old = ConversationState().also { it.page(page("same-revision", "old", "cached text")) }
        cache.put(scope, old)
        val restored = cache.get(scope)!!
        assertEquals("cached text", restored.entries["old"]!!.getString("text"))
        assertEquals("", restored.next); assertEquals("", restored.subscription); assertEquals(0L, restored.sequence)
        assertTrue(restored.page(page("same-revision", "new", "fresh result"), preserveLoaded = true))
        assertFalse(restored.entries.containsKey("old")); assertEquals("fresh result", restored.entries["new"]!!.getString("text"))
        assertNull(cache.get(WorkspaceBinding.fromAgent("computer", agent("b"))))
        assertNotNull(cache.get(scope))
    }
    @Test fun failedRefreshPreservesOnlyDisplayedContentAndOldStreamCannotAppend() {
        val state = ConversationState().also { it.page(page("r", "old", "cached text")) }
        state.prepareRefresh()
        assertFalse(state.page(JSONObject().put("available", false)))
        assertTrue(state.entries.containsKey("old")); assertEquals("", state.next)
        assertFalse(state.update(JSONObject().put("kind", "append").put("source_revision", "r").put("subscription_id", "old-stream").put("sequence", 11)))
        assertTrue(state.page(page("new-source", "fresh", "resumed segment"), preserveLoaded = true))
        assertEquals(setOf("fresh"), state.entries.keys)
    }
}
