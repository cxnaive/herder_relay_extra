package dev.herdr.companion

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*

internal object AgentStyle {
    val ink = Color.rgb(232, 240, 242)
    val muted = Color.rgb(151, 173, 182)
    val accent = Color.rgb(112, 225, 197)
    val panel = Color.rgb(27, 42, 50)
    val border = Color.rgb(54, 73, 82)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun shape(context: Context, color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(context, 1).coerceAtLeast(1), stroke)
    }
    fun ripple(context: Context, color: Int = Color.TRANSPARENT, radius: Int = 16) = RippleDrawable(
        ColorStateList.valueOf(0x2270e1c5), shape(context, color, radius), shape(context, Color.WHITE, radius))
    fun iconButton(context: Context, icon: String, description: String, action: () -> Unit) = ImageButton(context).apply {
        contentDescription = description; tooltipText = description
        setImageDrawable(AgentIcon(icon, ink)); scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        background = ripple(context); setOnClickListener { action() }
        minimumWidth = dp(context, 48); minimumHeight = dp(context, 48)
    }
}

/** Small vector icons share one stroke weight at every screen density. */
internal class AgentIcon(private val icon: String, private val color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@AgentIcon.color; style = Paint.Style.STROKE; strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, paint)
        when (icon) {
            "send" -> { line(12f, 19f, 12f, 5f); line(6f, 11f, 12f, 5f); line(12f, 5f, 18f, 11f) }
            "back" -> { line(14f, 5f, 7f, 12f); line(7f, 12f, 14f, 19f) }
            "close" -> { line(7f, 7f, 17f, 17f); line(17f, 7f, 7f, 17f) }
            "more" -> { paint.style = Paint.Style.FILL; for (x in listOf(5f, 12f, 19f)) canvas.drawCircle(x, 12f, 1.5f, paint); paint.style = Paint.Style.STROKE }
            "attachment" -> {
                val path = Path().apply {
                    moveTo(9f, 17f); lineTo(17f, 9f); cubicTo(21f, 5f, 15f, 0f, 11f, 4f)
                    lineTo(3f, 12f); cubicTo(-2f, 18f, 6f, 25f, 11f, 20f)
                    lineTo(19f, 12f); moveTo(8f, 15f); lineTo(15f, 8f)
                }; canvas.drawPath(path, paint)
            }
            "file" -> {
                canvas.drawPath(Path().apply { moveTo(5f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(5f, 21f); close(); moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f) }, paint)
                line(8f, 13f, 16f, 13f); line(8f, 17f, 13f, 17f)
            }
        }
        canvas.restore()
    }
    override fun getIntrinsicWidth() = 24
    override fun getIntrinsicHeight() = 24
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** One focusable writing surface owns the draft, attachments and message actions. */
internal class NativeComposerView(context: Context, terminal: Boolean, draft: String,
    onDraft: (String) -> Unit, onAttach: () -> Unit, onCommands: () -> Unit,
    onInputOptions: () -> Unit, onSend: () -> Unit) : LinearLayout(context) {
    data class Attachment(val name: String, val bytes: Long)
    val editor = EditText(context)
    val sendButton = AgentStyle.iconButton(context, "send", if (terminal) "输入文本" else "发送", onSend)
    private val attachButton = AgentStyle.iconButton(context, "attachment", "添加附件", onAttach)
    private val attachmentRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val attachmentScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false; addView(attachmentRow); visibility = GONE
    }
    private val progress = TextView(context).apply { textSize = 12f; setTextColor(AgentStyle.accent); visibility = GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    init {
        orientation = VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(8))
        background = AgentStyle.shape(context, AgentStyle.panel, 24, AgentStyle.border)
        elevation = dp(3).toFloat(); contentDescription = "消息输入区"
        addView(attachmentScroll, LayoutParams(-1, -2).apply { setMargins(dp(4), dp(6), dp(4), 0) })
        editor.apply {
            hint = if (terminal) "输入终端文本…" else "给 Agent 一个任务…"
            contentDescription = if (terminal) "终端文本" else "消息内容"
            setTextColor(AgentStyle.ink); setHintTextColor(AgentStyle.muted); textSize = 16f
            background = null; gravity = Gravity.TOP or Gravity.START
            setPadding(dp(10), dp(if (terminal) 8 else 12), dp(10), dp(8))
            minLines = 1; maxLines = if (terminal) 4 else 5
            minimumHeight = dp(44)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(draft)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onDraft(s.toString()) }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            setOnFocusChangeListener { _, focused -> this@NativeComposerView.background = AgentStyle.shape(context, AgentStyle.panel, 24, if (focused) 0xff619e91.toInt() else AgentStyle.border) }
        }
        addView(editor, LayoutParams(-1, -2))
        addView(progress, LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(6)) })
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(attachButton, LayoutParams(dp(48), dp(48)))
        fun action(text: String, description: String, run: () -> Unit) = TextView(context).apply {
            this.text = text; contentDescription = description; tooltipText = description
            textSize = 13f; setTextColor(AgentStyle.muted); gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0); background = AgentStyle.ripple(context)
            isFocusable = true; setOnClickListener { run() }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name }
            }
        }
        actions.addView(action("/ 指令", "选择 Agent 指令", onCommands), LayoutParams(-2, dp(48)))
        if (terminal) actions.addView(action("输入方式", "终端输入方式", onInputOptions), LayoutParams(-2, dp(48)))
        actions.addView(View(context), LayoutParams(0, 1, 1f))
        sendButton.apply {
            background = AgentStyle.ripple(context, AgentStyle.accent, 24)
            setImageDrawable(AgentIcon("send", Color.rgb(16, 38, 34)))
        }
        actions.addView(sendButton, LayoutParams(dp(48), dp(48)))
        addView(actions, LayoutParams(-1, dp(48)))
    }
    fun setAvailability(canSend: Boolean, canAttach: Boolean) {
        sendButton.isEnabled = canSend; sendButton.alpha = if (canSend) 1f else .35f
        attachButton.isEnabled = canAttach; attachButton.alpha = if (canAttach) 1f else .35f
    }
    fun uploadStatus(text: String?) { progress.text = text.orEmpty(); progress.visibility = if (text.isNullOrBlank()) GONE else VISIBLE }
    fun attachments(files: List<Attachment>, removable: Boolean, onRemove: (Int) -> Unit) {
        attachmentRow.removeAllViews(); attachmentScroll.visibility = if (files.isEmpty()) GONE else VISIBLE
        files.forEachIndexed { index, file ->
            val chip = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; background = AgentStyle.shape(context, 0xff253a42.toInt(), 14, AgentStyle.border)
                setPadding(dp(10), dp(3), 0, dp(3))
            }
            chip.addView(ImageView(context).apply { setImageDrawable(AgentIcon("file", AgentStyle.accent)); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LayoutParams(dp(24), dp(24)))
            val meta = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(8), 0, 0, 0) }
            meta.addView(TextView(context).apply { text = file.name; textSize = 13f; setTextColor(AgentStyle.ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; maxWidth = dp(156) })
            meta.addView(TextView(context).apply {
                text = if (file.bytes > 0) android.text.format.Formatter.formatShortFileSize(context, file.bytes) else "已添加"
                textSize = 11f; setTextColor(AgentStyle.muted)
            }); chip.addView(meta)
            chip.addView(AgentStyle.iconButton(context, "close", "移除附件 ${file.name}") { onRemove(index) }.apply { isEnabled = removable; alpha = if (removable) 1f else .4f }, LayoutParams(dp(48), dp(48)))
            attachmentRow.addView(chip, LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
    }
    private fun dp(value: Int) = AgentStyle.dp(context, value)
}
