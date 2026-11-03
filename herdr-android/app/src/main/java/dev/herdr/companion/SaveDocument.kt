package dev.herdr.companion

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

/** The picker must receive the MIME matching the requested suffix, not a generic .bin. */
class SaveDocument : ActivityResultContracts.CreateDocument("application/octet-stream") {
    override fun createIntent(context: Context, input: String) =
        super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE).setType(DocumentMime.forName(input))
}

object DocumentMime {
    private val commonTypes = mapOf(
        "pdf" to "application/pdf", "zip" to "application/zip", "txt" to "text/plain",
        "csv" to "text/csv", "json" to "application/json", "png" to "image/png",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )
    fun forName(name: String, fallback: String = "application/octet-stream"): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        // Content sniffing sees an APK as ZIP; the system installer needs the APK MIME.
        if (extension == "apk") return "application/vnd.android.package-archive"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: commonTypes[extension] ?: fallback
    }
}
