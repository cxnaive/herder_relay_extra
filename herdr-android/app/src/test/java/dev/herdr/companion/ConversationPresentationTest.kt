package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationPresentationTest {
    @Test fun imageViewingToolsBecomeWorkspacePreviewsOutsideTheDisclosure() {
        val entry = JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "functions.view_image")
            .put("input", """{"path":"/work/project/plots/loss.png"}""")))
        val images = ConversationPresentation.images(entry, "/work/project")
        assertEquals("plots/loss.png", images.single().workspacePath)
        assertEquals("loss.png", images.single().label)
    }
    @Test fun supportsMarkdownAndObjectInputsWithoutDuplicatePreviews() {
        val entry = JSONObject().put("text", "结果 ![损失曲线](plots/loss.png)")
            .put("tools", JSONArray().put(JSONObject().put("name", "read")
                .put("input", JSONObject().put("file_path", "/work/project/plots/loss.png"))))
        assertEquals(1, ConversationPresentation.images(entry, "/work/project").size)
        assertEquals("损失曲线", ConversationPresentation.images(entry, "/work/project").single().label)
    }
    @Test fun neverReadsOutsideTheWorkspaceOrFetchesRemoteImages() {
        for (path in listOf("../private.png", "/work/project-other/image.png", "/tmp/image.png", "https://example.com/a.png", "//other/a.png")) {
            assertNull(path, ConversationPresentation.workspacePath(path, "/work/project"))
        }
        assertEquals("a.png", ConversationPresentation.workspacePath("/work/project/nested/../a.png", "/work/project"))
    }
    @Test fun embeddedImagesAreRecognizedAndPreviewCountsAreBounded() {
        val entry = JSONObject().put("images", JSONArray().put("data:image/png;base64,aGVsbG8="))
        assertTrue(ConversationPresentation.images(entry, "/work").single().source.startsWith("data:"))
        val many = JSONObject().put("images", JSONArray((1..20).map { "images/$it.png" }))
        assertEquals(8, ConversationPresentation.images(many, "/work").size)
    }
    @Test fun collapsedToolSummariesExposeRunningAndFailedCalls() {
        assertTrue(ConversationPresentation.toolSummary(JSONArray().put(JSONObject().put("state", "running"))).contains("进行中"))
        assertTrue(ConversationPresentation.toolSummary(JSONArray().put(JSONObject().put("error", true))).contains("失败"))
    }
}
