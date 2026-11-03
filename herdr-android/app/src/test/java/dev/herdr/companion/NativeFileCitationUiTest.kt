package dev.herdr.companion

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeFileCitationUiTest {
    private val paths = listOf("output/pdf/GT-VLM-Inference-Cost-Preview.pdf", "output/pdf/GT-VLM-CVPR2027-Teaser-B.pdf")
    private val text = "**成本表单页预览：**\n\n:codex-file-citation{path=\"/work/project/${paths[0]}\" purpose=\"output\"}\n\n**完整 B 版：**\n\n:codex-file-citation{path=\"/work/project/${paths[1]}\" purpose=\"output\"}"
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    private fun get(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun draw(root: View, name: String) {
        val w = 800; val h = 1480
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); root.layout(0, 0, w, h)
            root.draw(Canvas(bitmap)); shadowOf(Looper.getMainLooper()).idle()
        }
        val dir = File("build/reports/ui-026").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun writerFinalMessageHasTwoLinksAndTwoCardsThatOpenExactReviewerPaths() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        val vault = Vault(activity)
        val engine = RelayEngine(activity, vault) {}
        val agent = JSONObject().put("pane_id", "writer-1").put("terminal_id", "terminal-test").put("generation", 1)
            .put("agent_session_id", "session-test").put("cwd", "/work/project").put("agent", "codex").put("tab_label", "writer-1")
        val agents = NativeAgents(activity, engine, vault, { mapOf("fixture" to JSONArray().put(agent)) }, { _, _ -> }, {}, {})
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val scroll = ScrollView(activity).apply { setBackgroundColor(0xff101b22.toInt()); addView(body) }
        activity.setContentView(scroll)
        try {
            set(agents, "selected", agent); set(agents, "relay", "fixture"); set(agents, "active", true)
            set(agents, "conversationBody", body); set(agents, "conversationScroll", scroll)
            val state = get(agents, "conversation") as ConversationState
            state.entries["final"] = JSONObject().put("id", "final").put("role", "assistant").put("text", text)
            agents.javaClass.getDeclaredMethod("renderConversation", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(agents, false)
            draw(scroll, "writer-final-pdf-citations")
            val cards = descendants(body).filter { it.contentDescription?.startsWith("查看文档：") == true }
            assertEquals(paths.map { "查看文档：$it" }, cards.map { it.contentDescription.toString() })
            val message = descendants(body).filterIsInstance<TextView>().first { it.text.contains("成本表单页") }
            assertFalse(message.text.contains(":codex-file-citation")); assertFalse(message.text.contains("purpose="))
            val spans = (message.text as Spanned).getSpans(0, message.text.length, ClickableSpan::class.java).sortedBy { (message.text as Spanned).getSpanStart(it) }
            assertEquals(2, spans.size)
            for ((index, span) in spans.withIndex()) {
                val marked = message.text as Spanned
                assertEquals(FileRules.safeName(paths[index]), marked.subSequence(marked.getSpanStart(span), marked.getSpanEnd(span)).toString())
            }
            // Inline citation and standalone card take exactly the same guarded route.
            spans[0].onClick(message)
            assertEquals(paths[0], (get(agents, "reviewer") as DocumentReviewer).activePath)
            assertTrue(ShadowDialog.getLatestDialog().isShowing)
            cards[1].performClick()
            assertEquals(paths[1], (get(agents, "reviewer") as DocumentReviewer).activePath)
        } finally {
            set(agents, "selected", null); agents.destroy(); engine.destroy(); controller.pause().stop().destroy()
        }
    }

    @Test fun markdownReviewerAlsoResolvesTheSameDirectiveWithoutNetworkOrPdfJni() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        val requests = mutableListOf<String>()
        val reviewer = DocumentReviewer(activity, "/work/project", { true }) { path, done ->
            requests += path
            if (path == "docs/report.md") done(JSONObject().put("kind", "text").put("text", text), null)
        }
        try {
            reviewer.open(ReviewReference("docs/report.md"))
            val root = ShadowDialog.getLatestDialog().window!!.decorView
            var message: TextView? = null
            for (i in 0 until 200) {
                shadowOf(Looper.getMainLooper()).idle()
                message = descendants(root).filterIsInstance<TextView>().firstOrNull { it.text.contains("成本表单页") }
                if (message != null) break
                Thread.sleep(10)
            }
            assertNotNull(message)
            val spans = (message!!.text as Spanned).getSpans(0, message.text.length, ClickableSpan::class.java).sortedBy { (message.text as Spanned).getSpanStart(it) }
            assertEquals(2, spans.size); assertFalse(message.text.contains(":codex-file-citation"))
            spans[1].onClick(message)
            assertEquals(paths[1], requests.last())
        } finally { reviewer.close(); controller.pause().stop().destroy() }
    }
}
