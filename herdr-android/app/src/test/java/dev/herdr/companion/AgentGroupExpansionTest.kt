package dev.herdr.companion

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AgentGroupExpansionTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun clear() { context.getSharedPreferences(AgentGroupExpansion.PREFERENCES, 0).edit().clear().commit() }
    private fun workspace(relay: String = "a", server: String = "primary", id: String = "shared", title: String = "项目") =
        AgentPresentation.Group(relay, server, id, title, "/same/path", 1, emptyList())

    @Test fun defaultsExpandedAndRemembersChangesAcrossInstances() {
        val key = AgentGroupExpansion.computerKey("a")
        val state = AgentGroupExpansion(context)
        assertTrue(state.expanded(key, ""))
        state.toggle(key, "")
        assertFalse(AgentGroupExpansion(context).expanded(key, ""))
        AgentGroupExpansion(context).toggle(key, "")
        assertTrue(AgentGroupExpansion(context).expanded(key, ""))
    }
    @Test fun computerCollapseDoesNotResetChildWorkspaceState() {
        val state = AgentGroupExpansion(context)
        val computer = AgentGroupExpansion.computerKey("a")
        val child = AgentGroupExpansion.workspaceKey(workspace())
        state.toggle(child, ""); state.toggle(computer, ""); state.toggle(computer, "")
        assertTrue(state.expanded(computer, ""))
        assertFalse(state.expanded(child, ""))
    }
    @Test fun namesPathsServersAndComputersCannotMixUpExpansionIdentity() {
        val key = AgentGroupExpansion.workspaceKey(workspace())
        val state = AgentGroupExpansion(context)
        state.toggle(key, "")
        assertEquals(key, AgentGroupExpansion.workspaceKey(workspace(title = "已重命名")))
        assertTrue(state.expanded(AgentGroupExpansion.workspaceKey(workspace(relay = "b")), ""))
        assertTrue(state.expanded(AgentGroupExpansion.workspaceKey(workspace(server = "secondary")), ""))
        assertTrue(state.expanded(AgentGroupExpansion.workspaceKey(workspace(id = "different")), ""))
        assertNotEquals(AgentGroupExpansion.computerKey("a"), key)
    }
    @Test fun searchingTemporarilyExpandsWithoutChangingSavedPreferences() {
        val state = AgentGroupExpansion(context)
        val key = AgentGroupExpansion.computerKey("a")
        state.toggle(key, "")
        assertTrue(state.expanded(key, "writer"))
        assertFalse(AgentGroupExpansion(context).expanded(key, ""))
        state.toggle(key, "writer")
        assertFalse(state.expanded(key, "writer"))
        assertFalse(state.expanded(key, ""))
        assertTrue(state.expanded(key, "another query"))
    }
    @Test fun searchFoldDoesNotPersistOnNormallyExpandedGroups() {
        val state = AgentGroupExpansion(context)
        val key = AgentGroupExpansion.workspaceKey(workspace())
        state.toggle(key, "search")
        assertFalse(state.expanded(key, "search"))
        assertTrue(state.expanded(key, ""))
        assertTrue(AgentGroupExpansion(context).expanded(key, ""))
    }
    @Test fun whitespaceUsesNormalExpansionAndKeysAreUnambiguous() {
        val state = AgentGroupExpansion(context)
        val key = AgentGroupExpansion.computerKey("a::b")
        state.toggle(key, "  ")
        assertFalse(state.expanded(key, ""))
        assertNotEquals(AgentGroupExpansion.workspaceKey(workspace(relay = "a::b", server = "c")),
            AgentGroupExpansion.workspaceKey(workspace(relay = "a", server = "b::c")))
    }
}
