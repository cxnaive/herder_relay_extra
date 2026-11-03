package dev.herdr.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Base64
import java.util.concurrent.Executors

/** Only loads embedded bytes or files through the authenticated, exact-agent relay request. No arbitrary URL fetches. */
class ConversationImages(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private data class Failure(val attempts: Int, val retryAt: Long)
    private val failures = linkedMapOf<String, Failure>()
    private val pending = mutableMapOf<String, Any>()
    private val viewers = mutableSetOf<() -> Unit>()
    private var active = true
    private var connected = true
    private var scope = 0L
    private var closed = false
    private val refresh = Runnable { viewers.toList().forEach { it() } }
    private val retry = Runnable { refreshViews() }
    private fun refreshViews() { main.removeCallbacks(refresh); if (!closed) main.post(refresh) }
    fun setAvailability(active: Boolean, connected: Boolean) {
        if (closed || (this.active == active && this.connected == connected)) return
        this.active = active; this.connected = connected
        // Invalidated reads may still finish after pause/reconnect; their tokens can no longer update the cache.
        pending.clear(); failures.clear(); main.removeCallbacks(retry)
        refreshViews()
    }
    fun clear() { scope++; pending.clear(); failures.clear(); cache.evictAll(); main.removeCallbacks(retry); refreshViews() }
    fun close() { closed = true; clear(); viewers.clear(); main.removeCallbacksAndMessages(null); worker.shutdownNow() }
    private fun failed(key: String, token: Any, retryable: Boolean) {
        if (closed || pending[key] !== token) return
        pending.remove(key)
        val attempts = (failures[key]?.attempts ?: 0) + 1
        val retryAt = if (retryable && attempts < 3) SystemClock.uptimeMillis() + attempts * 1500L else Long.MAX_VALUE
        failures[key] = Failure(attempts, retryAt)
        while (failures.size > 256) failures.remove(failures.keys.first())
        if (retryAt != Long.MAX_VALUE) main.postDelayed(retry, (retryAt - SystemClock.uptimeMillis()).coerceAtLeast(0))
        refreshViews()
    }
    private fun decode(key: String, token: Any, raw: String?) {
        if (closed || pending[key] !== token) return
        if (raw == null || raw.length > 16 * 1024 * 1024) { failed(key, token, false); return }
        worker.execute {
            val bitmap = runCatching {
                val bytes = Base64.getDecoder().decode(raw.substringAfter(',', raw))
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                require(options.outWidth in 1..32768 && options.outHeight in 1..32768)
                options.inJustDecodeBounds = false; options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 768) options.inSampleSize *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }.getOrNull()
            main.post {
                if (closed || pending[key] !== token) { bitmap?.recycle(); return@post }
                if (bitmap == null) failed(key, token, false) else {
                    pending.remove(key); failures.remove(key); cache.put(key, bitmap); refreshViews()
                }
            }
        }
    }
    fun view(image: ConversationPresentation.Image, key: String, open: () -> Unit, fetch: (String, (String?, String?) -> Unit) -> Unit): View {
        val viewScope = scope
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = AgentStyle.shape(context, AgentStyle.panel, 12)
        }
        val picture = ImageView(context).apply {
            adjustViewBounds = true; maxHeight = dp(280); scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "图像预览：${image.label}"
        }
        val status = TextView(context).apply { text = "图像 · ${image.label}"; textSize = 12f; setTextColor(AgentStyle.muted); setPadding(0, dp(6), 0, 0) }
        card.addView(picture, LinearLayout.LayoutParams(-1, dp(200))); card.addView(status)
        fun status(value: String) { if (status.text.toString() != value) status.text = value }
        var shown: Bitmap? = null
        val inline = image.source.startsWith("data:image/")
        fun load() {
            if (closed || scope != viewScope || !card.isAttachedToWindow) return
            if (!active || !card.getLocalVisibleRect(Rect())) {
                picture.setImageDrawable(null); shown = null
                if (!active) status("${image.label} · 已暂停，回到对话后继续")
                return
            }
            cache.get(key)?.let {
                if (shown !== it) { picture.setImageBitmap(it); shown = it }
                status("${image.label} · 点按进入全屏查看")
                return
            }
            // The bitmap can outlive its cache entry while this card remains on screen.
            if (shown != null) return
            if (!inline && image.workspacePath == null) { status("${image.label} · 非当前工作区图像，未自动读取"); return }
            if (!inline && !connected) { status("${image.label} · 等待连接，恢复后自动加载"); return }
            if (key in pending) { status("加载图像 · ${image.label}"); return }
            failures[key]?.let {
                if (SystemClock.uptimeMillis() < it.retryAt) {
                    status(if (it.retryAt == Long.MAX_VALUE) "${image.label} · 暂无法预览，点按重试" else "${image.label} · 读取失败，稍后自动重试（也可点按）")
                    return
                }
            }
            if (pending.size >= 2) { status("${image.label} · 等待加载"); return }
            val token = Any(); pending[key] = token
            status("加载图像 · ${image.label}")
            if (inline) decode(key, token, image.source) else fetch(requireNotNull(image.workspacePath)) { raw, error ->
                if (error != null) failed(key, token, true) else decode(key, token, raw)
            }
        }
        val update = { load() }
        val click = View.OnClickListener {
            if (closed || !active || scope != viewScope) return@OnClickListener
            if (shown != null) open() else if (inline || image.workspacePath != null) { failures.remove(key); load() }
        }
        val openClick = View.OnClickListener {
            if (!closed && active && scope == viewScope && (inline || image.workspacePath != null)) open()
        }
        // The reviewer may load an original too large for the thumbnail endpoint; keep it reachable after preview failure.
        card.setOnClickListener(openClick); picture.setOnClickListener(openClick); status.setOnClickListener(click)
        val scrollListener = ViewTreeObserver.OnScrollChangedListener { load() }
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { load() }
        var observer: ViewTreeObserver? = null
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                viewers += update
                observer = v.viewTreeObserver.also { it.addOnScrollChangedListener(scrollListener); it.addOnGlobalLayoutListener(layoutListener) }
                refreshViews()
            }
            override fun onViewDetachedFromWindow(v: View) {
                viewers -= update
                observer?.takeIf { it.isAlive }?.let { it.removeOnScrollChangedListener(scrollListener); it.removeOnGlobalLayoutListener(layoutListener) }
                observer = null; picture.setImageDrawable(null); shown = null
            }
        })
        return card
    }
}
