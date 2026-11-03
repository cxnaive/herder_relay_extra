package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationPagingTest {
    @Test fun preparationKeepsOlderIntentAndUsesReturnedCursor() {
        val request = ConversationHistoryRequest(true, "recent-boundary", false)
        val retry = request.afterPreparing(JSONObject().put("state", "preparing").put("next_cursor", "snapshot-job"))
        assertTrue(retry.older)
        assertEquals("snapshot-job", retry.payload().getString("cursor"))
        assertFalse(retry.payload().getBoolean("observe"))
        assertEquals("snapshot-job", retry.afterPreparing(JSONObject().put("state", "preparing")).cursor)
    }
    @Test fun repeatedPreparationNeverFallsBackToTheRecentPage() {
        var request = ConversationHistoryRequest(true, "boundary", false)
        repeat(5) { request = request.afterPreparing(JSONObject().put("state", "preparing")) }
        assertTrue(request.older); assertEquals("boundary", request.cursor)
    }
    @Test fun olderSnapshotAddsUserMessagesWithoutDiscardingRecentToolsOrLiveSubscription() {
        val state = ConversationState()
        assertTrue(state.page(JSONObject().put("available", true).put("source_revision", "revision")
            .put("subscription_id", "live").put("sequence", 7).put("next_cursor", "boundary")
            .put("entries", JSONArray().put(JSONObject().put("id", "tool-new").put("role", "assistant").put("text", "")))))
        assertTrue(state.page(JSONObject().put("available", true).put("source_revision", "revision").put("mode", "snapshot")
            .put("entries", JSONArray().put(JSONObject().put("id", "question").put("role", "user").put("text", "where is the result?"))), true))
        assertEquals(listOf("question", "tool-new"), state.entries.keys.toList())
        assertEquals("live", state.subscription); assertEquals(7L, state.sequence)
        assertTrue(state.next.isEmpty())
    }
    @Test fun staleSnapshotCannotMixDifferentConversationRevisions() {
        val state = ConversationState()
        state.page(JSONObject().put("available", true).put("source_revision", "new").put("entries", JSONArray()))
        assertFalse(state.page(JSONObject().put("available", true).put("source_revision", "old")
            .put("entries", JSONArray().put(JSONObject().put("id", "stale"))), true))
        assertFalse(state.entries.containsKey("stale"))
    }
}
