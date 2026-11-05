package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentLaunchArgumentsTest {
    private fun parse(value: String) = AgentLaunchArguments.parse(value)
    private val ws = JSONObject().put("workspace_id", "workspace-one").put("cwd", "/work/project")

    @Test fun noDaemonIsArgvNotAnInitialPrompt() {
        val payload = AgentManagement.launchPayload(ws, "codex", "writer", "开始调研", "--no-daemon", true)
        assertEquals(listOf("--no-daemon"), payload.getJSONArray("agent_args").let { (0 until it.length()).map(it::getString) })
        assertEquals("开始调研", payload.getString("prompt"))
        assertEquals("codex", payload.getString("profile_id"))
        assertEquals("workspace-one", payload.getString("workspace_id"))
    }
    @Test fun whitespaceAndEmptyInputKeepExistingLaunchBehavior() {
        assertEquals(emptyList<String>(), parse(" \t "))
        assertFalse(AgentManagement.launchPayload(ws, "claude", "writer", "", " \t ").has("agent_args"))
        assertEquals(listOf("--no-daemon", "--model", "custom"), parse(" --no-daemon\t --model  custom "))
    }
    @Test fun quotesEscapesUnicodeAndEmptyArgumentsArePreserved() {
        assertEquals(listOf("-c", "model=\"custom\"", "two words", "", "", "beforeafter", "项目 名称", "one two"),
            parse("-c 'model=\"custom\"' \"two words\" '' \"\" before'after' '项目 名称' one\\ two"))
        assertEquals(listOf("a'b", "a\"b", "a\\b", "a\\qb"), parse("a\\'b \"a\\\"b\" 'a\\b' \"a\\qb\""))
    }
    @Test fun shellSyntaxIsLiteralNeverExpanded() {
        assertEquals(listOf("\$HOME", "\$(whoami)", "`id`", "~", "*", ";", "|", ">"), parse("\$HOME \$(whoami) `id` ~ * ; | >"))
    }
    @Test fun malformedOrUnboundedInputIsRejected() {
        for (bad in listOf("'open", "\"open", "trailing\\", "a\nb", "a\rb", "a\u0000b", "a\u001bb", "'a\tb'", "a\u0085b", "x".repeat(8193), "中".repeat(2731), List(65) { "a" }.joinToString(" "), " ".repeat(16385))) {
            assertThrows("accepted malformed arguments", IllegalArgumentException::class.java) { parse(bad) }
        }
        assertEquals(8192, parse("x".repeat(8192)).single().length)
        assertEquals(64, parse(List(64) { "''" }.joinToString(" ")).size)
    }
    @Test fun oldRelayCannotSilentlyDropArguments() {
        assertFalse(AgentLaunchArguments.supported(null))
        assertFalse(AgentLaunchArguments.supported(JSONObject().put("capabilities", JSONArray().put("agent_model_settings_v1"))))
        assertTrue(AgentLaunchArguments.supported(JSONObject().put("capabilities", JSONArray().put(AgentLaunchArguments.CAPABILITY))))
        val error = assertThrows(IllegalArgumentException::class.java) { AgentManagement.launchPayload(ws, "codex", "writer", "", "--no-daemon") }
        assertTrue(error.message!!.contains("升级电脑 Relay"))
    }
}
