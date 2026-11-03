package dev.herdr.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceDirectoryUiTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun setup() {
        SharedRelay.resetForTest()
        context.getSharedPreferences("private_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("background-monitor", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("file-browser", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun waitUntil(root: View, predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 800, 1600)
            if (predicate()) return
            Thread.sleep(5)
        }
        fail("Directory UI condition timed out: ${views(root).filterIsInstance<TextView>().map { it.text }}")
    }
    private fun row(path: String, folder: Boolean = true) = JSONObject().put("path", path).put("name", path.substringAfterLast('/')).put("kind", if (folder) "directory" else "file").put("size", 10)
    @Test fun actualEncryptedDirectoryNavigationPagesRootAndReadsEachChildInsteadOfRecursiveTree() {
        MockTcpMonitorRelay().use { fixture ->
            fixture.extraCapabilities += listOf("workspace_directory_v1", "workspace_file_read_v1")
            val shared = SharedRelay.get(context)
            shared.vault.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }.set(shared.vault, lazyOf(SecretKeySpec(ByteArray(32) { 21 }, "AES")))
            shared.vault.write("state", fixture.state().toString())
            shared.engine.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(shared.engine, fixture.state())
            val requests = CopyOnWriteArrayList<Pair<String, String>>()
            fixture.foregroundCommand = { request ->
                when (request.getString("type")) {
                    "workspace_list" -> {
                        val target = request.getJSONObject("target")
                        check(target.getString("agent_session_id") == "session-a" && target.getString("terminal_id") == "terminal-a")
                        val path = request.getString("path"); val cursor = request.optString("cursor"); requests += path to cursor
                        val rows = when {
                            path == "docs" -> JSONArray().put(row("docs/guide.md", false))
                            path == "empty" -> JSONArray()
                            cursor == "next" -> JSONArray().put(row("scripts")).put(row("README.md", false))
                            else -> JSONArray().put(row("docs")).put(row("empty")).put(row(".cache-tree"))
                        }
                        JSONObject().put("root", "/fixture/project").put("path", path).put("revision", "fixture-directory")
                            .put("entries", rows).put("total", if (path == "") 5 else rows.length()).put("next_cursor", if (path == "" && cursor == "") "next" else "")
                    }
                    "get_conversation_history" -> JSONObject().put("available", true).put("source_revision", "fixture").put("entries", JSONArray())
                    "unwatch_pane", "unwatch_conversation", "release_pane_size" -> null
                    else -> error("Unexpected request: ${request.getString("type")}")
                }
            }
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); val activity = controller.get(); val root = activity.window.decorView
            fun texts() = views(root).filterIsInstance<TextView>()
            fun click(text: String) {
                var view: View? = texts().first { it.text.toString().contains(text) }
                if (view is android.widget.CompoundButton) { view.performClick(); return }
                while (view != null && !view.performClick()) view = view.parent as? View
                assertNotNull("No clickable ancestor for $text", view)
            }
            try {
                waitUntil(root) { shared.ready("fixture") }
                activity.javaClass.getDeclaredMethod("selectWorkspace", String::class.java, JSONObject::class.java).apply { isAccessible = true }.invoke(activity, "fixture", fixture.agent("a"))
                waitUntil(root) { texts().any { it.text.toString() == "docs" } }
                assertFalse(texts().any { it.text.toString() == ".cache-tree" })
                click("显示隐藏文件/目录")
                waitUntil(root) { texts().any { it.text.toString() == ".cache-tree" } }
                click("显示隐藏文件/目录")
                waitUntil(root) { texts().none { it.text.toString() == ".cache-tree" } }
                assertTrue(texts().any { it.text.toString() == "writer-a 的工作区" })
                assertTrue(texts().any { it.text.toString().contains("支持图片、PDF") })
                click("加载更多目录项")
                waitUntil(root) { texts().any { it.text.toString() == "scripts" } }
                assertTrue(requests.contains("" to "next"))
                click("docs")
                waitUntil(root) { texts().any { it.text.toString() == "guide.md" } }
                assertTrue(requests.contains("docs" to ""))
                assertTrue(texts().any { it.text.toString() == "/fixture/project/docs" })
                assertFalse(texts().any { it.text.toString() == "scripts" })
                click("上一级")
                waitUntil(root) { texts().any { it.text.toString() == "docs" } }
                assertEquals(2, requests.count { it == "" to "" })
                val out = File("../screenshots/qa-0.2.15").apply { mkdirs() }
                val bitmap = Bitmap.createBitmap(800, 1600, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff101d23.toInt()); root.draw(Canvas(bitmap))
                File(out, "workspace-directory.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                click("empty")
                waitUntil(root) { texts().any { it.text.toString() == "这个目录是空的" } }
                assertTrue(requests.contains("empty" to ""))
                assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
            } finally { controller.pause().stop().destroy() }
        }
    }
}
