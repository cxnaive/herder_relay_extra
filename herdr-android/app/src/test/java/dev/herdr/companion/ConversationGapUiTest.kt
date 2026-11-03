package dev.herdr.companion

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationGapUiTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun setup() {
        SharedRelay.resetForTest()
        context.getSharedPreferences("private_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("background-monitor", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun message(id: String, text: String, role: String = "assistant") = JSONObject().put("id", id).put("role", role).put("text", text)
    private fun tool(id: String, name: String, input: JSONObject) = message(id, "").put("tools", JSONArray().put(JSONObject().put("id", id).put("name", name).put("input", input.toString()).put("state", "completed")))
    private fun page(cursor: String, vararg entries: JSONObject) = JSONObject().put("available", true).put("state", "ready").put("source_revision", "revision")
        .put("subscription_id", "live").put("sequence", 1).put("next_cursor", cursor).put("entries", JSONArray(entries.toList()))
    @Test fun cachedUserAndNewToolTailDoNotHideMissingCommentaryOrImageBehindCollapsedTools() {
        MockTcpMonitorRelay().use { fixture ->
            fixture.extraCapabilities += "conversation_activity"
            val shared = SharedRelay.get(context)
            shared.vault.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }.set(shared.vault, lazyOf(SecretKeySpec(ByteArray(32) { 25 }, "AES")))
            shared.engine.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(shared.engine, fixture.state())
            val imageBitmap = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff267e82.toInt()) }
            Canvas(imageBitmap).drawText("Recovered preview", 20f, 66f, Paint().apply { color = -1; textSize = 28f; isAntiAlias = true })
            val imageBytes = ByteArrayOutputStream(); imageBitmap.compress(Bitmap.CompressFormat.PNG, 100, imageBytes); imageBitmap.recycle()
            val imageData = "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes.toByteArray())
            val reads = CopyOnWriteArrayList<String>()
            val user = message("user", "Review the figure", "user"); val first = message("first", "I will inspect the figure.")
            val old = tool("old", "exec_command", JSONObject().put("cmd", "echo before"))
            val middle = message("middle", "The intermediate image is ready.")
            val image = tool("image", "view_image", JSONObject().put("path", "/fixture/project/plot.png"))
            val newest = tool("new", "exec_command", JSONObject().put("cmd", "echo after"))
            fixture.foregroundCommand = { request ->
                when (request.getString("type")) {
                    "get_conversation_history" -> {
                        val cursor = request.optString("cursor"); reads += cursor
                        when (cursor) {
                            "" -> page("middle-page", newest, message("last", "The next reply is visible."))
                            "middle-page" -> page("start-page", image, middle)
                            "start-page" -> page("", user, first, old)
                            else -> error("unexpected cursor")
                        }
                    }
                    "get_conversation_updates" -> JSONObject().put("kind", "heartbeat").put("subscription_id", "live").put("source_revision", "revision").put("sequence", 1)
                    "workspace_file" -> JSONObject().put("kind", "image").put("data_url", imageData)
                    "unwatch_pane", "unwatch_conversation", "release_pane_size" -> null
                    else -> error("unexpected write or terminal read")
                }
            }
            val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
            val root = FrameLayout(activity); activity.setContentView(root)
            val ui = NativeAgents(activity, shared.engine, shared.vault, { shared.agents }, { _, _ -> }, {}, {})
            fun waitUntil(condition: () -> Boolean) {
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
                while (System.nanoTime() < end) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                    root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 800, 1600)
                    if (condition()) return; Thread.sleep(5)
                }
                fail("gap UI fixture timed out: $reads")
            }
            try {
                val memory = ui.javaClass.getDeclaredField("conversationCache").apply { isAccessible = true }.get(ui) as ConversationMemoryCache
                val cached = ConversationState(); cached.page(page("older", user, first, old)); memory.put(WorkspaceBinding.fromAgent("fixture", fixture.agent("a")), cached)
                ui.attach(root); shared.attachUI(ui, ui::onEvent); waitUntil { shared.ready("fixture") }
                ui.open("fixture", fixture.agent("a"))
                waitUntil { views(root).filterIsInstance<TextView>().any { it.text.toString() == "The intermediate image is ready." } }
                assertTrue(reads.containsAll(listOf("", "middle-page", "start-page")))
                val state = ui.javaClass.getDeclaredField("conversation").apply { isAccessible = true }.get(ui) as ConversationState
                assertEquals(listOf("user", "first", "old", "image", "middle", "new", "last"), state.entries.keys.toList())
                val text = views(root).filterIsInstance<TextView>().single { it.text.toString() == "The intermediate image is ready." }
                val picture = views(root).filterIsInstance<ImageView>().single { it.contentDescription == "图像预览：plot.png" }
                for (v in listOf(text, picture)) { var parent = v.parent; while (parent is View) { assertFalse(parent is NativeToolGroup); parent = parent.parent } }
                waitUntil { picture.drawable is BitmapDrawable }
                val groups = ui.javaClass.getDeclaredField("expandedToolGroups").apply { isAccessible = true }.get(ui) as Set<*>
                assertTrue(groups.isEmpty()); assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
                val output = File("../screenshots/qa-0.2.16").apply { mkdirs() }
                val bitmap = Bitmap.createBitmap(800, 1600, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff101d23.toInt()); root.draw(Canvas(bitmap))
                File(output, "recovered-commentary.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            } finally { ui.destroy(); shared.detachUI(ui); controller.pause().stop().destroy() }
        }
    }
}
