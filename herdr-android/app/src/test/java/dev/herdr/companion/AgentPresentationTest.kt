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
    private fun workspace(id: String, label: String, number: Int = 1, server: String = "primary") = JSONObject()
        .put("workspace_id", id).put("label", label).put("number", number).put("server_session_id", server)
        .put("cwd", "/work/project")
    @Test fun selectedWorkspaceTitleDoesNotDependOnGroupOrderAndEmptyWorkspacesRemainVisible() {
        val selected = agent("b", "selected", 1, "Selected task")
        val workspaces = mapOf("computer" to JSONArray().put(workspace("empty", "Empty first", 0))
            .put(workspace("unrelated", "Unrelated project", 1)).put(workspace("selected", "Selected project", 3)))
        assertEquals("Selected project", AgentPresentation.workspaceTitle("computer", selected, workspaces))
        val groups = AgentPresentation.groups(mapOf("computer" to JSONArray().put(selected)), workspaces)
        assertEquals(listOf("empty", "unrelated", "selected"), groups.map { it.id })
        assertTrue(groups.take(2).all { it.agents.isEmpty() })
        assertEquals(listOf(selected), groups.last().agents)
    }
    @Test fun workspaceTitleMatchesComputerAndServerAsWellAsWorkspaceId() {
        val selected = agent("b", "shared-id", 1, "Selected task").put("server_session_id", "worker")
        val workspaces = mapOf("computer" to JSONArray().put(workspace("shared-id", "Primary project"))
            .put(workspace("shared-id", "Worker project", server = "worker")),
            "other-computer" to JSONArray().put(workspace("shared-id", "Other computer", server = "worker")))
        assertEquals("Worker project", AgentPresentation.workspaceTitle("computer", selected, workspaces))
        assertEquals("Other computer", AgentPresentation.workspaceTitle("other-computer", selected, workspaces))
        selected.remove("server_session_id")
        assertEquals("Primary project", AgentPresentation.workspaceTitle("computer", selected, workspaces))
        workspaces.getValue("computer").getJSONObject(0).remove("server_session_id")
        selected.put("server_session_id", "primary")
        assertEquals("Primary project", AgentPresentation.workspaceTitle("computer", selected, workspaces))
    }
    @Test fun sameDirectoryDoesNotOverrideWorkspaceIdentity() {
        val selected = agent("b", "second", 1, "Selected task")
        val workspaces = mapOf("computer" to JSONArray().put(workspace("first", "First project"))
            .put(workspace("second", "Second project")))
        assertEquals("Second project", AgentPresentation.workspaceTitle("computer", selected, workspaces))
    }
    @Test fun missingMetadataFallsBackToSelectedAgentWithoutBorrowingOtherWorkspaceLabels() {
        val selected = agent("b", "missing", 1, "Selected task").put("workspace_name", "Own workspace")
        val workspaces = mapOf("computer" to JSONArray().put(workspace("unrelated", "Wrong workspace"))
            .put(workspace("missing", "Wrong server", server = "other")),
            "other-computer" to JSONArray().put(workspace("missing", "Wrong computer")))
        assertEquals("Own workspace", AgentPresentation.workspaceTitle("computer", selected, workspaces))
        selected.remove("workspace_name")
        assertEquals("project", AgentPresentation.workspaceTitle("computer", selected, workspaces))
        selected.put("cwd", "")
        assertEquals("未命名工作空间", AgentPresentation.workspaceTitle("computer", selected, workspaces))
    }
    @Test fun missingWorkspaceIdDoesNotMatchMetadataByDirectoryOrBlankId() {
        val selected = agent("b", "", 1, "Selected task").put("cwd", "/work/project/")
        val workspaces = mapOf("computer" to JSONArray().put(workspace("", "Blank id"))
            .put(workspace("known", "Same directory")).put(workspace("cwd:/work/project/", "Fallback key")))
        assertEquals("project", AgentPresentation.workspaceTitle("computer", selected, workspaces))
        selected.remove("workspace_id")
        assertEquals("project", AgentPresentation.workspaceTitle("computer", selected, workspaces))
    }
    @Test fun blankMetadataLabelUsesOwnWorkspaceNameThenWorkspacePathThenAgentPath() {
        val selected = agent("b", "w", 1, "Selected task").put("workspace_name", "Own workspace")
        val metadata = workspace("w", "").put("cwd", "/work/workspace-root/")
        val workspaces = mapOf("computer" to JSONArray().put(metadata))
        assertEquals("Own workspace", AgentPresentation.workspaceTitle("computer", selected, workspaces))
        selected.remove("workspace_name")
        assertEquals("workspace-root", AgentPresentation.workspaceTitle("computer", selected, workspaces))
        metadata.put("cwd", "")
        assertEquals("project", AgentPresentation.workspaceTitle("computer", selected, workspaces))
    }
}
