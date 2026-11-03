package dev.herdr.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Operates only on the document returned by the user's save picker, never sibling paths. */
object SavedDocument {
    data class Result(val uri: Uri, val originalName: String, val actualName: String?, val restored: Boolean = false) {
        val nameVerified: Boolean get() = actualName == originalName
    }
    private data class Metadata(val name: String?, val flags: Int?)

    /** Run on an IO thread. A failed write/close must never trigger a rename or a success message. */
    fun write(context: Context, uri: Uri, originalName: String, writer: (OutputStream) -> Unit): Result {
        require(originalName == FileRules.safeName(originalName) && originalName !in listOf(".", ".."))
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
        output.use(writer)
        val before = metadata(context, uri)
        if (before.name == originalName) return Result(uri, originalName, before.name)
        val document = runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)
        val supportsRename = before.flags?.let { it and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0 } ?: true
        if (before.name == null || !document || !supportsRename) return Result(uri, originalName, before.name)

        // Providers can reject a collision or force their suffix again. Make one attempt only;
        // never delete another document, copy over a sibling or invent a filesystem path.
        val renamed = runCatching { DocumentsContract.renameDocument(context.contentResolver, uri, originalName) }.getOrNull()
        if (renamed != null && (renamed.scheme != "content" || renamed.authority != uri.authority ||
                    !runCatching { DocumentsContract.isDocumentUri(context, renamed) }.getOrDefault(false))) {
            return Result(uri, originalName, null)
        }
        val current = renamed ?: uri
        // A successful rename may revoke the old URI. Verify using the returned URI, not the input.
        val after = metadata(context, current)
        return Result(current, originalName, after.name, after.name == originalName)
    }

    private fun metadata(context: Context, uri: Uri): Metadata {
        if (uri.scheme == "file") return Metadata(uri.path?.let { File(it).name }, 0)
        fun query(flags: Boolean): Metadata? = runCatching {
            val columns = if (flags) arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS) else arrayOf(OpenableColumns.DISPLAY_NAME)
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                Metadata(
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } else null,
                    if (flagsIndex >= 0 && !cursor.isNull(flagsIndex)) cursor.getInt(flagsIndex) else null
                )
            }
        }.getOrNull()
        return query(true) ?: query(false) ?: Metadata(null, null)
    }

    /** Call on the main thread. An unverified name is a partial success, not a failed download. */
    fun report(activity: Activity, result: Result) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (result.nameVerified) {
            Toast.makeText(activity, "已保存：${result.originalName}" + if (result.restored) "（已恢复原名）" else "", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(activity).setTitle(if (result.actualName == null) "文件已保存，名称未确认" else "文件已保存，原名未恢复")
            .setMessage("文件内容已保存。\n\n原文件名：${result.originalName}\n实际文件名：${result.actualName ?: "无法读取，未能确认"}\n\n文件管理器可能不支持改名、存在同名文件，或仍自动改后缀。请在文件管理器中核对；必要时手动改名或换一个位置保存。App 不会删除或强制覆盖同名文件。")
            .setPositiveButton("复制原文件名") { _, _ ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("原文件名", result.originalName))
                Toast.makeText(activity, "已复制原文件名", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("知道了", null).show()
    }
}
