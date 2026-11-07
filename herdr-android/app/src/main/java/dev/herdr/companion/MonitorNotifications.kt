package dev.herdr.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

object MonitorNotifications {
    const val SERVICE_ID = 1101
    const val SOUND_TEST_ID = 1102
    const val SERVICE_CHANNEL = "agent-monitor-status"
    const val COMPLETION_CHANNEL = "agent-completed"
    const val OPEN_AGENT = "dev.herdr.companion.OPEN_MONITORED_AGENT"
    const val EXTRA_ROUTE = "monitor_route"
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Keep these IDs stable so upgrades preserve the user's ringtone and mute choices.
        manager.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "常驻监控状态", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示监控连接状态，可随时停止"; setSound(null, null); enableVibration(false); setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        manager.createNotificationChannel(NotificationChannel(COMPLETION_CHANNEL, "Agent 完成提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "任务完成时播放通知铃声；可在此设置声音，也可在 App 内单独关闭某个 Agent"
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }
    fun allowed(context: Context): Boolean = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun completionsAllowed(context: Context): Boolean = allowed(context) && context.getSystemService(NotificationManager::class.java)
        .getNotificationChannel(COMPLETION_CHANNEL)?.let { it.importance != NotificationManager.IMPORTANCE_NONE } == true
    fun completionSettingsIntent(context: Context): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, COMPLETION_CHANNEL)
    fun completionSoundStatus(context: Context): String {
        if (!allowed(context)) return "通知权限未开启，请先允许 Herdr Pocket 发送通知"
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(COMPLETION_CHANNEL) ?: return "完成提醒通道尚未创建"
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return "完成提醒已被系统禁用，请在声音设置中开启"
        if (channel.importance < NotificationManager.IMPORTANCE_DEFAULT) return "完成提醒通道为静默，请在声音设置中改为响铃提醒"
        if (channel.sound == null) return "完成提醒未选择铃声，请在声音设置中选择"
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return "手机处于静音/振动模式，完成提醒不会响铃"
        if (audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0) return "通知音量为 0，请调高手机的通知音量"
        if (channel.sound == Settings.System.DEFAULT_NOTIFICATION_URI && RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION) == null)
            return "系统默认通知铃声为无，请在声音设置中选择铃声"
        if (manager.currentInterruptionFilter !in listOf(NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
            return "手机已开启勿扰模式，完成提醒的声音可能被系统屏蔽"
        return "已配置通知铃声，可发送测试提醒确认；实际播放仍由系统控制"
    }
    private fun completionBuilder(context: Context, title: String, text: String) = NotificationCompat.Builder(context, COMPLETION_CHANNEL)
        .setSmallIcon(R.drawable.ic_monitor).setContentTitle(title).setContentText(text).setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS).setPriority(NotificationCompat.PRIORITY_HIGH)
        .setOnlyAlertOnce(false).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    fun testCompletion(context: Context): Boolean {
        createChannels(context)
        if (!completionsAllowed(context)) return false
        val notification = completionBuilder(context, "Herdr Pocket · 提醒测试", "这是完成提醒的声音测试，不代表 Agent 任务完成")
            .setContentIntent(main(context)).build()
        return try { context.getSystemService(NotificationManager::class.java).notify("monitor-sound-test", SOUND_TEST_ID, notification); true }
        catch (_: SecurityException) { false }
    }
    private fun main(context: Context) = PendingIntent.getActivity(context, SERVICE_ID, Intent(context, MainActivity::class.java).setAction("dev.herdr.companion.MONITOR_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun foreground(context: Context, status: String): Notification = NotificationCompat.Builder(context, SERVICE_CHANNEL)
        .setSmallIcon(R.drawable.ic_monitor).setContentTitle("Herdr 常驻监控").setContentText(status)
        .setStyle(NotificationCompat.BigTextStyle().bigText(status)).setContentIntent(main(context))
        .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false).setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .addAction(0, "停止监控", PendingIntent.getService(context, SERVICE_ID, Intent(context, AgentMonitorService::class.java).setAction(AgentMonitorService.STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()
    fun routeIntent(context: Context, relay: String, agent: JSONObject): Intent {
        val route = MonitorIdentity.route(relay, agent)
        return Intent(context, MainActivity::class.java).setAction(OPEN_AGENT)
            .setData(Uri.parse("herdr-monitor://agent/${route.getString("key")}"))
            .putExtra(EXTRA_ROUTE, route.toString()).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    fun completed(context: Context, shared: SharedRelay, relay: String, agent: JSONObject, state: JSONObject) {
        if (!completionsAllowed(context)) return
        val key = MonitorIdentity.key(relay, agent)
        fun clean(value: String) = value.filterNot { it.isISOControl() }.take(80)
        val title = "${clean(AgentPresentation.title(agent))} · 本轮完成"
        val location = listOf(shared.relayName(relay), shared.workspaceName(relay, agent)).filter { it.isNotBlank() }.joinToString(" · ", transform = ::clean)
        val duration = if (state.optString("source") == "codex_runtime" && state.optString("timing") == "turn_elapsed" && !state.isNull("elapsed_ms")) " · 用时 ${AgentRunPresentation.duration(state.optLong("elapsed_ms"))}" else ""
        val public = NotificationCompat.Builder(context, COMPLETION_CHANNEL).setSmallIcon(R.drawable.ic_monitor).setContentTitle("Herdr Agent 本轮完成").setContentText("解锁查看对应对话").build()
        val notification = completionBuilder(context, title, location + duration).setPublicVersion(public)
            .setContentIntent(PendingIntent.getActivity(context, 0, routeIntent(context, relay, agent), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        context.getSystemService(NotificationManager::class.java).notify("completed:$key", 1, notification)
    }
}
