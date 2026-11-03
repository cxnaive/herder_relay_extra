package dev.herdr.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

object MonitorSettingsUi {
    fun show(activity: Activity, enable: () -> Unit, changed: () -> Unit): AlertDialog {
        val prefs = MonitorPreferences(activity)
        val enabled = AgentMonitorService.active
        val exempt = activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName)
        val text = buildString {
            append(if (enabled) "正在常驻监控\n${AgentMonitorService.lastStatus}" else "常驻监控未运行")
            if (prefs.error().isNotBlank()) append("\n${prefs.error()}")
            append("\n\n开启后默认关注所有 Agent，包括后来新建的 Agent。可在 Agent 的 ⋯ 菜单单独关闭完成提醒。")
            append("\n\n通知栏会保留监控状态和停止按钮；完成通知只显示电脑、工作空间、Agent 名称及用时，不含对话正文。点击通知进入对应对话。")
            append("\n\n这是手机常驻连接，不是云推送。耗电会增加。每个 Agent 通常约 15 秒检查一次；重连会核对最新轮次，第一次快照不会通知早已完成的任务。")
            append("\n\n电池优化：${if (exempt) "已允许不优化" else "仍受优化限制，锁屏可能延迟"}。请在系统电池设置允许 Herdr Pocket 后台运行，并按手机厂商要求允许自启动。强行停止、重启手机或断网期间不能保证实时通知；重启后请打开 App。")
            if (!MonitorNotifications.completionsAllowed(activity)) append("\n\n⚠ 完成通知权限/通道未开放，请在系统通知设置允许声音和弹出通知。")
        }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; val p = (20 * resources.displayMetrics.density).toInt(); setPadding(p, p / 2, p, 0) }
        body.addView(TextView(activity).apply { this.text = text; textSize = 14f; setTextColor(0xffe8f0f2.toInt()) })
        val dialog = AlertDialog.Builder(activity).setTitle("常驻监控 / 完成提醒").setView(android.widget.ScrollView(activity).apply { addView(body) })
            .setPositiveButton(if (enabled) "停止监控" else "开启常驻监控") { _, _ -> if (enabled) { AgentMonitorService.stop(activity); changed() } else enable() }
            .setNeutralButton("系统设置") { _, _ ->
                AlertDialog.Builder(activity).setTitle("监控权限与电池设置").setItems(arrayOf("通知、声音与弹出设置", "电池优化设置")) { _, index ->
                    val intent = if (index == 0) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName) else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    runCatching { activity.startActivity(intent) }.onFailure { android.widget.Toast.makeText(activity, "请从系统设置中找到 Herdr Pocket 的通知与电池权限", android.widget.Toast.LENGTH_LONG).show() }
                }.show()
            }.setNegativeButton("关闭", null).create()
        dialog.show(); return dialog
    }
    fun muteLabel(context: Context, relay: String, agent: JSONObject): String = runCatching {
        if (MonitorPreferences(context).muted(MonitorIdentity.key(relay, agent))) "开启此 Agent 完成提醒" else "关闭此 Agent 完成提醒"
    }.getOrDefault("完成提醒暂不可用")
    fun toggle(context: Context, relay: String, agent: JSONObject) {
        runCatching {
            val key = MonitorIdentity.key(relay, agent); val prefs = MonitorPreferences(context); val next = !prefs.muted(key)
            prefs.mute(key, next); AgentMonitorService.policyChanged(context, key)
            context.getSystemService(android.app.NotificationManager::class.java).cancel("completed:$key", 1)
            android.widget.Toast.makeText(context, if (next) "已关闭此 Agent 的完成提醒，其他 Agent 不受影响" else "已开启此 Agent 的完成提醒（需开启常驻监控）", android.widget.Toast.LENGTH_LONG).show()
        }.onFailure { android.widget.Toast.makeText(context, "无法更新此 Agent 的提醒设置，请刷新后重试", android.widget.Toast.LENGTH_LONG).show() }
    }
}
