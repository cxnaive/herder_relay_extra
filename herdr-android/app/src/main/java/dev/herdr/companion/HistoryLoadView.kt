package dev.herdr.companion

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject

/** A stable top-of-history control, updated immediately without rebuilding the transcript. */
class HistoryLoadView(context: Context, private val load: () -> Unit, private val cancel: () -> Unit) : LinearLayout(context) {
    private val spinner = ProgressBar(context).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(0xff70e1c5.toInt()); contentDescription = "历史消息正在加载" }
    private val status = TextView(context).apply { textSize = 13f; setTextColor(0xffd4e5e9.toInt()); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val action = Button(context).apply {
        text = "加载更早消息"; isAllCaps = false; textSize = 13f; setTextColor(0xff70e1c5.toInt())
        background = AgentStyle.shape(context, 0xff1d323c.toInt(), 12); setPadding(dp(12), 0, dp(12), 0)
    }
    private var pending = false
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(4), dp(6), dp(4))
        addView(spinner, LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
        addView(status, LayoutParams(0, -2, 1f))
        addView(action, LayoutParams(-2, dp(48)))
        action.setOnClickListener { if (pending) cancel() else load() }
        update(false, false, false)
    }
    fun update(loading: Boolean, older: Boolean, hasMore: Boolean, message: String = "", progress: JSONObject? = null) {
        pending = loading
        spinner.visibility = if (loading) VISIBLE else GONE
        visibility = if (loading || hasMore || message.isNotBlank()) VISIBLE else GONE
        val scanned = progress?.optLong("scanned_bytes") ?: 0L
        val total = progress?.optLong("source_bytes") ?: 0L
        status.text = when {
            loading && total > 0 -> "正在整理历史 · ${FileRules.size(scanned)} / ${FileRules.size(total)}"
            loading -> message.ifBlank { if (older) "正在读取更早消息…" else "正在同步对话…" }
            message.isNotBlank() -> message
            else -> "${if (hasMore) "上方还有更早记录" else ""}"
        }
        action.text = when { loading -> "取消等待"; message.isNotBlank() -> "重试"; else -> "加载更早消息" }
        action.contentDescription = action.text
        action.visibility = if (loading || hasMore || message.isNotBlank()) VISIBLE else GONE
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
