package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationRecentRecoveryTest {
    private fun entry(id: String, role: String = "assistant") = JSONObject().put("id", id).put("role", role).put("text", id)
    private fun page(cursor: String, vararg entries: JSONObject) = JSONObject().put("available", true).put("state", "ready").put("source_revision", "same").put("subscription_id", "live").put("sequence", 4).put("next_cursor", cursor).put("entries", JSONArray(entries.toList()))
    @Test fun cachedUserMessageDoesNotHideGapsBetweenLaterToolUpdates() {
        val state = ConversationState(); state.page(page("old", entry("user", "user"), entry("first"), entry("shell-a"), entry("shell-b")))
        val recovery = ConversationRecentRecovery(state)
        recovery.accept(page("middle", entry("shell-b"), entry("latest")))
        assertTrue("Old cached user must not stop catch-up", recovery.needsMore)
        recovery.accept(page("start", entry("image"), entry("commentary"), entry("shell-b")))
        assertTrue(recovery.needsMore)
        recovery.accept(page("older-turn", entry("user", "user"), entry("first"), entry("shell-a")))
        assertFalse(recovery.needsMore); assertFalse(recovery.incomplete)
        assertTrue(state.page(recovery.page(), preserveLoaded = true))
        assertEquals(listOf("user", "first", "shell-a", "image", "commentary", "shell-b", "latest"), state.entries.keys.toList())
        assertEquals("live", state.subscription); assertEquals(4, state.sequence.toInt())
    }
    @Test fun recoveryRetainsEarlierLoadedPagesAndCursor() {
        val state = ConversationState(); state.page(page("before", entry("known"))); state.page(page("oldest", entry("old-user", "user")), true)
        val recovery = ConversationRecentRecovery(state); recovery.accept(page("missing", entry("new")))
        recovery.accept(page("before", entry("turn-user", "user"), entry("known"), entry("middle")))
        state.page(recovery.page(), preserveLoaded = true)
        assertEquals(listOf("old-user", "turn-user", "known", "middle", "new"), state.entries.keys.toList())
        assertEquals("oldest", state.next)
    }
    @Test fun noOverlapKeepsChronologyAndRecoveryHasAnExplicitBound() {
        val state = ConversationState(); state.page(page("old", entry("before")))
        val recovery = ConversationRecentRecovery(state, maxPages = 1)
        recovery.accept(page("gap", entry("later")))
        assertFalse(recovery.needsMore); assertTrue(recovery.incomplete)
        state.page(recovery.page(), preserveLoaded = true)
        assertEquals(listOf("before", "later"), state.entries.keys.toList())
    }
    @Test fun sourceChangeAndRepeatedCursorCannotMergeDifferentSnapshots() {
        val state = ConversationState(); val recovery = ConversationRecentRecovery(state)
        recovery.accept(page("next", entry("one")))
        assertThrows(IllegalArgumentException::class.java) { recovery.accept(page("else", entry("two")).put("source_revision", "changed")) }
        assertThrows(IllegalArgumentException::class.java) { recovery.accept(page("next", entry("two"))) }
    }
}
