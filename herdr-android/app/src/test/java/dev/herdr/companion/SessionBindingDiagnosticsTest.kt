package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionBindingDiagnosticsTest {
    private fun agent() = JSONObject().put("agent", "codex").put("pane_id", "pane").put("terminal_id", "terminal")
        .put("generation", 2).put("binding_epoch", 3).put("binding_state", "ambiguous").put("agent_session_id", "")
    private fun response(agent: JSONObject) = JSONObject().put("schema_version", 1).put("available", true)
        .put("observation", "inventory_snapshot").put("target", AgentTarget.fromAgent("computer", agent).json())
        .put("binding", JSONObject().put("state", "ambiguous"))
        .put("details", JSONObject().put("future_evidence", JSONArray().put(JSONObject().put("kind", "relation").put("candidate_ids", JSONArray().put("root-a").put("root-b")))))

    @Test fun diagnosticReadPreservesFutureDetailsWithoutChoosingOrConfirming() {
        val a = agent(); val before = a.toString(); val data = response(a)
        data.getJSONObject("binding").put("state", "verified") // A diagnostic is never inventory authority.
        val text = SessionBindingDiagnostics.render("computer", a, data)
        assertTrue(text.contains("future_evidence")); assertTrue(text.contains("root-a")); assertTrue(text.contains("root-b"))
        assertTrue(text.contains("仅供查看")); assertEquals(before, a.toString())
        assertFalse(PrimarySessionBinding.writable(a)); assertEquals("", a.optString("agent_session_id"))
    }
    @Test fun commandIsAnExactTargetReadEvenWhenSessionIsUnconfirmed() {
        val a = agent(); val command = AgentProtocol.command("computer", a, SessionBindingDiagnostics.ACTION)
        assertEquals("get_session_binding_diagnostics", command.optString("type"))
        assertTrue(AgentProtocol.targetMatches("computer", a, command))
        assertFalse(command.has("candidate_id")); assertFalse(command.has("selected_session_id")); assertFalse(command.has("confirm"))
    }
    @Test fun staleDiagnosticCannotBeDisplayedForAnotherBinding() {
        val a = agent(); val data = response(a)
        data.getJSONObject("target").put("binding_epoch", 4)
        assertTrue(runCatching { SessionBindingDiagnostics.render("computer", a, data) }.isFailure)
    }
    @Test fun boundedSnapshotExplainsTruncationAndRejectsOversizedData() {
        val a = agent(); val data = response(a).put("truncated", true)
        assertTrue(SessionBindingDiagnostics.render("computer", a, data).contains("已截断"))
        data.put("details", JSONObject().put("large", "x".repeat(70 * 1024)))
        assertTrue(runCatching { SessionBindingDiagnostics.render("computer", a, data) }.isFailure)
    }
    @Test fun unsupportedProviderAndVersionRemainPassive() {
        val a = agent(); val data = response(a).put("schema_version", 2).put("available", false)
        assertTrue(SessionBindingDiagnostics.render("computer", a, data).contains("暂无绑定诊断"))
        data.put("schema_version", 0)
        assertTrue(runCatching { SessionBindingDiagnostics.render("computer", a, data) }.isFailure)
    }
}
