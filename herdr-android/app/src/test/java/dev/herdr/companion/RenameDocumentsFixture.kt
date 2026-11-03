package dev.herdr.companion

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Simulates a system document provider which appends .bin and may change its URI on rename. */
class RenameDocumentsFixture(context: Context, initialName: String) : ContentProvider() {
    val authority = "test.documents.${UUID.randomUUID()}"
    val directory = File(context.cacheDir, "rename-${UUID.randomUUID()}").apply { mkdirs() }
    var file = File(directory, initialName).apply { writeBytes(byteArrayOf()) }; private set
    var uri: Uri = DocumentsContract.buildDocumentUri(authority, "created"); private set
    var canRename = true
    var renameMode = "change-uri"
    var namesAvailable = true
    var namesAvailableAfterRename = true
    var rejectWrite = false
    var rejectFlagsProjection = false
    var renameCalls = 0; private set
    val queries = mutableListOf<Uri>()
    val writes = mutableListOf<Uri>()
    init {
        val info = ProviderInfo().apply { this.authority = this@RenameDocumentsFixture.authority; packageName = context.packageName; name = javaClass.name; exported = true; grantUriPermissions = true }
        attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(authority, this)
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(DocumentsContract.PROVIDER_INTERFACE), ResolveInfo().apply { providerInfo = info })
    }
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        queries += uri
        if (rejectFlagsProjection && projection?.contains(DocumentsContract.Document.COLUMN_FLAGS) == true) throw IllegalArgumentException("projection")
        if (uri != this.uri) throw FileNotFoundException("old URI")
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME)
        return MatrixCursor(columns).apply { addRow(columns.map<String, Any?> { when (it) {
            OpenableColumns.DISPLAY_NAME -> if (namesAvailable && (renameCalls == 0 || namesAvailableAfterRename)) file.name else null
            OpenableColumns.SIZE -> file.length()
            DocumentsContract.Document.COLUMN_FLAGS -> if (canRename) DocumentsContract.Document.FLAG_SUPPORTS_RENAME else 0
            else -> null
        } }.toTypedArray()) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri != this.uri || rejectWrite) throw FileNotFoundException("unavailable document")
        writes += uri
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        check(method == "android:renameDocument") { "unexpected provider mutation $method" }
        check(extras!!.getParcelable<Uri>("uri") == uri)
        renameCalls++
        when (renameMode) {
            "throw" -> throw SecurityException("rename denied")
            "null" -> return null
            "foreign-uri" -> return Bundle().apply { putParcelable("uri", Uri.parse("content://other.provider/document/secret")) }
            "ignore" -> return Bundle().apply { putParcelable("uri", uri) }
        }
        val requested = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)!!
        check(requested == File(requested).name)
        val destination = File(directory, requested)
        // A conflicting file must not be overwritten by the fixture or by a client workaround.
        if (destination.exists()) throw FileNotFoundException("name exists")
        check(file.renameTo(destination)); file = destination
        if (renameMode == "change-uri") uri = DocumentsContract.buildDocumentUri(authority, "renamed")
        return Bundle().apply { putParcelable("uri", uri) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("unexpected insert")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("unexpected update")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("unexpected delete")
}
