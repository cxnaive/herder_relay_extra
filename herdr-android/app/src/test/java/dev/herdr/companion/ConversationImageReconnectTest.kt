package dev.herdr.companion

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.ScrollView
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationImageReconnectTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun setup() {
        SharedRelay.resetForTest()
        context.getSharedPreferences("private_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("background-monitor", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun layout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 800, 1440); root.viewTreeObserver.dispatchOnGlobalLayout()
    }
    private fun waitUntil(root: View, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); layout(root)
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Conversation image fixture timed out")
    }
    private fun png(): String {
        val bitmap = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.rgb(38, 126, 130))
        val canvas = Canvas(bitmap); val paint = android.graphics.Paint().apply { color = Color.WHITE; textSize = 28f; isAntiAlias = true }
        canvas.drawText("Recovered image", 30f, 65f, paint)
        val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray())
    }
    private fun entry(name: String) = JSONObject().put("id", name).put("role", "assistant").put("text", "")
        .put("tools", JSONArray().put(JSONObject().put("id", "call-$name").put("name", "functions.view_image")
            .put("input", JSONObject().put("path", "/fixture/project/$name.png").toString()).put("output", "attached image").put("state", "completed")))
    @Test fun realEncryptedReconnectAndReturnFromBackgroundRecoverHistoryImagesWithoutRerunningAgent() {
        MockTcpMonitorRelay().use { fixture ->
            val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
            val shared = SharedRelay.get(context)
            shared.vault.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }.set(shared.vault, lazyOf(SecretKeySpec(ByteArray(32) { 13 }, "AES")))
            shared.engine.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(shared.engine, fixture.state())
            val extraImage = AtomicBoolean(false); val disconnectFirstImage = AtomicBoolean(true)
            val paths = CopyOnWriteArrayList<String>(); val raw = png()
            fixture.foregroundCommand = { request ->
                when (request.getString("type")) {
                    "get_conversation_history" -> JSONObject().put("available", true).put("source_revision", "fixture-revision")
                        .put("entries", JSONArray().put(JSONObject().put("id", "question").put("role", "user").put("text", "Review the plots"))
                            .put(entry("plot")).apply { if (extraImage.get()) put(entry("offline-plot")) })
                    "workspace_file" -> {
                        val target = request.getJSONObject("target")
                        check(target.getString("agent_session_id") == "session-a" && target.getString("terminal_id") == "terminal-a")
                        val path = request.getString("path"); check(path in listOf("plot.png", "offline-plot.png")); paths += path
                        if (disconnectFirstImage.compareAndSet(true, false)) { fixture.disconnect(); null }
                        else JSONObject().put("kind", "image").put("data_url", raw)
                    }
                    "unwatch_pane", "unwatch_conversation", "release_pane_size" -> null
                    else -> error("Unexpected foreground request: ${request.getString("type")}")
                }
            }
            val root = FrameLayout(activity); activity.setContentView(root)
            val ui = NativeAgents(activity, shared.engine, shared.vault, { shared.agents }, { _, _ -> }, {}, {})
            fun images() = views(root).filterIsInstance<ImageView>().filter { it.contentDescription?.startsWith("图像预览：") == true }
            try {
                ui.attach(root); shared.attachUI(ui, ui::onEvent)
                waitUntil(root) { shared.ready("fixture") }
                ui.open("fixture", fixture.agent("a"))
                waitUntil(root) { fixture.connected.get() >= 2 && images().any { it.drawable is BitmapDrawable } }
                assertEquals(2, paths.count { it == "plot.png" })
                val out = File("../screenshots/qa-0.2.14").apply { mkdirs() }
                val screenshot = Bitmap.createBitmap(800, 1440, Bitmap.Config.ARGB_8888); screenshot.eraseColor(0xff101d23.toInt()); root.draw(Canvas(screenshot))
                File(out, "reconnected-image.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
                ui.pause(); shared.detachUI(ui)
                val count = paths.size; extraImage.set(true)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10)); assertEquals(count, paths.size)
                // Render the retained history first while disconnected, then bring the same session online.
                ui.attach(root); shared.attachUI(ui, ui::onEvent)
                waitUntil(root) { images().any { it.contentDescription == "图像预览：offline-plot.png" } }
                // Lazy loading is intentional: bring the newly replayed history card into the viewport.
                val scroll = ui.javaClass.getDeclaredField("conversationScroll").apply { isAccessible = true }.get(ui) as ScrollView
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                waitUntil(root) { images().any { it.contentDescription == "图像预览：offline-plot.png" && it.drawable is BitmapDrawable } }
                assertTrue(paths.contains("offline-plot.png"))
                val state = ui.javaClass.getDeclaredField("conversation").apply { isAccessible = true }.get(ui) as ConversationState
                assertTrue(state.entries.keys.containsAll(listOf("plot", "offline-plot")))
                assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
                assertFalse(views(root).filterIsInstance<TextView>().any { it.text.contains("暂无法预览") })
            } finally { ui.destroy(); shared.detachUI(ui); controller.pause().stop().destroy() }
        }
    }
}
