package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentLaunchDefaultsTest {
    private val title = "tui.terminal_title=[\"thread-id\",\"thread-name\"]"
    @Test fun displaysReportedDefaultsWithoutAddingThemToUserPayload() {
        val defaults = JSONObject().put("codex", JSONArray().put("-c").put(title))
        assertEquals(listOf("-c", title), AgentLaunchArguments.defaultArgs(defaults, "codex"))
        assertTrue(AgentLaunchArguments.defaultLabel(defaults, "codex").contains("-c '$title'"))
        assertTrue(AgentLaunchArguments.defaultLabel(defaults, "codex").contains("无需手填"))
        val workspace = JSONObject().put("workspace_id", "w1").put("cwd", "/work/project")
        val payload = AgentManagement.launchPayload(workspace, "codex", "writer", "", "", true)
        assertFalse(payload.has("agent_args"))
        assertEquals("", AgentLaunchArguments.defaultLabel(defaults, "claude"))
    }
    @Test fun absentMalformedAndUnboundedDefaultsAreNotInvented() {
        for (bad in listOf<Any>("not-array", JSONArray().put(1), JSONArray().put("a\nb"), JSONArray(List(65) { "a" }), JSONArray().put("x".repeat(8193)))) {
            assertEquals("", AgentLaunchArguments.defaultLabel(JSONObject().put("codex", bad), "codex"))
        }
        assertEquals("", AgentLaunchArguments.defaultLabel(null, "codex"))
        assertEquals("", AgentLaunchArguments.defaultLabel(JSONObject(), "codex"))
    }
    @Test fun onlyVerifiedCodexIdentityIsDisplayed() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val agent = JSONObject().put("agent", "codex").put("binding_state", "verified").put("agent_session_id", id)
        assertTrue(PrimarySessionBinding.label(agent).contains(id))
        assertTrue(PrimarySessionBinding.terminalLabel(agent).contains(id))
        agent.put("binding_state", "verifying").put("binding_reason", "standalone_metadata_unavailable")
        assertFalse(PrimarySessionBinding.label(agent).contains(id))
        assertTrue(PrimarySessionBinding.label(agent).contains("首轮"))
        agent.put("binding_reason", "terminal_title_invalid")
        assertTrue(PrimarySessionBinding.label(agent).contains("标题"))
        agent.put("binding_reason", "runtime_unavailable")
        assertTrue(PrimarySessionBinding.label(agent).contains("不代表启动参数未附加"))
        agent.put("binding_reason", "").put("binding_state", "verified").put("agent_session_id", "bad\nID")
        assertFalse(PrimarySessionBinding.label(agent).contains("bad"))
    }
}
