package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AgentWorkspaceTest {
    private fun agent() = JSONObject().put("pane_id", "pane-1").put("terminal_id", "term-1")
        .put("generation", 4).put("agent_session_id", "session-1").put("cwd", "/work/one")
    private fun route(view: String = "history", generation: Long = 4): String {
        val fields = JSONArray().put(3).put("relay-1").put("primary").put("pane-1").put("term-1").put(generation).put("session-1")
        return "https://appassets.androidplatform.net/builds/test/index.html#$view=r3." + Base64.getUrlEncoder().withoutPadding().encodeToString(fields.toString().toByteArray())
    }
    @Test fun conversationAndTerminalIdentifyTheExactAgent() {
        for (view in listOf("history", "pane")) {
            val route = AgentRoute.parse(route(view))!!
            assertEquals("relay-1", route.relay); assertEquals("pane-1", route.pane)
            assertTrue(route.matches(agent()))
            assertEquals(route, AgentRoute.parse("https://appassets.androidplatform.net/" + route.target!!.historyFragment()))
            assertFalse(route.matches(agent().put("generation", 5)))
            assertFalse(route.matches(agent().put("terminal_id", "term-2")))
            assertFalse(route.matches(agent().put("agent_session_id", "session-2")))
        }
    }
    @Test fun statusUpdatesKeepWorkspaceButSessionOrDirectoryChangesInvalidateIt() {
        val original = WorkspaceBinding.fromAgent("relay-1", agent())
        assertEquals(original, WorkspaceBinding.fromAgent("relay-1", agent().put("status", "working").put("updated_at", 1234)))
        assertNotEquals(original, WorkspaceBinding.fromAgent("relay-1", agent().put("cwd", "/work/two")))
        assertNotEquals(original, WorkspaceBinding.fromAgent("relay-2", agent()))
        assertNotEquals(original, WorkspaceBinding.fromAgent("relay-1", agent().put("generation", 5)))
    }
    @Test fun invalidAndExternalRoutesCannotSelectAWorkspace() {
        assertNull(AgentRoute.parse(route().replace("appassets.androidplatform.net", "evil.example")))
        assertNull(AgentRoute.parse("https://appassets.androidplatform.net/#history=r3.bad"))
        assertNull(AgentRoute.parse("https://appassets.androidplatform.net/#settings"))
        assertNull(AgentRoute.parse(route(generation = -1)))
    }
}
