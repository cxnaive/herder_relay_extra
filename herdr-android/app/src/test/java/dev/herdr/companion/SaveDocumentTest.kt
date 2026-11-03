package dev.herdr.companion

import android.app.Activity
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class SaveDocumentTest {
    @Test fun saveIntentKeepsRequestedFilenameAndMatchesItsExtension() {
        val names = mapOf(
            "Herdr-Pocket-0.2.19-debug.apk" to "application/vnd.android.package-archive",
            "报告 (最终).PDF" to "application/pdf",
            "source.zip" to "application/zip",
            "SHA256SUMS.txt" to "text/plain",
            "data.csv" to "text/csv",
            "figure.png" to "image/png",
            "image.jpeg" to "image/jpeg",
            "photo.webp" to "image/webp",
            "table.xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "run.custom-extension" to "application/octet-stream",
            "LICENSE" to "application/octet-stream"
        )
        for ((name, mime) in names) {
            val intent = SaveDocument().createIntent(RuntimeEnvironment.getApplication(), name)
            assertEquals(name, name, intent.getStringExtra(Intent.EXTRA_TITLE))
            assertEquals(name, mime, intent.type)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        }
    }
    @Test fun apkFilenameOverridesZipSniffingAndCancellationStillReturnsNoUri() {
        assertEquals("application/vnd.android.package-archive", DocumentMime.forName("Pocket.APK", "application/zip"))
        assertEquals("application/zip", DocumentMime.forName("source.zip", "application/octet-stream"))
        val uri = Uri.parse("content://documents/downloads/123")
        assertEquals(uri, SaveDocument().parseResult(Activity.RESULT_OK, Intent().setData(uri)))
        assertNull(SaveDocument().parseResult(Activity.RESULT_CANCELED, Intent().setData(uri)))
    }
}
