package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentPresentationTest {
    private fun agent(id: String, workspace: String, tab: Int, label: String) = JSONObject()
        .put("pane_id", id).put("workspace_id", workspace).put("tab_id", "tab-$tab")
        .put("tab_number", tab).put("tab_label", label).put("agent", "codex")
        .put("name", "Codex").put("cwd", "/work/project")
    @Test fun tabLabelsTakePriorityOverAgentNames() {
        assertEquals("训练实验", AgentPresentation.title(agent("a", "w", 1, "训练实验")))
        assertEquals("Codex", AgentPresentation.title(agent("a", "w", 1, "")))
    }
    @Test fun separatesWorkspacesEvenWhenTheyShareTheSameDirectoryAndName() {
        val rows = JSONArray().put(agent("a", "first", 2, "第二个任务")).put(agent("b", "first", 1, "第一个任务"))
            .put(agent("c", "second", 1, "第三个任务"))
        val workspaces = JSONArray().put(JSONObject().put("workspace_id", "first").put("label", "论文实验").put("number", 1))
            .put(JSONObject().put("workspace_id", "second").put("label", "论文实验").put("number", 2))
        val groups = AgentPresentation.groups(mapOf("laptop" to rows), mapOf("laptop" to workspaces))
        assertEquals(2, groups.size); assertEquals("论文实验", groups[0].title)
        assertEquals(listOf("b", "a"), groups[0].agents.map { it.getString("pane_id") })
        assertEquals("second", groups[1].id)
    }
    @Test fun computerAndServerSessionsCannotMerge() {
        val rows = JSONArray().put(agent("a", "w", 1, "任务"))
            .put(agent("b", "w", 1, "任务").put("server_session_id", "other"))
        val groups = AgentPresentation.groups(mapOf("one" to rows, "two" to rows), emptyMap())
        assertEquals(4, groups.size)
    }
    @Test fun olderRelayWithoutWorkspaceMetadataKeepsAUsableTitle() {
        val groups = AgentPresentation.groups(mapOf("one" to JSONArray().put(agent("a", "", 1, "任务"))), emptyMap())
        assertEquals("project", groups.single().title)
    }
}
