package dev.herdr.companion

import android.app.Service
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** User-started, visible, stoppable local monitoring, NOT a cloud push channel. */
class AgentMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var monitor: AgentMonitor? = null
    private var wake: PowerManager.WakeLock? = null
    private var foreground = false
    private var status = ""
    private val renewWake = object : Runnable {
        override fun run() {
            if (!foreground) return
            wake?.let { if (it.isHeld) it.release(); it.acquire(10 * 60 * 1000L) }
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }
    override fun onCreate() {
        super.onCreate(); MonitorNotifications.createChannels(this)
        try {
            val notification = MonitorNotifications.foreground(this, "正在启动加密监控…")
            if (Build.VERSION.SDK_INT >= 34) startForeground(MonitorNotifications.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(MonitorNotifications.SERVICE_ID, notification)
            foreground = true
        } catch (_: RuntimeException) { MonitorPreferences(this).enabled = false; MonitorPreferences(this).setError("系统拒绝启动常驻服务，请在前台重新开启"); stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = MonitorPreferences(this)
        if (intent?.action == STOP) { prefs.enabled = false; prefs.clearLedger(); finish(); return START_NOT_STICKY }
        if (!foreground || !prefs.enabled || !MonitorNotifications.allowed(this)) { prefs.enabled = false; finish(); return START_NOT_STICKY }
        if (monitor == null) {
            try {
                val shared = SharedRelay.get(this)
                monitor = AgentMonitor(shared, prefs, { relay, agent, state ->
                    try { MonitorNotifications.completed(this, shared, relay, agent, state) } catch (_: RuntimeException) { updateStatus("完成通知被系统拦截，请检查通知设置") }
                }, ::updateStatus, { error -> prefs.enabled = false; prefs.setError(error); handler.post { finish() } }).also { it.start() }
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HerdrPocket:AgentMonitor").apply { setReferenceCounted(false) }
                renewWake.run(); prefs.setError(""); active = true; shared.monitorStateChanged()
            } catch (_: Exception) { prefs.enabled = false; prefs.setError("监控启动失败，请打开 App 检查连接或本机凭据"); finish(); return START_NOT_STICKY }
        }
        if (intent?.action == POLICY_CHANGED) intent.getStringExtra("key")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { monitor?.reset(it) }
        return START_STICKY
    }
    private fun updateStatus(value: String) {
        if (!foreground) return
        if (!MonitorNotifications.allowed(this)) {
            MonitorPreferences(this).enabled = false
            MonitorPreferences(this).setError("通知权限已关闭，监控已停止；允许通知后可重新开启")
            handler.post { finish() }
            return
        }
        val text = if (MonitorNotifications.completionsAllowed(this)) value else "完成通知已被关闭，请打开系统通知设置"
        if (status == text) return
        status = text; lastStatus = text
        getSystemService(NotificationManager::class.java).notify(MonitorNotifications.SERVICE_ID, MonitorNotifications.foreground(this, text))
    }
    private fun finish() { handler.removeCallbacks(renewWake); wake?.let { if (it.isHeld) it.release() }; wake = null; monitor?.stop(); monitor = null; stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; active = false; SharedRelay.notifyMonitorStateIfPresent(); stopSelf() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); monitor?.stop(); monitor = null
        wake?.let { if (it.isHeld) it.release() }; wake = null; if (foreground) stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; active = false
        SharedRelay.notifyMonitorStateIfPresent()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val STOP = "dev.herdr.companion.STOP_MONITOR"
        const val POLICY_CHANGED = "dev.herdr.companion.MONITOR_POLICY_CHANGED"
        var active = false; private set
        var lastStatus = ""; private set
        fun start(context: Context) {
            val prefs = MonitorPreferences(context)
            check(MonitorNotifications.allowed(context)) { "请先允许通知权限" }
            prefs.enabled = true; prefs.setError("")
            try { ContextCompat.startForegroundService(context, Intent(context, AgentMonitorService::class.java)) }
            catch (e: RuntimeException) { prefs.enabled = false; prefs.setError("无法启动监控，请保持 App 在前台重试"); throw e }
        }
        fun stop(context: Context) {
            val prefs = MonitorPreferences(context); prefs.enabled = false; prefs.clearLedger()
            context.stopService(Intent(context, AgentMonitorService::class.java))
        }
        fun policyChanged(context: Context, key: String) {
            if (active) context.startService(Intent(context, AgentMonitorService::class.java).setAction(POLICY_CHANGED).putExtra("key", key))
            else {
                val prefs = MonitorPreferences(context); val ledger = CompletionLedger(prefs.ledger()); ledger.forget(key); prefs.saveLedger(ledger.encode())
            }
        }
    }
}
