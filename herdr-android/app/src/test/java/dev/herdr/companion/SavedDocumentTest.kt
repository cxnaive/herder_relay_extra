package dev.herdr.companion

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedDocumentTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val bytes = ByteArray(65537) { (it % 251).toByte() }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    @Test fun restoresArbitraryOriginalNamesAndRequeriesTheReturnedUri() {
        for (name in listOf("model.custom-vla", "output.any_suffix-123", "日志 (最终).ABC", "README", ".env", "archive.tar.gz", "Pocket.apk", "original.bin")) {
            val provider = RenameDocumentsFixture(context, "$name.bin")
            val originalUri = provider.uri
            try {
                val result = SavedDocument.write(context, originalUri, name) { it.write(bytes) }
                assertTrue(name, result.nameVerified); assertTrue(result.restored); assertEquals(name, result.actualName)
                assertEquals(provider.uri, result.uri); assertNotEquals(originalUri, result.uri)
                assertEquals(result.uri, provider.queries.last()); assertEquals(listOf(originalUri), provider.writes)
                assertArrayEquals(bytes, context.contentResolver.openInputStream(result.uri)!!.use { it.readBytes() })
                assertEquals(1, provider.renameCalls)
            } finally { provider.directory.deleteRecursively() }
        }
    }
    @Test fun matchingNameIsNotRenamedAndSameUriRenameIsVerified() {
        val provider = RenameDocumentsFixture(context, "weights.bin")
        try {
            val first = SavedDocument.write(context, provider.uri, "weights.bin") { it.write(bytes) }
            assertTrue(first.nameVerified); assertFalse(first.restored); assertEquals(0, provider.renameCalls)
            provider.renameMode = "same-uri"
            val second = SavedDocument.write(context, provider.uri, "weights.custom") { it.write(bytes) }
            assertTrue(second.nameVerified); assertEquals(first.uri, second.uri); assertEquals(1, provider.renameCalls)
        } finally { provider.directory.deleteRecursively() }
    }
    @Test fun deniedUnsupportedIgnoredOrNullRenameKeepsSavedBytesAndReportsActualName() {
        for (mode in listOf("throw", "null", "ignore", "unsupported")) {
            val provider = RenameDocumentsFixture(context, "weights.custom.bin").apply { renameMode = mode; canRename = mode != "unsupported" }
            try {
                val result = SavedDocument.write(context, provider.uri, "weights.custom") { it.write(bytes) }
                assertFalse(mode, result.nameVerified); assertFalse(result.restored); assertEquals("weights.custom.bin", result.actualName)
                assertArrayEquals(bytes, provider.file.readBytes()); assertEquals(if (mode == "unsupported") 0 else 1, provider.renameCalls)
            } finally { provider.directory.deleteRecursively() }
        }
    }
    @Test fun conflictsAreNotOverwrittenAndFilenameCannotBeAssumedWhenRequeryFails() {
        val provider = RenameDocumentsFixture(context, "model.custom.bin")
        val existing = File(provider.directory, "model.custom").apply { writeText("original other file") }
        try {
            val result = SavedDocument.write(context, provider.uri, "model.custom") { it.write(bytes) }
            assertFalse(result.nameVerified); assertEquals("model.custom.bin", result.actualName)
            assertEquals("original other file", existing.readText()); assertArrayEquals(bytes, provider.file.readBytes())
            provider.namesAvailableAfterRename = false
            val second = SavedDocument.write(context, provider.uri, "different.custom") { it.write(bytes) }
            assertFalse(second.nameVerified); assertNull(second.actualName); assertEquals(provider.uri, second.uri)
        } finally { provider.directory.deleteRecursively() }
    }
    @Test fun missingMetadataDoesNotTriggerBlindRenameAndWriteFailuresDoNotRename() {
        val provider = RenameDocumentsFixture(context, "document.bin")
        try {
            provider.namesAvailable = false
            val result = SavedDocument.write(context, provider.uri, "document.custom") { it.write(bytes) }
            assertFalse(result.nameVerified); assertNull(result.actualName); assertEquals(0, provider.renameCalls)
            provider.namesAvailable = true
            assertTrue(runCatching { SavedDocument.write(context, provider.uri, "document.custom") { it.write(1); throw IOException("write interrupted") } }.isFailure)
            assertEquals(0, provider.renameCalls)
            provider.rejectWrite = true
            assertTrue(runCatching { SavedDocument.write(context, provider.uri, "document.custom") { it.write(bytes) } }.isFailure)
            assertEquals(0, provider.renameCalls)
        } finally { provider.directory.deleteRecursively() }
    }
    @Test fun unresolvedNameShowsPersistentPartialSuccessAndCanCopyTheOriginal() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible(); val activity = controller.get()
        val provider = RenameDocumentsFixture(activity, "result.custom.bin").apply { canRename = false }
        try {
            val result = SavedDocument.write(activity, provider.uri, "result.custom") { it.write(bytes) }
            SavedDocument.report(activity, result); shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val labels = views(dialog.window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text }
            assertTrue(labels.contains("文件已保存，原名未恢复")); assertTrue(labels.contains("原文件名：result.custom")); assertTrue(labels.contains("实际文件名：result.custom.bin"))
            val root = dialog.window!!.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST)); root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(image))
            val directory = File("build/reports/restore-name").apply { mkdirs() }
            File(directory, "partial-success.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals("result.custom", (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString())
            provider.canRename = true
            SavedDocument.report(activity, SavedDocument.write(activity, provider.uri, "result.custom") { it.write(bytes) })
            assertEquals("已保存：result.custom（已恢复原名）", ShadowToast.getTextOfLatestToast())
        } finally { provider.directory.deleteRecursively(); controller.pause().stop().destroy() }
    }
    @Test fun projectionFallbackWorksAndUnexpectedProviderUrisAreNotFollowed() {
        val provider = RenameDocumentsFixture(context, "weights.custom.bin").apply { rejectFlagsProjection = true }
        try {
            val first = SavedDocument.write(context, provider.uri, "weights.custom") { it.write(bytes) }
            assertTrue(first.nameVerified); assertEquals(1, provider.renameCalls)
            provider.renameMode = "foreign-uri"
            val second = SavedDocument.write(context, provider.uri, "different.custom") { it.write(bytes) }
            assertFalse(second.nameVerified); assertNull(second.actualName)
            assertTrue(provider.queries.all { it.authority == provider.authority })
            assertArrayEquals(bytes, provider.file.readBytes())
        } finally { provider.directory.deleteRecursively() }
    }

}
