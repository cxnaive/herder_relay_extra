package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationTimelineTest {
    private fun call(id: String, name: String = "exec_command", text: String = "") = JSONObject().put("id", id).put("role", "assistant").put("text", text)
        .put("tools", JSONArray().put(JSONObject().put("id", "call-$id").put("name", name).put("input", """{"cmd":"ls src"}""").put("output", "file.kt")))
    private fun message(id: String, role: String, text: String) = JSONObject().put("id", id).put("role", role).put("text", text)
    @Test fun consecutiveToolEntriesBecomeOneOuterGroup() {
        val blocks = ConversationTimeline.blocks(listOf(call("1"), call("2", "read"), call("3", "view_image")))
        val group = blocks.single() as ConversationTimeline.Tools
        assertEquals(3, group.tools.size); assertEquals(3, group.entries.size)
        assertEquals("命令 1 · 读取 1 · 图片 1", ConversationTimeline.overview(group))
    }
    @Test fun emptyAssistantEventsDoNotSplitConsecutiveToolCalls() {
        val blocks = ConversationTimeline.blocks(listOf(call("1"), message("empty", "assistant", ""), call("2")))
        assertEquals(2, (blocks.single() as ConversationTimeline.Tools).tools.size)
    }
    @Test fun groupsDoNotCrossUserMessagesOrAssistantProse() {
        val blocks = ConversationTimeline.blocks(listOf(call("1"), message("u", "user", "下一步"), call("2"), message("a", "assistant", "结果"), call("3")))
        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is ConversationTimeline.Tools); assertTrue(blocks[1] is ConversationTimeline.Message)
        assertTrue(blocks[2] is ConversationTimeline.Tools); assertTrue(blocks[3] is ConversationTimeline.Message)
    }
    @Test fun proseAndToolsOnOneEntryKeepTextBeforeTheGroup() {
        val blocks = ConversationTimeline.blocks(listOf(call("1", text = "检查文件"), call("2")))
        assertEquals(2, blocks.size)
        assertEquals("检查文件", (blocks[0] as ConversationTimeline.Message).entry.getString("text"))
        assertEquals(2, (blocks[1] as ConversationTimeline.Tools).tools.size)
    }
    @Test fun streamedAppendDoesNotChangeTheOuterOrInnerExpansionKeys() {
        val first = ConversationTimeline.blocks(listOf(call("1"))).single() as ConversationTimeline.Tools
        val updated = ConversationTimeline.blocks(listOf(call("1"), call("2"))).single() as ConversationTimeline.Tools
        assertEquals(first.key, updated.key); assertEquals(first.tools.first().key, updated.tools.first().key)
    }
    @Test fun distinctEntriesWithReusedToolIdsDoNotShareInnerExpansion() {
        val group = ConversationTimeline.blocks(listOf(call("1"), call("2").apply { getJSONArray("tools").getJSONObject(0).put("id", "call-1") })).single() as ConversationTimeline.Tools
        assertNotEquals(group.tools[0].key, group.tools[1].key)
    }
    @Test fun labelsAndDetailsAreCompactButOutputRemainsAvailable() {
        val tool = call("1").getJSONArray("tools").getJSONObject(0)
        assertEquals("ls src", ConversationTimeline.preview(tool))
        assertEquals("file.kt", ConversationTimeline.detail(tool, "output"))
        assertTrue(ConversationTimeline.detail(tool, "input").contains("cmd"))
    }
}
