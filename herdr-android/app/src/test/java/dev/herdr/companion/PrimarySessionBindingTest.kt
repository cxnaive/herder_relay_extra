package dev.herdr.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PrimarySessionBindingTest {
    private fun agent(epoch: Long = 1, state: String = "verified") = JSONObject()
        .put("pane_id", "p").put("terminal_id", "t").put("agent_session_id", "session")
        .put("generation", 3).put("cwd", "/project").put("binding_epoch", epoch).put("binding_state", state)
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
    @Test fun firstProofCanOpenTheWaitingPageButCannotRebindAnExistingSession() {
        val waiting = agent(state = "verifying").put("agent_session_id", "")
        val first = agent(2).put("generation", 4)
        assertTrue(PrimarySessionBinding.initialDiscovery("computer", waiting, first))
        assertFalse(PrimarySessionBinding.initialDiscovery("computer", agent(), first))
        assertFalse(PrimarySessionBinding.initialDiscovery("computer", waiting, first.put("cwd", "/other")))
        assertFalse(PrimarySessionBinding.initialDiscovery("computer", waiting, agent(2).put("generation", 4).put("terminal_id", "other")))
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
    @Test fun unconfirmedAndManuallySelectedHistoryNeverAuthorizesInput() {
        for (state in listOf("verifying", "stale", "ambiguous", "conflict", "ended")) {
            assertFalse(PrimarySessionBinding.writable(agent(state = state)))
            assertFalse(PrimarySessionBinding.readable(agent(state = state)))
            assertTrue(PrimarySessionBinding.label(agent(state = state)).isNotBlank())
        }
        assertTrue(PrimarySessionBinding.readable(agent(state = "pinned_history")))
        assertFalse(PrimarySessionBinding.writable(agent(state = "pinned_history")))
        assertFalse(PrimarySessionBinding.live(agent(state = "pinned_history")))
        assertTrue(PrimarySessionBinding.writable(agent()))
    }
}
