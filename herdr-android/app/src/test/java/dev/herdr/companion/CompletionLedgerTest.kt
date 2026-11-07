package dev.herdr.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompletionLedgerTest {
    private fun sample(status: String, turn: String = "turn-a", source: String = "codex_runtime") = JSONObject().put("status", status).put("turn_id", turn).put("source", source).put("evidence", "runtime_turn").put("completion_confirmed", status == "done")
    @Test fun firstSnapshotIsBaselineAndEachCompletedTurnAlertsOnceAcrossRestart() {
        val ledger = CompletionLedger()
        assertFalse(ledger.observe("agent", sample("done"), 1000))
        assertFalse(ledger.observe("agent", sample("working", "turn-b"), 2000))
        assertTrue(ledger.observe("agent", sample("done", "turn-b"), 3000))
        assertFalse(ledger.observe("agent", sample("done", "turn-b"), 4000))
        val restarted = CompletionLedger(ledger.encode())
        assertFalse(restarted.observe("agent", sample("done", "turn-b"), 5000))
        // An unobserved whole turn cannot prove that the current task stopped.
        assertFalse(restarted.observe("agent", sample("done", "turn-c"), 6000))
    }
    @Test fun staleWorkingObservationCannotRetriggerDeliveredCompletion() {
        val ledger = CompletionLedger()
        ledger.observe("agent", sample("working"), 1000)
        assertTrue(ledger.observe("agent", sample("done"), 2000))
        ledger.observe("agent", sample("working"), 3000)
        assertFalse(ledger.observe("agent", sample("done"), 4000))
        assertFalse(ledger.observe("different-agent", sample("done"), 4000))
    }
    @Test fun approvalFailureInterruptionMissingIdentityAndDisconnectAreNotCompletion() {
        val ledger = CompletionLedger()
        assertFalse(ledger.observe("agent", sample("working"), 1000))
        for (status in listOf("blocked", "unknown", "interrupted", "failed", "stopped", "idle")) assertFalse(ledger.observe("agent", sample(status), 2000))
        assertFalse(ledger.observe("agent", sample("done", ""), 3000))
        assertFalse(ledger.observe("agent", sample("done"), 4000))
    }
    @Test fun genericAgentsRequireObservedActiveToIdleTransition() {
        val ledger = CompletionLedger()
        assertFalse(ledger.observe("agent", sample("idle", "", "herdr"), 1000))
        assertFalse(ledger.observe("agent", sample("working", "", "herdr"), 2000))
        assertFalse(ledger.observe("agent", sample("blocked", "", "herdr"), 3000))
        assertTrue(ledger.observe("agent", sample("idle", "", "herdr"), 4000))
        assertFalse(ledger.observe("agent", sample("idle", "", "herdr"), 5000))
    }
    @Test fun muteReenableAndExpiredBaselinesDoNotNotifyOldCompletions() {
        val ledger = CompletionLedger()
        ledger.observe("agent", sample("working"), 1000); ledger.forget("agent")
        assertFalse(ledger.observe("agent", sample("done"), 2000))
        assertFalse(CompletionLedger("not-json").observe("agent", sample("done"), 2000))
        assertFalse(ledger.observe("agent", sample("done", "much-later"), 32L * 24 * 60 * 60 * 1000))
    }
    @Test fun repeatedIdenticalSamplesDoNotRewritePersistentLedgerEveryPoll() {
        val ledger = CompletionLedger(); ledger.observe("a", sample("working"), 1000); ledger.observe("b", sample("working"), 1000)
        val serialized = ledger.encode()
        repeat(5) { ledger.observe("a", sample("working"), 2000); ledger.observe("b", sample("working"), 2000) }
        assertEquals(serialized, ledger.encode())
    }
    @Test fun codexRequiresSameObservedTurnExplicitProofAndFreshConnection() {
        for (evidence in listOf("runtime_turn", "rollout_lifecycle")) {
            val ledger = CompletionLedger()
            fun state(status: String, id: String = "a") = sample(status, id).put("evidence", evidence)
            ledger.observe("a", state("working"), 1000)
            assertFalse(ledger.observe("a", state("done", "b"), 2000))
            ledger.observe("a", state("working"), 3000)
            assertFalse(ledger.observe("a", state("done").put("completion_confirmed", false), 4000))
            ledger.observe("b", state("working"), 5000)
            ledger.observe("b", state("unknown"), 6000)
            assertFalse(ledger.observe("b", state("done"), 7000))
            ledger.observe("c", state("working"), 8000); ledger.invalidate("c")
            assertFalse(ledger.observe("c", state("done"), 9000))
            ledger.observe("d", state("working"), 10000)
            assertFalse(ledger.observe("d", state("done"), 101000))
            ledger.observe("e", state("working"), 11000)
            assertFalse(CompletionLedger(ledger.encode()).observe("e", state("done"), 12000))
            ledger.observe("f", state("working"), 13000)
            assertTrue(ledger.observe("f", state("done"), 14000))
        }
    }
    @Test fun pendingCompletionCannotInventAnObservedActiveTurn() {
        val ledger = CompletionLedger()
        val pending = sample("working").put("completion_pending", true)
        ledger.observe("new", pending, 1000)
        assertFalse(ledger.observe("new", sample("done"), 2000))
        ledger.observe("known", sample("working"), 1000)
        ledger.observe("known", pending, 2000)
        assertTrue(ledger.observe("known", sample("done"), 3000))
    }
    private fun agent() = JSONObject().put("agent_session_id", "session-a").put("terminal_id", "terminal-a").put("workspace_id", "workspace-a").put("pane_id", "pane-a").put("generation", 1)
    @Test fun identitySurvivesRenameAndRelayGenerationButNotAnotherSessionOrWorkspace() {
        val a = agent(); val key = MonitorIdentity.key("relay", a)
        assertEquals(key, MonitorIdentity.key("relay", JSONObject(a.toString()).put("generation", 50).put("tab_label", "renamed").put("pane_id", "new-pane")))
        for ((field, value) in listOf("agent_session_id" to "other", "workspace_id" to "other", "server_session_id" to "other")) assertNotEquals(key, MonitorIdentity.key("relay", JSONObject(a.toString()).put(field, value)))
        assertNotEquals(key, MonitorIdentity.key("other-relay", a))
        assertTrue(MonitorIdentity.matchesRoute("relay", a, MonitorIdentity.route("relay", a)))
        assertFalse(MonitorIdentity.matchesRoute("relay", JSONObject(a.toString()).put("agent_session_id", "replacement"), MonitorIdentity.route("relay", a)))
    }
}
