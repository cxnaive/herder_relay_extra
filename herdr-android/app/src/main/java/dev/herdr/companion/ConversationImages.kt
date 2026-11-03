package dev.herdr.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
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
    private val errors = mutableSetOf<String>()
    private var closed = false
    fun clear() { cache.evictAll(); errors.clear() }
    fun close() { closed = true; clear(); worker.shutdownNow() }
    fun view(image: ConversationPresentation.Image, key: String, open: () -> Unit, fetch: (String, (String?, String?) -> Unit) -> Unit): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = AgentStyle.shape(context, 0xff192b32.toInt(), 12)
        }
        val picture = ImageView(context).apply {
            adjustViewBounds = true; maxHeight = dp(280); scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "图像预览：${image.label}"
        }
        val status = TextView(context).apply { text = "图像 · ${image.label}"; textSize = 12f; setTextColor(0xff98b0b9.toInt()); setPadding(0, dp(6), 0, 0) }
        card.addView(picture, LinearLayout.LayoutParams(-1, dp(200))); card.addView(status)
        if (image.workspacePath != null || image.source.startsWith("data:image/")) card.setOnClickListener { open() }
        var loading = false
        var shown: Bitmap? = null
        fun display(bitmap: Bitmap) {
            if (closed || !card.isAttachedToWindow || !card.getLocalVisibleRect(Rect())) return
            picture.setImageBitmap(bitmap); shown = bitmap
            status.text = "${image.label} · 点按进入全屏查看"
            picture.setOnClickListener { open() }
            status.setOnClickListener { open() }
        }
        fun fail() { loading = false; errors += key; status.text = "${image.label} · 暂无法预览，点按重试"; picture.setImageDrawable(null) }
        fun decode(raw: String?) {
            if (raw == null || raw.length > 16 * 1024 * 1024) { fail(); return }
            if (closed) return
            worker.execute {
                val bitmap = runCatching {
                    val payload = raw.substringAfter(',', raw)
                    val bytes = Base64.getDecoder().decode(payload)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    require(options.outWidth in 1..32768 && options.outHeight in 1..32768)
                    options.inJustDecodeBounds = false
                    options.inSampleSize = 1
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 768) options.inSampleSize *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }.getOrNull()
                main.post {
                    loading = false
                    if (!closed) { if (bitmap != null) { cache.put(key, bitmap); errors -= key; display(bitmap) } else fail() }
                }
            }
        }
        fun load() {
            if (closed || loading || !card.isAttachedToWindow || !card.getLocalVisibleRect(Rect())) return
            cache.get(key)?.let { if (shown !== it) display(it); return }
            if (key in errors || shown != null) return
            loading = true
            status.text = "加载图像 · ${image.label}"
            when {
                image.source.startsWith("data:image/") -> decode(image.source)
                image.workspacePath != null -> fetch(image.workspacePath) { raw, error -> if (error != null) fail() else decode(raw) }
                else -> { loading = false; errors += key; status.text = "${image.label} · 非当前工作区图像，未自动读取" }
            }
        }
        status.setOnClickListener { errors -= key; load() }
        val scrollListener = android.view.ViewTreeObserver.OnScrollChangedListener {
            if (!card.getLocalVisibleRect(Rect())) { picture.setImageDrawable(null); picture.setOnClickListener(null); shown = null } else load()
        }
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.viewTreeObserver.addOnScrollChangedListener(scrollListener); v.post { load() } }
            override fun onViewDetachedFromWindow(v: View) { if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnScrollChangedListener(scrollListener); picture.setImageDrawable(null); picture.setOnClickListener(null); shown = null }
        })
        return card
    }
}
