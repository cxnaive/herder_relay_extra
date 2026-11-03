package dev.herdr.companion

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import org.json.JSONObject
import java.util.concurrent.Executors

/** Full-screen, read-only route. The underlying conversation and draft remain mounted. */
class DocumentReviewer(private val context: Context, private val cwd: String,
    private val current: () -> Boolean,
    private val fetch: (String, (JSONObject?, String?) -> Unit) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var closed = false
    private var pdf: PdfPreview? = null
    private var image: ZoomImageView? = null
    private val thumbnails = ConversationImages(context)
    private var selected: ReviewReference? = null
    private val history = ArrayDeque<ReviewReference>()
    private var rawMarkdown = false
    private var textSize = 17f
    private val ink = 0xffe8f0f2.toInt()
    private val muted = 0xff97adb6.toInt()
    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff101b22.toInt()) }
    private val title = label("文档查看", 17f)
    private val subtitle = label("", 11f).apply { setTextColor(muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
    private val controls = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val body = FrameLayout(context)
    private val dialog = object : Dialog(context, android.R.style.Theme_Material_NoActionBar) {
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onBackPressed() { goBack() }
    }
    init {
        root.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(button("‹", "返回上一份文档或对话") { goBack() }, LinearLayout.LayoutParams(dp(52), dp(56)))
            addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; title.maxLines = 1; title.ellipsize = android.text.TextUtils.TruncateAt.END; addView(title); addView(subtitle) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("×", "关闭文档查看器") { close() }, LinearLayout.LayoutParams(dp(52), dp(56)))
        })
        root.addView(controls, LinearLayout.LayoutParams(-1, -2)); root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.setOnDismissListener { dispose() }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
    }
    val requestVersion: Int get() = generation
    val isOpen: Boolean get() = !closed && dialog.isShowing
    val activePath: String? get() = if (closed) null else selected?.path

    fun open(ref: ReviewReference) {
        if (closed || !current()) return
        if (!dialog.isShowing) {
            dialog.show(); dialog.window?.let { WindowCompat.setDecorFitsSystemWindows(it, false); it.setLayout(-1, -1); it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN) }
        }
        navigate(ref, false)
    }
    fun openLink(link: String, from: ReviewReference? = selected) {
        val ref = ReviewReferences.local(link, cwd, from?.path.orEmpty())
        if (ref != null) { navigate(ref, true); return }
        confirmExternal(context, link)
    }
    private fun goBack() { if (history.isNotEmpty()) navigate(history.removeLast(), false) else close() }
    fun close() { if (dialog.isShowing) dialog.dismiss() else dispose() }
    private fun dispose() {
        if (closed) return
        closed = true; generation++; pdf?.close(); pdf = null; image?.setBitmap(null); image = null; body.removeAllViews(); thumbnails.close(); history.clear(); selected = null; worker.shutdownNow()
    }
    private fun navigate(ref: ReviewReference, remember: Boolean) {
        if (closed) return
        if (!current()) { close(); return }
        if (remember) selected?.let { if (history.size >= 12) history.removeFirst(); history.addLast(it) }
        selected = ref; val owner = ++generation
        pdf?.close(); pdf = null; image?.setBitmap(null); image = null; thumbnails.clear(); controls.removeAllViews(); body.removeAllViews()
        title.text = ref.label; subtitle.text = "${ref.kind.uppercase()} · ${ref.path.ifBlank { "对话内嵌图像" }}"
        body.addView(label("正在读取原始文件…", 15f).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        fun accept(data: JSONObject?, error: String?) {
            if (closed || owner != generation) return
            if (!current()) { close(); return }
            if (error != null || data == null) { failure(error ?: "未返回文件", ref); return }
            worker.execute {
                try {
                    val bytes = ReviewDecoding.bytes(data)
                    val kind = data.optString("kind")
                    val bitmap = if (kind == "image") ReviewDecoding.image(bytes) else null
                    val text = if (kind == "text") { require(bytes.size <= 1024 * 1024); bytes.toString(Charsets.UTF_8) } else null
                    main.post {
                        if (closed || owner != generation) { bitmap?.recycle(); return@post }
                        if (!current()) { bitmap?.recycle(); close(); return@post }
                        body.removeAllViews()
                        when {
                            bitmap != null -> showImage(bitmap)
                            kind == "pdf" -> { pdf = PdfPreview(context, bytes, ref.page); body.addView(pdf, FrameLayout.LayoutParams(-1, -1)) }
                            text != null -> { rawMarkdown = ref.line > 0; showMarkdown(ref, text) }
                            else -> failure("此文件不是可预览的图片、PDF 或文本", ref)
                        }
                    }
                } catch (_: Exception) { main.post { if (!closed && owner == generation) failure("文件无法解码、已损坏或超过预览大小限制", ref) } }
            }
        }
        if (ref.inlineImage != null) accept(JSONObject().put("kind", "image").put("data_url", ref.inlineImage), null)
        else fetch(ref.path, ::accept)
    }
    private fun showImage(bitmap: android.graphics.Bitmap) {
        val view = ZoomImageView(context); image = view
        val percent = button("适合", "还原图片缩放") { view.fitScreen() }
        controls.addView(button("−", "缩小图片") { view.changeZoom(view.viewport.zoom / 1.5f) }, LinearLayout.LayoutParams(dp(52), dp(48)))
        controls.addView(percent, LinearLayout.LayoutParams(dp(78), dp(48)))
        controls.addView(button("+", "放大图片") { view.changeZoom(view.viewport.zoom * 1.5f) }, LinearLayout.LayoutParams(dp(52), dp(48)))
        controls.addView(label("双指缩放 · 双击放大\n拖动查看细节", 11f).apply { setTextColor(muted); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, dp(48), 1f))
        view.onZoom = { percent.text = if (it <= 1.01f) "适合" else "${(it * 100).toInt()}%" }
        view.setBitmap(bitmap); body.addView(view, FrameLayout.LayoutParams(-1, -1))
    }
    private fun showMarkdown(ref: ReviewReference, text: String) {
        controls.removeAllViews(); body.removeAllViews()
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(32)) }
        val value = label("", textSize).apply { setTextIsSelectable(true); setLineSpacing(0f, 1f) }
        fun resizeText(delta: Float) {
            val previousY = scroll.scrollY
            textSize = (textSize + delta).coerceIn(12f, 28f); showMarkdown(ref, text)
            (body.getChildAt(0) as? ScrollView)?.let { replacement -> replacement.post { replacement.scrollTo(0, previousY) } }
        }
        controls.addView(button("A−", "减小文字") { resizeText(-1f) }, LinearLayout.LayoutParams(dp(60), dp(48)))
        controls.addView(button("A+", "增大文字") { resizeText(1f) }, LinearLayout.LayoutParams(dp(60), dp(48)))
        controls.addView(button(if (rawMarkdown) "排版" else "源码", "切换 Markdown 排版与源码") { rawMarkdown = !rawMarkdown; showMarkdown(ref, text) }, LinearLayout.LayoutParams(dp(84), dp(48)))
        controls.addView(label("只读 · 可选择复制", 11f).apply { setTextColor(muted) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (rawMarkdown) { value.typeface = Typeface.MONOSPACE; value.text = text }
        else Markwon.builder(context).usePlugin(CodexFileCitations.plugin()).usePlugin(TablePlugin.create(context)).usePlugin(StrikethroughPlugin.create())
            .usePlugin(object : io.noties.markwon.AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: io.noties.markwon.MarkwonConfiguration.Builder) { builder.linkResolver { _, link -> openLink(link, ref) } }
            }).build().setMarkdown(value, text)
        if (!rawMarkdown) value.movementMethod = TableAwareMovementMethod.create()
        column.addView(value)
        if (!rawMarkdown) for (imageRef in ReviewReferences.markdownImages(text, cwd, ref.path)) {
            val image = ConversationPresentation.Image(imageRef.path, imageRef.label, imageRef.path)
            val owner = generation
            column.addView(thumbnails.view(image, "$owner:${imageRef.path}", { navigate(imageRef, true) }) { path, done ->
                fetch(path) { data, error ->
                    if (owner == generation && !closed) {
                        val raw = data?.optString("data_url").orEmpty().ifBlank { data?.optString("data").orEmpty() }
                        done(raw, error)
                    }
                }
            })
        }
        scroll.addView(column); body.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        scroll.post {
            if (closed || selected != ref) return@post
            val layout = value.layout ?: return@post
            val line = if (rawMarkdown && ref.line > 0) {
                var offset = 0; repeat((ref.line - 1).coerceAtMost(text.count { it == '\n' })) { offset = text.indexOf('\n', offset).let { if (it < 0) text.length else it + 1 } }
                layout.getLineForOffset(offset.coerceAtMost(value.text.length))
            } else if (ref.anchor.isNotBlank()) {
                val heading = text.lineSequence().filter { it.startsWith('#') }.map { it.trimStart('#').trim().trimEnd('#').trim() }
                    .firstOrNull { it.lowercase().replace(Regex("[^\\p{L}\\p{N} _-]"), "").replace(' ', '-') == ref.anchor.lowercase() }
                val offset = if (heading != null) value.text.indexOf(heading) else -1
                if (offset >= 0) layout.getLineForOffset(offset) else 0
            } else 0
            scroll.scrollTo(0, layout.getLineTop(line.coerceIn(0, layout.lineCount - 1)))
        }
    }
    private fun failure(message: String, ref: ReviewReference) {
        body.removeAllViews()
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(label(message, 15f)); addView(button("重新读取", "重试读取文档") { navigate(ref, false) })
        }, FrameLayout.LayoutParams(-1, -1))
    }
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float) = TextView(context).apply { this.text = text; textSize = size; setTextColor(ink) }
    private fun button(text: String, description: String, click: () -> Unit) = Button(context).apply {
        this.text = text; textSize = 13f; isAllCaps = false; setTextColor(ink); background = android.graphics.drawable.InsetDrawable(AgentStyle.shape(context, 0xff1d323c.toInt(), 12), dp(3), dp(4), dp(3), dp(4)); minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0); contentDescription = description; setOnClickListener { click() }
    }
    companion object {
        fun confirmExternal(context: Context, link: String) {
            val uri = runCatching { Uri.parse(link) }.getOrNull()
            if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) { Toast.makeText(context, "只能预览当前工作区内的图片、PDF 和 Markdown；该引用不可访问", Toast.LENGTH_LONG).show(); return }
            AlertDialog.Builder(context).setTitle("打开外部链接？").setMessage("将交给浏览器打开，不会发送 Relay 配对凭据。\n\n$link")
                .setPositiveButton("浏览器打开") { _, _ -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show() } }
                .setNegativeButton("取消", null).show()
        }
    }
}
