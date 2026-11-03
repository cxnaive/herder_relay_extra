package dev.herdr.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeConversationUiTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun calls() = listOf("读取" to "read", "命令" to "exec_command", "图像" to "view_image").mapIndexed { index, (_, name) ->
        JSONObject().put("id", "entry-$index").put("role", "assistant").put("text", "")
            .put("tools", JSONArray().put(JSONObject().put("id", "call-$index").put("name", name).put("state", "done")
                .put("input", JSONObject().put(if (name == "exec_command") "cmd" else "path", if (name == "exec_command") "python evaluate.py --split validation" else "results/plot.png"))
                .put("output", "Validation complete\naccuracy: 0.92\nsamples: 1200")))
    }
    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
    private fun draw(name: String, root: View, width: Int = 760): Int {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, width, root.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, root.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(0xff101d23.toInt()); root.draw(canvas)
        val dir = File("build/reports/ui-024").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return root.measuredHeight
    }
    @Test fun toolGroupAndIndividualToolsAreIndependentDisclosures() {
        val group = ConversationTimeline.blocks(calls()).single() as ConversationTimeline.Tools
        val outer = mutableSetOf<String>(); val inner = mutableSetOf<String>()
        val view = NativeToolGroup(context, group, outer, inner) { _, _ -> }
        draw("tools-collapsed", view)
        assertTrue(outer.isEmpty()); assertTrue(inner.isEmpty())
        val groupHeader = descendants(view).first { it.contentDescription?.startsWith("工具组：") == true }
        groupHeader.performClick()
        assertEquals(setOf(group.key), outer); assertTrue(inner.isEmpty())
        assertEquals(3, descendants(view).count { it.contentDescription?.startsWith("工具：") == true })
        draw("tools-group-open", view)
        val toolHeader = descendants(view).first { it.contentDescription?.startsWith("工具：") == true }
        toolHeader.performClick(); assertEquals(1, inner.size)
        assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.contains("Validation complete") })
        draw("tools-detail-open", view)
        groupHeader.performClick(); assertEquals(1, inner.size)
        groupHeader.performClick(); assertEquals(1, inner.size)
        val rerendered = NativeToolGroup(context, group, outer, inner) { _, _ -> }
        assertTrue(descendants(rerendered).filterIsInstance<TextView>().any { it.text.contains("Validation complete") })
        val older = JSONObject(calls().first().toString()).put("id", "older-entry")
        val merged = ConversationTimeline.blocks(listOf(older) + calls()).single() as ConversationTimeline.Tools
        val prepended = NativeToolGroup(context, merged, outer, inner) { _, _ -> }
        assertTrue("Prepending older tool history lost the inner disclosure", descendants(prepended).filterIsInstance<TextView>().any { it.text.contains("Validation complete") })
    }
    @Test fun paddedDesktopPromptDoesNotInflateMobileTerminalOrEmptyComposer() {
        context.getSharedPreferences("terminal-appearance", Context.MODE_PRIVATE).edit().clear().commit()
        val esc = "\u001b["
        val blank = "${esc}48;2;41;41;41m" + " ".repeat(158) + "${esc}0m"
        val prompt = "${esc}48;2;41;41;41m› Ask Codex to do anything" + " ".repeat(132) + "${esc}0m"
        val terminal = NativeTerminalView(context)
        terminal.setViewportWidth(720)
        terminal.show("${esc}32mBuild complete${esc}0m\n中文任务 · history is preserved\n\n$blank\n$prompt\n$blank", 158)
        assertTrue("Desktop padding became phantom mobile rows", terminal.displayedRows <= 8)
        draw("terminal-mobile", terminal, 720)
        val composer = NativeComposerView(context, true, "", {}, {}, {}, {}, {})
        val height = draw("terminal-composer", composer, 720)
        assertTrue("Empty composer should remain compact", height <= AgentStyle.dp(context, 112))
        assertEquals(1, composer.editor.minLines)
        val combined = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20)
            addView(terminal); addView(composer)
        }
        draw("terminal-and-composer", combined, 760)
    }
}
