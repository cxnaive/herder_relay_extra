package dev.herdr.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SavedDocumentPickerUiTest {
    @Before fun setup() {
        SharedRelay.resetForTest()
        val context = RuntimeEnvironment.getApplication() as Context
        for (name in listOf("private_state", "background-monitor", "file-browser")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    @Test fun legacyAndDraftSaveCallbackRestoresOriginalNameAndCancelDoesNotWriteOrRename() {
        for (cancel in listOf(false, true)) {
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); val activity = controller.get()
            val provider = RenameDocumentsFixture(activity, "draft.custom.bin")
            val bytes = "只在手机导出的草稿\n".toByteArray()
            try {
                activity.javaClass.getDeclaredField("pendingExport").apply { isAccessible = true }.set(activity, "draft.custom" to bytes)
                @Suppress("UNCHECKED_CAST")
                val picker = activity.javaClass.getDeclaredField("picker").apply { isAccessible = true }.get(activity) as ActivityResultLauncher<String>
                picker.launch("draft.custom")
                val intent = shadowOf(activity).nextStartedActivity
                assertEquals("draft.custom", intent.getStringExtra(Intent.EXTRA_TITLE))
                controller.pause().stop()
                shadowOf(activity).receiveResult(intent, if (cancel) Activity.RESULT_CANCELED else Activity.RESULT_OK, if (cancel) null else Intent().setData(provider.uri))
                controller.restart().start().resume()
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!cancel && provider.file.name != "draft.custom" && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5) }
                shadowOf(Looper.getMainLooper()).idle()
                if (cancel) { assertEquals(0, provider.renameCalls); assertTrue(provider.writes.isEmpty()) }
                else { assertEquals("draft.custom", provider.file.name); assertArrayEquals(bytes, provider.file.readBytes()); assertEquals(1, provider.renameCalls) }
            } finally { controller.pause().stop().destroy(); provider.directory.deleteRecursively() }
        }
    }
}
