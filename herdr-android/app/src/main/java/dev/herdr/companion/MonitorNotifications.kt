package dev.herdr.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

object MonitorNotifications {
    const val SERVICE_ID = 1101
    const val SERVICE_CHANNEL = "agent-monitor-status"
    const val COMPLETION_CHANNEL = "agent-completed"
    const val OPEN_AGENT = "dev.herdr.companion.OPEN_MONITORED_AGENT"
    const val EXTRA_ROUTE = "monitor_route"
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "常驻监控状态", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示监控连接状态，可随时停止"; setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        manager.createNotificationChannel(NotificationChannel(COMPLETION_CHANNEL, "Agent 完成提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "默认关注所有 Agent，可在 App 内单独关闭"; enableVibration(true); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }
    fun allowed(context: Context): Boolean = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun completionsAllowed(context: Context): Boolean = allowed(context) && context.getSystemService(NotificationManager::class.java).getNotificationChannel(COMPLETION_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
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
        val title = "${clean(AgentPresentation.title(agent))} · 已完成"
        val location = listOf(shared.relayName(relay), shared.workspaceName(relay, agent)).filter { it.isNotBlank() }.joinToString(" · ", transform = ::clean)
        val duration = if (state.optString("source") == "codex_runtime" && state.optString("timing") == "turn_elapsed" && !state.isNull("elapsed_ms")) " · 用时 ${AgentRunPresentation.duration(state.optLong("elapsed_ms"))}" else ""
        val public = NotificationCompat.Builder(context, COMPLETION_CHANNEL).setSmallIcon(R.drawable.ic_monitor).setContentTitle("Herdr Agent 已完成").setContentText("解锁查看对应对话").build()
        val notification = NotificationCompat.Builder(context, COMPLETION_CHANNEL).setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle(title).setContentText(location + duration).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setContentIntent(PendingIntent.getActivity(context, 0, routeIntent(context, relay, agent), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        context.getSystemService(NotificationManager::class.java).notify("completed:$key", 1, notification)
    }
}
