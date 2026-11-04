package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentListPresentationTest {
    private fun agent(name: String, order: Int = 1, server: String = "primary") = JSONObject()
        .put("workspace_id", "shared").put("server_session_id", server).put("cwd", "/work/shared")
        .put("tab_label", name).put("tab_id", name).put("pane_id", name).put("tab_order", order)
    private fun workspace(id: String = "shared", server: String = "primary") = JSONObject()
        .put("workspace_id", id).put("server_session_id", server).put("cwd", "/work/$id").put("label", "同名项目")
    private val relays get() = JSONArray().put(JSONObject().put("id", "z").put("label", "训练机 A"))
        .put(JSONObject().put("id", "a").put("label", "训练机 B"))

    @Test fun pairedComputerOrderIsStableAndSameWorkspacesStaySeparate() {
        val inventory = mapOf("z" to JSONArray().put(agent("second", 2)).put(agent("first", 1)), "a" to JSONArray().put(agent("first")))
        val result = AgentListPresentation.computers(relays, inventory, emptyMap(), "")
        assertEquals(listOf("z", "a"), result.map { it.id })
        assertEquals(listOf(2, 1), result.map { it.totalAgents })
        assertEquals(listOf("first", "second"), result[0].workspaces.single().agents.map { AgentPresentation.title(it) })
        assertNotEquals(AgentGroupExpansion.workspaceKey(result[0].workspaces.single().group), AgentGroupExpansion.workspaceKey(result[1].workspaces.single().group))
    }
    @Test fun pairedOfflineComputersAndEmptyWorkspacesRemainVisible() {
        val result = AgentListPresentation.computers(relays, emptyMap(), mapOf("z" to JSONArray().put(workspace("empty"))), "")
        assertEquals(2, result.size)
        assertEquals(1, result[0].totalWorkspaces)
        assertEquals(0, result[0].totalAgents)
        assertTrue(result[1].workspaces.isEmpty())
    }
    @Test fun searchMatchesAnAgentAndRetainsItsComputerAndWorkspace() {
        val result = AgentListPresentation.computers(relays, mapOf("z" to JSONArray().put(agent("writer")).put(agent("trainer")),
            "a" to JSONArray().put(agent("reader"))), emptyMap(), "  WRITER  ")
        assertEquals(1, result.size)
        assertEquals("z", result.single().id)
        assertEquals(2, result.single().totalAgents)
        assertEquals(1, result.single().matchingAgents)
        assertEquals("writer", AgentPresentation.title(result.single().workspaces.single().agents.single()))
    }
    @Test fun searchingAComputerIncludesAllItsWorkspacesButNotTheOtherComputer() {
        val workspaces = mapOf("z" to JSONArray().put(workspace()).put(workspace("empty")), "a" to JSONArray().put(workspace()))
        val result = AgentListPresentation.computers(relays, mapOf("z" to JSONArray().put(agent("writer"))), workspaces, "训练机 A")
        assertEquals(listOf("z"), result.map { it.id })
        assertEquals(2, result.single().workspaces.size)
    }
    @Test fun workspacePathsAndNamesAreSearchableIncludingEmptyWorkspaces() {
        val workspaces = mapOf("z" to JSONArray().put(workspace("empty")))
        for (query in listOf("/work/empty", "同名项目")) {
            val result = AgentListPresentation.computers(relays, emptyMap(), workspaces, query)
            assertEquals("empty", result.single().workspaces.single().group.id)
        }
        assertTrue(AgentListPresentation.computers(relays, emptyMap(), workspaces, "not-found").isEmpty())
    }
    @Test fun serviceSessionsStaySeparateWithinAComputer() {
        val result = AgentListPresentation.computers(relays, mapOf("z" to JSONArray().put(agent("writer")).put(agent("other", server = "secondary"))), emptyMap(), "")
        assertEquals(2, result[0].workspaces.size)
        assertNotEquals(AgentGroupExpansion.workspaceKey(result[0].workspaces[0].group), AgentGroupExpansion.workspaceKey(result[0].workspaces[1].group))
    }
    @Test fun receivedInventoryIsNotDroppedWhilePairingMetadataIsUnavailable() {
        val result = AgentListPresentation.computers(JSONArray(), mapOf("z" to JSONArray().put(agent("writer"))), emptyMap(), "")
        assertEquals("z", result.single().id)
        assertEquals(1, result.single().totalAgents)
    }
}
