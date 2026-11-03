package dev.herdr.companion

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

/** Two independent disclosures: one activity group, then each tool's arguments/result. */
class NativeToolGroup(
    context: Context,
    private val group: ConversationTimeline.Tools,
    private val expandedGroups: MutableSet<String>,
    private val expandedItems: MutableSet<String>,
    private val openFull: (String, String) -> Unit,
) : LinearLayout(context) {
    private val details = LinearLayout(context).apply { orientation = VERTICAL }
    private val outer = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
    private val title = text("", 13, AgentStyle.muted)
    private var materialized = false
    private val expansionKeys = group.tools.map { "tools:" + it.key }.toSet()
    init {
        orientation = VERTICAL
        background = AgentStyle.shape(context, AgentStyle.panel, 10)
        val tools = JSONArray(group.tools.map { it.value })
        val summary = ConversationPresentation.toolSummary(tools)
        outer.apply {
            setPadding(dp(12), dp(9), dp(12), dp(9)); minimumHeight = dp(48)
            background = AgentStyle.ripple(context, AgentStyle.panel, 12)
            contentDescription = "工具组：$summary，展开或收起全部工具"
            isFocusable = true; AgentStyle.actionSemantics(this)
        }
        outer.addView(title)
        outer.addView(text(ConversationTimeline.overview(group), 12, AgentStyle.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(17), dp(3), 0, 0) })
        addView(outer, LayoutParams(-1, -2)); addView(details, LayoutParams(-1, -2))
        fun update() {
            val expanded = expansionKeys.any { it in expandedGroups }
            title.text = summary
            title.setCompoundDrawables(AgentStyle.icon(context, if (expanded) "down" else "chevron", size = 14), null, null, null); title.compoundDrawablePadding = dp(8)
            title.setTextColor(if (summary.contains("失败")) AgentStyle.error else AgentStyle.muted)
            outer.isSelected = expanded
            if (android.os.Build.VERSION.SDK_INT >= 30) outer.stateDescription = if (expanded) "已展开" else "已折叠"
            details.visibility = if (expanded) VISIBLE else GONE
            if (expanded && !materialized) { materialized = true; group.tools.forEach { addTool(it) } }
        }
        outer.setOnClickListener {
            if (expansionKeys.any { it in expandedGroups }) expandedGroups.removeAll(expansionKeys) else expandedGroups.add(group.key)
            update()
        }
        update()
    }
    private fun addTool(item: ConversationTimeline.Tool) {
        val tool = item.value
        val card = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(10), 0, dp(10), dp(6)) }
        val header = LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48)
            setPadding(dp(10), dp(8), dp(10), dp(8)); isFocusable = true; AgentStyle.actionSemantics(this)
            background = AgentStyle.ripple(context, AgentStyle.raised, 8)
            contentDescription = "工具：${tool.optString("name")}，展开或收起参数和输出"
        }
        val name = text("", 13, AgentStyle.ink); header.addView(name)
        val preview = ConversationTimeline.preview(tool)
        if (preview.isNotBlank()) header.addView(text(preview, 11, AgentStyle.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.MONOSPACE; setPadding(dp(17), dp(3), 0, 0) })
        val payload = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(6)) }
        var loaded = false
        fun update() {
            val expanded = item.key in expandedItems
            val state = when {
                tool.optBoolean("error") || tool.optString("state") in listOf("error", "failed") -> "失败"
                tool.optString("state") in listOf("running", "in_progress", "pending", "started") -> "进行中"
                else -> "完成"
            }
            name.text = "${tool.optString("name").substringAfterLast('.')} · $state"
            name.setCompoundDrawables(AgentStyle.icon(context, if (expanded) "down" else "chevron", size = 14), null, null, null); name.compoundDrawablePadding = dp(8)
            name.setTextColor(if (state == "失败") AgentStyle.error else AgentStyle.ink)
            payload.visibility = if (expanded) VISIBLE else GONE
            header.isSelected = expanded
            if (android.os.Build.VERSION.SDK_INT >= 30) header.stateDescription = if (expanded) "已展开" else "已折叠"
            if (expanded && !loaded) {
                loaded = true
                for ((field, label) in listOf("input" to "参数", "output" to "输出")) {
                    val value = ConversationTimeline.detail(tool, field)
                    if (value.isBlank()) continue
                    payload.addView(text(label, 11, AgentStyle.muted))
                    payload.addView(text(value.lineSequence().take(24).joinToString("\n").take(6000), 12, AgentStyle.ink).apply {
                        typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(0, dp(4), 0, dp(10))
                    })
                }
                if (tool.optBoolean("truncated")) payload.addView(text("电脑端返回的工具内容已截断", 11, AgentStyle.muted))
                payload.addView(text("查看完整内容 / 复制", 12, AgentStyle.accent).apply {
                    minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isFocusable = true
                    background = AgentStyle.ripple(context)
                    setOnClickListener { openFull(tool.optString("name"), ConversationTimeline.detail(tool, "input") + "\n\n" + ConversationTimeline.detail(tool, "output")) }
                })
            }
        }
        header.setOnClickListener { if (!expandedItems.add(item.key)) expandedItems.remove(item.key); update() }
        card.addView(header); card.addView(payload); details.addView(card); update()
    }
    private fun text(value: String, size: Int, color: Int) = TextView(context).apply { text = value; textSize = size.toFloat(); setTextColor(color) }
    private fun dp(value: Int) = AgentStyle.dp(context, value)
}
