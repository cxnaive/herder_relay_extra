package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HistoryReliabilityTest {
    private fun page(ids: List<String>, cursor: String = "older", revision: String = "rev", subscription: String = "live") = JSONObject()
        .put("available", true).put("state", "ready").put("source_revision", revision).put("next_cursor", cursor)
        .put("subscription_id", subscription).put("sequence", 5).put("entries", JSONArray(ids.map { JSONObject().put("id", it).put("text", it).put("role", "assistant") }))
    private fun binding(session: String = "thread", generation: Long = 1) = WorkspaceBinding(AgentTarget("relay", "primary", "pane", "terminal", generation, session), "/work")
    @Test fun recentResubscriptionPreservesLoadedHistoryAndOldestCursor() {
        val state = ConversationState(); state.page(page(listOf("recent"), "near"))
        state.page(page(listOf("old1", "old2"), "far"), true)
        assertTrue(state.page(page(listOf("recent", "new"), "near-new", subscription = "new-live"), preserveLoaded = true))
        assertEquals(listOf("old1", "old2", "recent", "new"), state.entries.keys.toList()); assertEquals("far", state.next)
        assertEquals("new-live", state.subscription)
    }
    @Test fun differentSourceRevisionDropsStaleCachedPages() {
        val state = ConversationState(); state.page(page(listOf("recent"))); state.page(page(listOf("old"), "far"), true)
        state.page(page(listOf("replacement"), "replacement-cursor", "different"), preserveLoaded = true)
        assertEquals(listOf("replacement"), state.entries.keys.toList()); assertEquals("replacement-cursor", state.next)
        assertFalse(state.loadedOlder)
    }
    @Test fun memoryCacheIsExactBoundedDeepCopiedAndNeverRestoresSubscription() {
        var now = 0L; val cache = ConversationMemoryCache(4096) { now }; val state = ConversationState()
        state.page(page(listOf("recent"))); state.page(page(listOf("old"), "far"), true); cache.put(binding(), state)
        state.entries["old"]!!.put("text", "modified outside cache")
        val restored = cache.get(binding())!!
        assertEquals("old", restored.entries["old"]!!.getString("text")); assertEquals("", restored.subscription); assertFalse(restored.loadedOlder); assertEquals("", restored.next)
        assertNull(cache.get(binding("other-thread"))); assertNull(cache.get(binding(generation = 2)))
        now = 600001; assertNull(cache.get(binding()))
        cache.put(binding(), state); cache.clear(); assertNull(cache.get(binding()))
    }
    @Test fun cacheBoundsAndPreparingRepliesDoNotDiscardVisibleHistory() {
        val state = ConversationState(); state.page(page(listOf("visible")))
        assertFalse(state.page(JSONObject().put("available", true).put("state", "preparing")))
        assertEquals(listOf("visible"), state.entries.keys.toList())
        val tiny = ConversationMemoryCache(16) { 0 }; tiny.put(binding(), state); assertNull(tiny.get(binding()))
        val cache = ConversationMemoryCache(4096) { 0 }
        for (i in 0..4) cache.put(binding("thread-$i"), state)
        assertNull(cache.get(binding("thread-0"))); assertNotNull(cache.get(binding("thread-4")))
        assertEquals(24, ConversationHistoryRequest(true, "cursor", false).payload().getInt("limit"))
    }
    @Test fun fragmentedMessageMayTakeOverThirtySecondsWhileStillMakingProgress() {
        var now = 0L; val incoming = RelayChunks { now }
        val bytes = ByteArray(100) { it.toByte() }; val frames = RelayChunks.split(bytes, 16)
        var result: ByteArray? = null
        for (frame in frames) { result = incoming.push(frame); now += 10_000_000_000L }
        assertTrue(now > 30_000_000_000L); assertArrayEquals(bytes, result)
    }
    @Test fun fragmentedMessageStillRejectsStallsTotalTimeoutAndMalformedFrames() {
        var now = 0L; var incoming = RelayChunks { now }; val frames = RelayChunks.split(ByteArray(100), 16)
        incoming.push(frames[0]); now = 30_000_000_000L
        assertThrows(RelayChunkTimeout::class.java) { incoming.push(frames[1]) }
        now = 0; incoming = RelayChunks { now }; val many = RelayChunks.split(ByteArray(1000), 16)
        for (i in 0..5) { now = i * 20_000_000_000L; incoming.push(many[i]) }
        now = 120_000_000_000L; assertThrows(RelayChunkTimeout::class.java) { incoming.push(many[6]) }
        assertThrows(IllegalArgumentException::class.java) { RelayChunks().push(byteArrayOf(9, 1)) }
    }
    @Test fun pureIpPairingDoesNotRequireDomainButStillRequiresTls() {
        val link = PairingLink.parse("https://192.0.2.10:8443/#setup=0123456789abcdef0123456789abcdef")
        assertEquals("wss://192.0.2.10:8443", link.relay.getString("url"))
        assertEquals("wss://192.0.2.10:8443/ws", NativeRelayConnection.directSocketUrl(link.relay.getString("url")))
        assertEquals("wss://192.0.2.10:8443/ws", NativeRelayConnection.directSocketUrl("wss://192.0.2.10:8443/ws"))
        assertThrows(IllegalArgumentException::class.java) { PairingLink.parse("http://192.0.2.10/#setup=0123456789abcdef0123456789abcdef") }
        assertThrows(IllegalArgumentException::class.java) { PairingLink.parse("https://192.0.2.10/#setup=0123456789abcdef0123456789abcdef&relay=ws%3A%2F%2F192.0.2.10") }
    }

}
