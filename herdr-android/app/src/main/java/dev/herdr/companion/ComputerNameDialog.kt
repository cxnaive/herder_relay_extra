package dev.herdr.companion

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal object ComputerNameDialog {
    fun show(context: Context, title: String, initialName: String, action: String, detail: String = "", submit: (String) -> Unit): AlertDialog {
        val padding = AgentStyle.dp(context, 20)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding / 2, padding, padding / 2) }
        fun text(value: String) = TextView(context).apply {
            text = value; textSize = 14f; setTextColor(AgentStyle.muted); setPadding(0, padding / 2, 0, padding / 2)
        }
        if (detail.isNotBlank()) body.addView(text(detail))
        body.addView(text("电脑名称（必填） · 仅用于当前手机区分电脑，不会更改远端名称、连接地址或配对。"))
        val name = EditText(context).apply {
            hint = "例如：家里台式机"; contentDescription = "电脑名称"; setTextColor(AgentStyle.ink); setHintTextColor(AgentStyle.muted)
            setSingleLine(); setText(initialName); selectAll(); minimumHeight = AgentStyle.dp(context, 48)
        }
        body.addView(name)
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(ScrollView(context).apply { addView(body) })
            .setPositiveButton(action, null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!dialog.isShowing) return@setOnClickListener
                try {
                    val label = ComputerName.validate(name.text.toString())
                    name.error = null
                    submit(label)
                    dialog.dismiss()
                } catch (error: IllegalArgumentException) {
                    name.error = error.message; name.requestFocus()
                } catch (_: Exception) {
                    name.error = "无法保存电脑名称，请重试"; name.requestFocus()
                }
            }
        }
        dialog.show()
        return dialog
    }
}
