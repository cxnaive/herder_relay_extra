package dev.herdr.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationFileLinksTcpUiTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun setup() {
        SharedRelay.resetForTest()
        for (name in listOf("private_state", "background-monitor", "file-browser")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); if (condition()) return; Thread.sleep(5) }
        fail("Conversation file UI timed out: ${org.robolectric.shadows.ShadowToast.getTextOfLatestToast()}")
    }
    private fun draw(root: View, name: String) {
        val bitmap = Bitmap.createBitmap(800, 1600, Bitmap.Config.ARGB_8888)
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 800, 1600)
            root.draw(Canvas(bitmap)); shadowOf(Looper.getMainLooper()).idle()
        }
        File("build/reports/file-links").mkdirs(); File("build/reports/file-links/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun realConversationLinksUseFilesystemPathsAndDownloadWhileReleaseLabelsStayText() {
        MockTcpMonitorRelay().use { fixture ->
            fixture.role = "controller"; fixture.filesystemRead = true
            fixture.extraCapabilities += listOf("conversation_activity", "workspace_file_read_v1", "workspace_file_download_v1", "filesystem_read_opt_in_v1")
            val paths = listOf("fixture/artifacts/Pocket-1.2.3-debug.apk", "fixture/artifacts/source.zip", "fixture/artifacts/SHA256SUMS.txt")
            val release = "已发布到 `example/pocket`，版本 `android-v1.2.3` 和 `native-v0.23.5-codexfix.11`。"
            val linked = "文件下载：\n\n[APK](file:///${paths[0]})\n\n[源码](/${paths[1]})\n\n[校验](</${paths[2]}>)"
            val requests = CopyOnWriteArrayList<Pair<String, Boolean>>()
            val checksums = "abc123  Pocket-1.2.3-debug.apk\n".toByteArray()
            val revision = "e".repeat(64)
            fixture.foregroundCommand = { request ->
                when (request.getString("type")) {
                    "get_conversation_history" -> JSONObject().put("available", true).put("state", "ready").put("source_revision", "r1").put("subscription_id", "live").put("sequence", 1).put("next_cursor", "")
                        .put("entries", JSONArray().put(JSONObject().put("id", "user").put("role", "user").put("text", "请给我下载文件"))
                            .put(JSONObject().put("id", "release").put("role", "assistant").put("text", release))
                            .put(JSONObject().put("id", "files").put("role", "assistant").put("text", linked)))
                    "get_conversation_updates" -> JSONObject().put("kind", "heartbeat").put("subscription_id", "live").put("source_revision", "r1").put("sequence", 1)
                    "workspace_file_download", "workspace_file_read" -> {
                        assertEquals("filesystem", request.getString("file_scope")); assertEquals("/fixture/project", request.getString("cwd"))
                        val target = request.getJSONObject("target"); assertEquals("terminal-a", target.getString("terminal_id")); assertEquals("session-a", target.getString("agent_session_id"))
                        val path = request.getString("path"); check(path in paths) { "unexpected inferred path $path" }
                        val metadata = request.optBoolean("metadata_only"); requests += path to metadata
                        val size = if (path == paths[2]) checksums.size else 13 * 1024 * 1024
                        if (metadata) JSONObject().put("path", path).put("size", size).put("kind", if (path == paths[2]) "text" else "binary")
                            .put("media_type", "application/octet-stream").put("metadata_only", true).put("download_revision", revision)
                            .put("preview_available", path == paths[2]).put("preview_reason", "文件超过预览限制；仍可下载或分享原文件")
                        else {
                            assertEquals(paths[2], path)
                            val hash = MessageDigest.getInstance("SHA-256").digest(checksums).joinToString("") { "%02x".format(it) }
                            if (request.getString("type") == "workspace_file_download") assertEquals(revision, request.getString("expected_revision"))
                            JSONObject().put("path", path).put("size", size).put("kind", "text").put("offset", 0).put("next_cursor", "")
                                .put("sha256", hash).put("download_revision", revision).put("chunk_sha256", hash).put("data", Base64.getEncoder().encodeToString(checksums))
                        }
                    }
                    "unwatch_pane", "unwatch_conversation", "release_pane_size" -> null
                    else -> error("Unexpected conversation command ${request.getString("type")}")
                }
            }
            val shared = SharedRelay.get(context)
            shared.vault.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }.set(shared.vault, lazyOf(SecretKeySpec(ByteArray(32) { 21 }, "AES")))
            shared.vault.write("state", fixture.state().toString()); shared.engine.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(shared.engine, fixture.state())
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); val activity = controller.get(); val root = activity.window.decorView
            try {
                waitUntil { shared.ready("fixture") }
                val ui = activity.javaClass.getDeclaredField("nativeAgents").apply { isAccessible = true }.get(activity) as NativeAgents
                ui.open("fixture", fixture.agent("a"))
                waitUntil { views(root).any { it.contentDescription == "查看文档：${paths[2]}" } }
                val cards = views(root).filter { it.contentDescription?.startsWith("查看文档：") == true }
                assertEquals(paths.map { "查看文档：$it" }, cards.map { it.contentDescription.toString() })
                assertTrue("Rendering must not probe speculative files", requests.isEmpty())
                val label = views(root).filterIsInstance<TextView>().first { it.text.toString().contains("已发布到") }
                assertTrue((label.text as Spanned).getSpans(0, label.text.length, ClickableSpan::class.java).isEmpty())
                draw(root, "release-labels-and-file-links")
                val message = views(root).filterIsInstance<TextView>().first { it.text.toString().contains("文件下载：") }
                val spans = (message.text as Spanned).getSpans(0, message.text.length, ClickableSpan::class.java).sortedBy { (message.text as Spanned).getSpanStart(it) }
                assertEquals(3, spans.size)
                for (i in paths.indices) {
                    val before = requests.size
                    if (i == 1) cards[i].performClick() else spans[i].onClick(message)
                    waitUntil { requests.size > before && requests[before] == paths[i] to true }
                    val viewer = ShadowDialog.getLatestDialog()
                    waitUntil { views(viewer.window!!.decorView).filterIsInstance<TextView>().any { if (i == 2) it.text.toString().contains("abc123") else it.text.toString().contains("超过预览限制") } }
                    assertTrue(views(viewer.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("/${paths[i]}") })
                    if (i < 2) viewer.dismiss()
                }
                val viewer = ShadowDialog.getLatestDialog()
                views(viewer.window!!.decorView).filterIsInstance<Button>().first { it.text == "下载原文件" }.performClick()
                waitUntil { shadowOf(activity).peekNextStartedActivity() != null }
                val intent = shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action); assertEquals("SHA256SUMS.txt", intent.getStringExtra(Intent.EXTRA_TITLE))
                val output = File(activity.cacheDir, "downloaded-checksums.txt")
                // The system destination picker stops the real Activity and closes its reviewer.
                controller.pause().stop()
                shadowOf(activity).receiveResult(intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(output)))
                controller.restart().start().resume()
                waitUntil { output.exists() && output.length() == checksums.size.toLong() }
                assertArrayEquals(checksums, output.readBytes()); output.delete()
                assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
            } finally { controller.pause().stop().destroy() }
        }
    }
    @Test fun missingExplicitFileExplainsTheExactPathWithoutSearchingOtherDirectories() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        var reads = 0
        val reviewer = DocumentReviewer(activity, "/fixture/project", { true }, unrestricted = true) { _, done -> reads++; done(null, "Workspace file was not found") }
        try {
            reviewer.openLink("/fixture/artifacts/moved.zip")
            val root = ShadowDialog.getLatestDialog().window!!.decorView
            val text = views(root).filterIsInstance<TextView>().joinToString("\n") { it.text }
            assertTrue(text.contains("找不到引用的文件")); assertTrue(text.contains("实际读取路径：\n/fixture/artifacts/moved.zip")); assertTrue(text.contains("不会自动寻找"))
            assertEquals(1, reads); draw(root, "missing-file-path")
        } finally { reviewer.close(); controller.pause().stop().destroy() }
    }
}
