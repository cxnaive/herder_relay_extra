package dev.herdr.companion

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.*

/** Termux-inspired extra keys; key presses target the remote terminal, never the local draft. */
internal class TerminalExtraKeys(context: Context, private val send: (String) -> Unit) : LinearLayout(context) {
    data class Key(val label: String, val wire: String, val description: String)
    private val buttons = mutableListOf<Button>()
    private var available = false
    private var secondary: LinearLayout? = null
    init {
        orientation = VERTICAL; contentDescription = "远端终端附加按键，不修改手机输入框草稿"
        for (keys in listOf(
            listOf(Key("Esc","Escape","取消 / Escape"),Key("Tab","Tab","补全 / Tab"),Key("Ctrl","CTRL","选择 Ctrl 组合键"),Key("Alt","ALT","选择 Alt 组合键"),Key("退格","BSpace","远端退格，删除光标前的字符"),Key("Enter","Enter","远端回车，不发送手机草稿")),
            listOf(Key("PgUp","PPage","远端向上翻页，会改变电脑端程序视图"),Key("←","Left","远端光标向左"),Key("↑","Up","远端上方向键"),Key("↓","Down","远端下方向键"),Key("→","Right","远端光标向右"),Key("PgDn","NPage","远端向下翻页，会改变电脑端程序视图"))
        )) {
            val row = LinearLayout(context)
            for (key in keys) {
                val button = AgentStyle.button(context,key.label) {
                    if (available) when (key.wire) { "CTRL" -> combinations(false); "ALT" -> combinations(true); else -> send(key.wire) }
                }.apply { textSize = 12f; setPadding(dp(2),0,dp(2),0); contentDescription = key.description; tooltipText = key.description }
                buttons += button
                row.addView(button,LayoutParams(0,dp(48),1f).apply { marginEnd = dp(2); marginStart = dp(2) })
            }
            if (childCount == 1) secondary = row
            addView(row, LayoutParams(-1,dp(48)).apply { topMargin=dp(4) })
        }
        setAvailable(false)
    }
    fun setCompact(compact: Boolean) { secondary?.visibility = if(compact) GONE else VISIBLE }
    fun setAvailable(value: Boolean) { available=value; buttons.forEach { it.isEnabled=value; it.alpha=if(value) 1f else .45f } }
    private fun combinations(alt: Boolean) {
        val keys = if(alt) listOf("Alt-B · 前移一个词" to "M-b","Alt-F · 后移一个词" to "M-f","Alt-D · 删除后面的词" to "M-d")
            else listOf("Ctrl-C · 中断当前任务" to "C-c","Ctrl-D · EOF / 退出" to "C-d","Ctrl-L · 清屏" to "C-l","Ctrl-A · 行首" to "C-a","Ctrl-E · 行尾" to "C-e","Ctrl-U · 删除到行首" to "C-u","Ctrl-W · 删除前一个词" to "C-w")
        AlertDialog.Builder(context).setTitle(if(alt) "远端 Alt 组合键" else "远端 Ctrl 组合键")
            .setItems((keys.map { it.first } + "其他组合键…").toTypedArray()) { _,i -> if (available) { if (i<keys.size) send(keys[i].second) else custom(alt) } }.show()
    }
    private fun custom(alt: Boolean) {
        val input = EditText(context).apply { setSingleLine(); hint="例如 a、x 或 Tab"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        AlertDialog.Builder(context).setTitle(if(alt) "Alt + 按键" else "Ctrl + 按键").setView(input).setNegativeButton("取消",null)
            .setPositiveButton("发送") { _,_ ->
                val key=input.text.toString().trim()
                if (available && key.matches(Regex("[A-Za-z0-9]|Tab|Enter|Left|Right|Up|Down|Home|End"))) send((if(alt) "M-" else "C-")+key)
                else Toast.makeText(context,"请输入一个字母、数字或指定功能键",Toast.LENGTH_LONG).show()
            }.show()
    }
    private fun dp(value: Int)=AgentStyle.dp(context,value)
}
