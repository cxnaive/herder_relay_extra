package dev.herdr.companion

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import org.robolectric.shadows.ShadowDialog
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
class FileAccessTcpUiTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun setup() {
        SharedRelay.resetForTest()
        // AndroidX caches provider roots across Robolectric's per-test temp directories.
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
        for (name in listOf("private_state", "background-monitor", "file-browser")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private var debug: () -> String = { "" }
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime()+30_000_000_000L
        while (System.nanoTime()<end) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); if (condition()) return; Thread.sleep(5) }
        fail("encrypted file UI condition timed out: ${debug()}")
    }
    @Test fun encryptedLargeDownloadAndOptInDirectoryNavigationKeepExactAgent() {
        MockTcpMonitorRelay().use { fixture ->
            fixture.role = "controller"
            fixture.extraCapabilities += listOf("workspace_directory_v1", "workspace_file_read_v1", "workspace_file_download_v1", "filesystem_read_opt_in_v1")
            val allowed = AtomicBoolean(false); val scopes = CopyOnWriteArrayList<String>(); val types = CopyOnWriteArrayList<String>()
            val total = 13*1024*1024+17; val revision = "d".repeat(64)
            fixture.foregroundCommand = { request ->
                val type = request.getString("type"); types += type
                when (type) {
                    "file_access_get" -> JSONObject().put("filesystem_read", allowed.get())
                    "file_access_set" -> { allowed.set(request.getBoolean("filesystem_read")); JSONObject().put("filesystem_read", allowed.get()) }
                    "workspace_list", "workspace_file_download" -> {
                        val target = request.getJSONObject("target")
                        check(target.getString("terminal_id") == "terminal-a" && target.getString("agent_session_id") == "session-a")
                        check(request.getString("cwd") == "/fixture/project")
                        val scope = request.getString("file_scope"); check(scope == "workspace" || allowed.get()); scopes += scope
                        val path = request.optString("path")
                        if (type == "workspace_list") {
                            val name = if (scope == "filesystem") "tmp" else "bundle.zip"
                            val entry = JSONObject().put("path", if (path.isEmpty()) name else "$path/$name").put("name", name).put("kind", if (scope == "filesystem") "directory" else "file")
                            JSONObject().put("root", if (scope == "filesystem") "/" else "/fixture/project").put("path", path).put("revision", "dir-v1").put("entries", JSONArray().put(entry)).put("total", 1).put("next_cursor", "")
                        } else if (request.optBoolean("metadata_only")) {
                            JSONObject().put("path", path).put("size", total).put("kind", "binary").put("media_type", "application/zip")
                                .put("metadata_only", true).put("download_revision", revision).put("preview_available", false).put("preview_reason", "文件超过 12 MiB 预览限制；仍可下载或分享原文件")
                        } else {
                            check(request.getString("expected_revision") == revision)
                            val offset = request.optString("cursor").toIntOrNull() ?: 0; val end = minOf(total, offset + 512*1024)
                            val bytes = ByteArray(end-offset) { ((it+offset)%251).toByte() }
                            JSONObject().put("path", path).put("size", total).put("offset", offset).put("download_revision", revision)
                                .put("data", Base64.getEncoder().encodeToString(bytes)).put("chunk_sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                                .put("next_cursor", if (end < total) end.toString() else "")
                        }
                    }
                    "unwatch_pane", "unwatch_conversation", "release_pane_size" -> null
                    else -> error("unexpected request: $type")
                }
            }
            val shared = SharedRelay.get(context)
            shared.vault.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }.set(shared.vault, lazyOf(javax.crypto.spec.SecretKeySpec(ByteArray(32) { 21 }, "AES")))
            shared.vault.write("state", fixture.state().toString()); shared.engine.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(shared.engine, fixture.state())
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); val activity = controller.get(); val root = activity.window.decorView
            debug = { "toast=${org.robolectric.shadows.ShadowToast.getTextOfLatestToast()} count=${types.size} files=${java.io.File(activity.cacheDir, "shared").walkTopDown().filter { it.isFile }.map { it.length() }.toList()} requests=${types.takeLast(8)} scopes=${scopes.takeLast(5)} failures=${fixture.failures.map { it.toString() }} UI=${views(root).filterIsInstance<TextView>().map { it.text }} dialog=${(ShadowDialog.getLatestDialog() as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.text}" }
            fun click(text: String) {
                var view: View? = views(root).filterIsInstance<TextView>().first { it.text.toString().contains(text) }
                while (view != null && !view.performClick()) view = view.parent as? View
                assertNotNull("click $text", view)
            }
            try {
                waitUntil { shared.ready("fixture") }
                activity.javaClass.getDeclaredMethod("selectWorkspace", String::class.java, JSONObject::class.java).apply { isAccessible = true }.invoke(activity, "fixture", fixture.agent("a"))
                waitUntil { views(root).filterIsInstance<TextView>().any { it.text.toString() == "bundle.zip" } }
                click("bundle.zip")
                waitUntil { views(root).filterIsInstance<TextView>().any { it.text.toString().contains("超过 12 MiB") } }
                assertFalse(types.contains("workspace_file_read")); click("分享")
                waitUntil { shadowOf(activity).peekNextStartedActivity() != null }
                val chooser = shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!; val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                var count = 0
                activity.contentResolver.openInputStream(uri)!!.use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; for (i in 0 until n) assertEquals(((count+i)%251).toByte(), buffer[i]); count += n }
                }
                assertEquals(total, count); assertEquals(29, types.count { it == "workspace_file_download" }) // open metadata + download metadata + 27 chunks
                assertTrue(scopes.all { it == "workspace" })
                activity.javaClass.getDeclaredMethod("changeTab", String::class.java).apply { isAccessible = true }.invoke(activity, "devices")
                click("工作区外文件访问：关闭")
                waitUntil { (ShadowDialog.getLatestDialog() as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.text == "我了解风险，开启" }
                assertFalse(allowed.get())
                (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitUntil { allowed.get() && views(root).filterIsInstance<TextView>().any { it.text.toString().contains("工作区外文件访问：已开启") } }
                activity.javaClass.getDeclaredMethod("selectWorkspace", String::class.java, JSONObject::class.java).apply { isAccessible = true }.invoke(activity, "fixture", fixture.agent("a"))
                waitUntil { scopes.contains("filesystem") && views(root).filterIsInstance<TextView>().any { it.text.toString() == "/ 根目录" } }
                click("/ 根目录")
                waitUntil { views(root).filterIsInstance<TextView>().any { it.text.toString() == "/" } }
                activity.javaClass.getDeclaredMethod("changeTab", String::class.java).apply { isAccessible = true }.invoke(activity, "devices")
                click("工作区外文件访问：已开启")
                waitUntil { (ShadowDialog.getLatestDialog() as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.text == "关闭访问" }
                (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                waitUntil { !allowed.get() && views(root).filterIsInstance<TextView>().any { it.text.toString().contains("工作区外文件访问：关闭") } }
                assertTrue(fixture.failures.isEmpty())
            } finally { controller.pause().stop().destroy() }
        }
    }
}
