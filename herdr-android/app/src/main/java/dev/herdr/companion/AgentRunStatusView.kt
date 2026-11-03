package dev.herdr.companion

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Kept above the scrolling conversation and separate from history-loading messages. */
class AgentRunStatusView(context: Context, refresh: () -> Unit) : LinearLayout(context) {
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val dot = TextView(context).apply { text = "●"; textSize = 12f; gravity = Gravity.CENTER }
    private val state = TextView(context).apply { textSize = 15f; setTypeface(typeface, Typeface.BOLD); maxLines = 2 }
    private val timer = TextView(context).apply { textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); fontFeatureSettings = "tnum"; gravity = Gravity.END; maxLines = 2 }
    private val detail = TextView(context).apply { textSize = 11f; setTextColor(0xffa9bec6.toInt()); maxLines = 2 }
    private var last: AgentRunPresentation.Display? = null
    init {
        orientation = VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); minimumHeight = dp(68)
        background = AgentStyle.ripple(context, 0xff182a32.toInt(), 14)
        isFocusable = true; setOnClickListener { refresh() }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(spinner, LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        top.addView(dot, LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        top.addView(state, LayoutParams(0, -2, 1f)); top.addView(timer, LayoutParams(-2, -2).apply { marginStart = dp(8) })
        addView(top, LayoutParams(-1, -2)); addView(detail, LayoutParams(-1, -2).apply { topMargin = dp(5) })
    }
    fun show(display: AgentRunPresentation.Display) {
        if (last == display) return
        last = display
        val color = when (display.tone) { "working", "done" -> 0xff70dfc0.toInt(); "waiting" -> 0xffffcb80.toInt(); "error" -> 0xffff989c.toInt(); else -> 0xffafc0c7.toInt() }
        state.text = display.headline; timer.text = display.timer; detail.text = display.detail
        state.setTextColor(color); timer.setTextColor(Color.rgb(232, 240, 242)); dot.setTextColor(color)
        spinner.indeterminateTintList = ColorStateList.valueOf(color)
        spinner.visibility = if (display.spinning) View.VISIBLE else View.GONE
        dot.visibility = if (display.spinning) View.GONE else View.VISIBLE
        timer.visibility = if (display.timer.isEmpty()) View.GONE else View.VISIBLE
        contentDescription = "${display.headline}，${display.timer}，${display.detail}，点按刷新状态"
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
