package dev.herdr.companion

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocumentDownloadsUiTest {
    @Before fun resetProviderCache() {
        // AndroidX is shared while Robolectric replaces the app's private cache root.
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }
    private val bytes = ByteArray(900_001) { (it % 251).toByte() }
    private val revision = "c".repeat(64)
    private fun metadata() = JSONObject().put("path", "bundle.zip").put("size", bytes.size).put("metadata_only", true)
        .put("media_type", "application/zip").put("download_revision", revision).put("preview_available", false)
    private fun part(offset: Int): JSONObject {
        val end = minOf(offset + 512*1024, bytes.size); val data = bytes.copyOfRange(offset,end)
        return JSONObject().put("path", "bundle.zip").put("size", bytes.size).put("offset", offset).put("download_revision", revision)
            .put("data", Base64.getEncoder().encodeToString(data)).put("chunk_sha256", MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
            .put("next_cursor", if (end < bytes.size) end.toString() else "")
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime()+10_000_000_000L
        while (System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); if (condition()) return; Thread.sleep(5) }
        fail("download condition timed out: ${org.robolectric.shadows.ShadowToast.getTextOfLatestToast()}")
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    @Test fun downloadsToSystemSavePickerAndSharesVerifiedFileWithoutPreview() {
        for (share in listOf(false, true)) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java); val activity = controller.get(); val downloads = DocumentDownloads(activity); controller.setup()
            File(activity.cacheDir,"shared").deleteRecursively()
            var requests = 0
            try {
                downloads.start("bundle.zip", share, { true }) { request, done ->
                    requests++
                    if (request.optBoolean("metadata_only")) done(metadata(), null) else {
                        assertEquals(revision, request.getString("expected_revision")); done(part(request.optString("cursor").toIntOrNull() ?: 0), null)
                    }
                }
                waitUntil { shadowOf(activity).peekNextStartedActivity() != null }
                assertEquals(3, requests)
                val intent = shadowOf(activity).nextStartedActivity
                if (share) {
                    assertEquals(Intent.ACTION_CHOOSER, intent.action)
                    val send = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!; assertEquals(Intent.ACTION_SEND, send.action)
                    val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                    assertArrayEquals(bytes, activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                    assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                } else {
                    assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action); assertEquals("bundle.zip", intent.getStringExtra(Intent.EXTRA_TITLE))
                    val target = File(activity.cacheDir,"saved.zip")
                    shadowOf(activity).receiveResult(intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(target)))
                    waitUntil { target.length() == bytes.size.toLong() }
                    assertArrayEquals(bytes, target.readBytes()); target.delete()
                    waitUntil { File(activity.cacheDir,"shared").walkTopDown().none { it.isFile } }
                }
            } finally { downloads.close(); controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle() }
        }
    }
    @Test fun cancellationAndScopeChangeRemovePartialFilesAndIgnoreLateResponses() {
        for (scopeChange in listOf(false, true)) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java); val activity = controller.get(); val downloads = DocumentDownloads(activity); controller.setup()
            File(activity.cacheDir,"shared").deleteRecursively()
            var valid = true; var pending: ((JSONObject?,String?) -> Unit)? = null
            try {
                downloads.start("bundle.zip", false, { valid }) { request, done ->
                    if (request.optBoolean("metadata_only")) done(metadata(), null)
                    else if (request.optString("cursor").isEmpty()) done(part(0), null) else pending = done
                }
                waitUntil { pending != null }
                assertTrue(views(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<ProgressBar>().any { !it.isIndeterminate && it.progress > 0 })
                if (scopeChange) valid = false else downloads.cancel()
                waitUntil { !ShadowDialog.getLatestDialog().isShowing }
                pending!!(part(512*1024), null)
                waitUntil { File(activity.cacheDir,"shared").walkTopDown().none { it.isFile } }
                assertNull(shadowOf(activity).peekNextStartedActivity())
            } finally { downloads.close(); controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle() }
        }
    }
}
