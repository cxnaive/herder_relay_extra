package dev.herdr.companion

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Kept above the scrolling conversation and separate from history-loading messages. */
class AgentRunStatusView(context: Context, refresh: () -> Unit) : LinearLayout(context) {
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val dot = android.widget.ImageView(context)
    private val state = TextView(context).apply { textSize = 13f; typeface = AgentStyle.medium; maxLines = 2 }
    private val timer = TextView(context).apply { textSize = 13f; typeface = AgentStyle.medium; fontFeatureSettings = "tnum"; gravity = Gravity.END; maxLines = 2 }
    private val detail = TextView(context).apply { textSize = 11f; setTextColor(AgentStyle.muted); maxLines = 2 }
    private var last: AgentRunPresentation.Display? = null
    init {
        orientation = VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(8)); minimumHeight = dp(48)
        background = AgentStyle.ripple(context)
        isFocusable = true; AgentStyle.actionSemantics(this); setOnClickListener { refresh() }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(spinner, LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
        top.addView(dot, LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
        top.addView(state, LayoutParams(0, -2, 1f)); top.addView(timer, LayoutParams(-2, -2).apply { marginStart = dp(8) })
        addView(top, LayoutParams(-1, -2)); addView(detail, LayoutParams(-1, -2).apply { topMargin = dp(5) })
    }
    fun show(display: AgentRunPresentation.Display) {
        if (last == display) return
        last = display
        val color = when (display.tone) { "working", "done" -> AgentStyle.accent; "waiting" -> AgentStyle.warning; "error" -> AgentStyle.error; else -> AgentStyle.muted }
        state.text = display.headline; timer.text = display.timer; detail.text = display.detail
        detail.visibility = if (display.tone in listOf("working", "done") && display.timer.isNotEmpty()) GONE else VISIBLE
        state.setTextColor(color); timer.setTextColor(AgentStyle.ink); dot.setImageDrawable(AgentIcon("dot", color))
        spinner.indeterminateTintList = ColorStateList.valueOf(color)
        spinner.visibility = if (display.spinning) View.VISIBLE else View.GONE
        dot.visibility = if (display.spinning) View.GONE else View.VISIBLE
        timer.visibility = if (display.timer.isEmpty()) View.GONE else View.VISIBLE
        contentDescription = "${display.headline}，${display.timer}，${display.detail}，点按刷新状态"
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
