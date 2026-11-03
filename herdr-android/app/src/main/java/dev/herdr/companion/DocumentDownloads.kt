package dev.herdr.companion

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Foreground transfers over the existing authenticated transport; no public download URLs. */
class DocumentDownloads(private val activity: ComponentActivity) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var closed = false
    private var dialog: AlertDialog? = null
    private var sink: DownloadSink? = null
    private var folder: File? = null
    private var pendingSave: File? = null
    private var pendingValid: (() -> Boolean)? = null
    private var monitor: Runnable? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = pendingSave; val valid = pendingValid; pendingSave = null; pendingValid = null
        if (file != null) {
            val allowed = !closed && valid?.invoke() == true
            worker.execute {
                val result = runCatching {
                    if (uri != null && allowed) activity.contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
                }
                file.parentFile?.deleteRecursively()
                main.post { if (uri != null) toast(if (!allowed) "Agent 已变化，已取消保存" else if (result.isSuccess) "已保存到所选位置" else "保存失败，请检查存储空间；所选位置可能留有不完整文件") }
            }
        }
    }
    fun start(path: String, share: Boolean, valid: () -> Boolean,
        request: (JSONObject, (JSONObject?, String?) -> Unit) -> Unit) {
        if (closed || !valid()) return
        if (pendingSave != null) { toast("请先完成当前文件的保存"); return }
        cancel()
        val owner = generation
        val started = SystemClock.elapsedRealtime()
        val label = TextView(activity).apply { text = "正在读取文件信息…"; textSize = 15f }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; max = 1000 }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; val p = (24 * resources.displayMetrics.density).toInt(); setPadding(p,p,p,p); addView(label); addView(progress, LinearLayout.LayoutParams(-1,-2)) }
        dialog = AlertDialog.Builder(activity).setTitle(if (share) "下载原文件后分享" else "下载原文件")
            .setView(content).setNegativeButton("取消") { _, _ -> cancel() }.create().also { it.setCanceledOnTouchOutside(false); it.setOnCancelListener { cancel() }; it.show() }
        fun alive() = !closed && owner == generation && valid()
        fun fail(message: String) { if (owner == generation) { cancel(); toast(message) } }
        monitor = object : Runnable { override fun run() {
            if (owner != generation || closed) return
            if (!valid()) { fail("Agent 或文件页面已变化，下载已取消"); return }
            if (SystemClock.elapsedRealtime() - started > 3_600_000L) { fail("下载超过一小时，请重试"); return }
            main.postDelayed(this, 500)
        } }.also { main.post(it) }
        request(JSONObject().put("path", path).put("metadata_only", true)) metadata@ { data, error ->
            if (!alive()) { if (owner == generation) fail("Agent 已变化，下载已取消"); return@metadata }
            if (error != null || data == null) { fail(error ?: "未收到文件信息"); return@metadata }
            try { DownloadSink.validateMetadata(data, path) } catch (_: Exception) { fail("文件信息无效，请重新打开"); return@metadata }
            val size = data.getLong("size"); val revision = data.getString("download_revision")
            val dir = File(activity.cacheDir, "shared/${UUID.randomUUID()}"); folder = dir
            val output = File(dir, FileRules.safeName(path).takeUnless { it == "." || it == ".." } ?: "download")
            val mime = data.optString("media_type").ifBlank { "application/octet-stream" }
            fun next(current: DownloadSink) {
                if (!alive()) { if (owner == generation) fail("Agent 已变化，下载已取消"); return }
                label.text = "${output.name}\n${FileRules.size(current.received)} / ${FileRules.size(size)}"
                progress.isIndeterminate = false; progress.progress = if (size == 0L) 0 else (current.received.toDouble() / size * 1000).toInt()
                val payload = JSONObject().put("path", path).put("expected_revision", revision)
                if (current.received > 0) payload.put("cursor", current.received.toString())
                request(payload) chunk@ { part, problem ->
                    if (!alive()) { if (owner == generation) fail("Agent 已变化，下载已取消"); return@chunk }
                    if (problem != null || part == null) { fail(problem ?: "连接中断，请重试下载"); return@chunk }
                    worker.execute {
                        val result = runCatching { current.accept(part) }
                        main.post {
                            if (!alive()) { if (owner == generation) fail("Agent 已变化，下载已取消"); return@post }
                            if (result.isFailure) { fail("下载不完整、文件已变化或存储空间不足，请重试"); return@post }
                            if (result.getOrThrow()) {
                                monitor?.let(main::removeCallbacks); monitor = null; dialog?.dismiss(); dialog = null; sink = null; folder = null
                                if (share) share(output, mime) else {
                                    pendingSave = output; pendingValid = valid
                                    runCatching { picker.launch(output.name) }.onFailure { pendingSave = null; pendingValid = null; worker.execute { dir.deleteRecursively() }; toast("无法打开保存位置选择器") }
                                }
                            } else next(current)
                        }
                    }
                }
            }
            worker.execute {
                val result = runCatching {
                    check(dir.mkdirs())
                    require(size <= dir.usableSpace - 8 * 1024 * 1024) { "存储空间不足" }
                    DownloadSink(output, path, revision, size)
                }
                main.post {
                    if (!alive()) { worker.execute { result.getOrNull()?.discard(); dir.deleteRecursively() }; if (owner == generation) fail("Agent 已变化，下载已取消"); return@post }
                    val current = result.getOrNull()
                    if (current == null) { fail("无法创建下载文件，请检查手机存储空间"); return@post }
                    sink = current; next(current)
                }
            }
        }
    }
    private fun share(file: File, mime: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri(file.name, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            activity.startActivity(Intent.createChooser(send, "分享 ${file.name}"))
        }.onFailure { worker.execute { file.parentFile?.deleteRecursively() }; toast("没有可用的分享应用，请改用下载") }
    }
    fun cancel() {
        generation++; monitor?.let(main::removeCallbacks); monitor = null; dialog?.dismiss(); dialog = null
        val old = sink; val dir = folder; sink = null; folder = null
        if (old != null || dir != null) worker.execute { old?.discard(); dir?.deleteRecursively() }
    }
    fun close() {
        if (closed) return
        closed = true; cancel()
        val file = pendingSave; pendingSave = null; pendingValid = null
        if (file != null) worker.execute { file.parentFile?.deleteRecursively() }
        // Drain worker results and their main-thread cleanup before shutting down.
        worker.execute { main.post { worker.shutdown() } }
    }
    private fun toast(message: String) { if (!closed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
}
