package dev.herdr.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PrimarySessionBindingTest {
    private fun agent(epoch: Long = 1, state: String = "verified") = JSONObject()
        .put("agent", "codex").put("pane_id", "p").put("terminal_id", "t").put("agent_session_id", "session")
        .put("generation", 3).put("cwd", "/project").put("binding_epoch", epoch).put("binding_state", state)
    @Test fun standaloneFailuresDoNotSuggestAddingNoDaemonAgain() {
        for (reason in listOf("standalone_runtime_unavailable", "standalone_metadata_unavailable", "standalone_ambiguous", "standalone_metadata_conflict", "standalone_ancestry_unavailable")) {
            val unresolved = agent(state = "ambiguous").put("binding_reason", reason)
            val label = PrimarySessionBinding.label(unresolved)
            assertFalse(label.contains("可尝试 codex --no-daemon"))
            assertTrue(label.contains("独立") || label.contains("已使用"))
            assertFalse(PrimarySessionBinding.writable(unresolved))
        }
        assertTrue(PrimarySessionBinding.label(agent(state = "ambiguous").put("binding_reason", "standalone_ambiguous")).contains("旧 hook"))
    }
    @Test fun availabilityIsNotHistoryOrDraftIdentity() {
        val old = WorkspaceBinding.fromAgent("computer", agent())
        val recovered = WorkspaceBinding.fromAgent("computer", agent(3))
        assertNotEquals(old, recovered)
        assertEquals(old.identity(), recovered.identity())
        assertEquals(old.storageKey(), recovered.storageKey())
        assertEquals("WorkspaceBinding(target=AgentTarget(relay=computer, server=primary, pane=p, terminal=t, generation=3, session=session), cwd=/project)", old.storageKey())
        assertTrue(PrimarySessionBinding.sameIdentity("computer", agent(), agent(2, "stale")))
        assertFalse(PrimarySessionBinding.sameIdentity("computer", agent(), agent().put("generation", 4)))
        assertFalse(PrimarySessionBinding.sameIdentity("computer", agent(), agent().put("agent_session_id", "other")))
    }
    @Test fun followsFirstFinalRelayIdentityWithoutRunningLocalDiscovery() {
        val waiting = agent(state = "verifying").put("agent_session_id", "")
        val first = agent(2).put("generation", 4)
        assertTrue(PrimarySessionBinding.relayReplacement("computer", waiting, first))
        assertFalse(PrimarySessionBinding.relayReplacement("computer", agent(), first))
        assertFalse(PrimarySessionBinding.relayReplacement("computer", waiting, first.put("cwd", "/other")))
        assertFalse(PrimarySessionBinding.relayReplacement("computer", waiting, agent(2).put("generation", 4).put("terminal_id", "other")))
    }
    @Test fun repliesAndCommandsAreFencedByAvailabilityEpoch() {
        val command = AgentProtocol.command("computer", agent(), "send_terminal_text")
        assertEquals(1L, command.getJSONObject("target").getLong("binding_epoch"))
        assertTrue(AgentProtocol.targetMatches("computer", agent(), command))
        assertFalse(AgentProtocol.targetMatches("computer", agent(3), command))
        val route = AgentRoute.parse("https://appassets.androidplatform.net/" + AgentTarget.fromAgent("computer", agent()).historyFragment())!!
        assertTrue(route.matches(agent(3)))
    }
    @Test fun cachedHistorySurvivesRecoveryButDoesNotCrossComputersOrSessions() {
        val cache = ConversationMemoryCache(now = { 0L })
        val history = ConversationState()
        history.page(JSONObject("""{"available":true,"source_revision":"r","entries":[{"id":"message","text":"cached"}]}"""))
        cache.put(WorkspaceBinding.fromAgent("computer", agent()), history)
        assertNotNull(cache.get(WorkspaceBinding.fromAgent("computer", agent(2, "stale"))))
        assertNotNull(cache.get(WorkspaceBinding.fromAgent("computer", agent(3))))
        assertNull(cache.get(WorkspaceBinding.fromAgent("other-computer", agent(3))))
        assertNull(cache.get(WorkspaceBinding.fromAgent("computer", agent(3).put("agent_session_id", "other"))))
    }
    @Test fun onlyVerifiedCodexPrimaryPermitsReadingOrInput() {
        for (state in listOf("verifying", "stale", "conflict", "history_only", "pinned_history")) {
            assertFalse(PrimarySessionBinding.writable(agent(state = state)))
            assertFalse(PrimarySessionBinding.readable(agent(state = state)))
        }
        assertTrue(PrimarySessionBinding.writable(agent()))
    }
    @Test fun unresolvedPrimarySuggestsNoDaemonWithoutPromisingSuccess() {
        val unresolved = agent(state = "ambiguous").put("binding_reason", "runtime_ambiguous_no_report")
        assertTrue(PrimarySessionBinding.label(unresolved).contains("--no-daemon"))
        assertTrue(PrimarySessionBinding.label(unresolved).contains("不保证修复"))
        val conflict = agent(state = "conflict").put("binding_reason", "runtime_metadata_conflict")
        assertFalse(PrimarySessionBinding.label(conflict).contains("--no-daemon"))
        assertFalse(PrimarySessionBinding.label(conflict).contains("升级"))
    }
    @Test fun otherProvidersNeverReceiveCodexDiscoveryAdvice() {
        for (kind in listOf("claude", "opencode", "gemini", "kimi", "pi")) {
            val other = agent(state = "ambiguous").put("agent", kind)
            assertEquals("", PrimarySessionBinding.label(other))
            assertTrue(PrimarySessionBinding.writable(other))
            assertTrue(PrimarySessionBinding.readable(other))
        }
    }
    @Test fun relayInventoryCanReplaceOnlyTheSameTerminalAndNewGeneration() {
        val before = agent()
        val next = agent(2).put("agent_session_id", "other").put("generation", 4)
        assertTrue(PrimarySessionBinding.relayReplacement("computer", before, next))
        assertFalse(PrimarySessionBinding.relayReplacement("computer", before, agent(2)))
        assertFalse(PrimarySessionBinding.relayReplacement("computer", before, JSONObject(next.toString()).put("terminal_id", "other-terminal")))
        assertFalse(PrimarySessionBinding.relayReplacement("computer", before, JSONObject(next.toString()).put("generation", 3)))
        assertFalse(PrimarySessionBinding.relayReplacement("computer", before, JSONObject(next.toString()).put("cwd", "/other")))
        assertTrue(PrimarySessionBinding.relayReplacement("computer", before, JSONObject(next.toString()).put("agent_session_id", "").put("binding_state", "verifying")))
        assertFalse(AgentProtocol.targetMatches("computer", next, AgentProtocol.command("computer", before, "get_conversation_history")))
    }
}
