package dev.herdr.companion

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MonitorServiceTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun setup() {
        SharedRelay.resetForTest()
        context.getSharedPreferences("private_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("background-monitor", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    private fun agent(id: String = "a") = JSONObject().put("agent_session_id", "session-$id").put("terminal_id", "terminal-$id").put("workspace_id", "ws")
        .put("pane_id", "pane-$id").put("generation", 1).put("agent", "pi").put("cwd", "/work/project").put("tab_label", "writer-$id").put("status", "working")
    private fun publish(shared: SharedRelay, rows: JSONArray) {
        shared.event(JSONObject().put("type", "status").put("relayId", "relay").put("status", "connected"))
        shared.event(JSONObject().put("type", "message").put("relayId", "relay").put("message", JSONObject().put("type", "push_config").put("protocol", 3).put("inventory", JSONObject().put("state", "ready"))))
        shared.event(JSONObject().put("type", "message").put("relayId", "relay").put("message", JSONObject().put("type", "agents").put("agents", rows)))
    }
    @Test fun defaultsMonitorEveryAgentAndMuteOnlySelectedIdentityPersists() {
        val prefs = MonitorPreferences(context); val a = MonitorIdentity.key("relay", agent("a")); val b = MonitorIdentity.key("relay", agent("b"))
        assertFalse(prefs.enabled); assertFalse(prefs.muted(a)); assertFalse(prefs.muted(b))
        prefs.mute(a, true); assertTrue(MonitorPreferences(context).muted(a)); assertFalse(prefs.muted(b))
        assertFalse(prefs.muted(MonitorIdentity.key("relay", agent("new"))))
        prefs.mute(a, false); assertFalse(prefs.muted(a))
    }
    @Test fun sharedConnectionRemainsOwnedByServiceAfterUIStopsAndReplaysInventory() {
        val shared = SharedRelay.get(context); val activity = Any(); val service = Any()
        shared.attachUI(activity) {}; shared.attachMonitor(service) {}
        publish(shared, JSONArray().put(agent()))
        shared.detachUI(activity)
        assertTrue(shared.monitorOwned); assertTrue(shared.ready("relay")); assertFalse(shared.uiVisible)
        val replayed = mutableListOf<JSONObject>(); val newActivity = Any()
        shared.attachUI(newActivity) { replayed += it }
        assertTrue(replayed.any { it.optJSONObject("message")?.optString("type") == "agents" })
        assertSame(shared.engine, SharedRelay.get(context).engine)
        shared.detachMonitor(service); assertTrue(shared.uiVisible); assertTrue(shared.ready("relay"))
        shared.detachUI(newActivity); assertFalse(shared.ready("relay")); assertTrue(shared.online.isEmpty())
    }
    @Test fun foregroundServiceHasVisibleStopActionAndReleasesWakeLockWhenDestroyed() {
        val prefs = MonitorPreferences(context); prefs.enabled = true
        val controller = Robolectric.buildService(AgentMonitorService::class.java).create()
        val service = controller.get()
        try {
            assertEquals(android.app.Service.START_STICKY, service.onStartCommand(Intent(context, AgentMonitorService::class.java), 0, 1))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(AgentMonitorService.active)
            val notification = shadowOf(service).lastForegroundNotification
            assertNotNull(notification); assertTrue(notification.actions.any { it.title.toString() == "停止监控" })
            assertTrue(SharedRelay.get(context).monitorOwned)
            val lock = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
            assertNotNull(lock); assertTrue(lock.isHeld)
            service.onStartCommand(Intent(context, AgentMonitorService::class.java).setAction(AgentMonitorService.STOP), 0, 2)
            assertFalse(prefs.enabled); assertFalse(AgentMonitorService.active); assertFalse(lock.isHeld)
        } finally { controller.destroy() }
        assertFalse(SharedRelay.get(context).monitorOwned)
    }
    @Test fun monitorDetectsCompletionInBackgroundButDoesNotPostInitialFinishedSnapshot() {
        val shared = SharedRelay.get(context); val prefs = MonitorPreferences(context); val alerts = mutableListOf<String>()
        val monitor = AgentMonitor(shared, prefs, { _, a, _ -> alerts += a.getString("pane_id") }, {})
        val a = agent("a"); val b = agent("b").put("status", "idle")
        try {
            monitor.start(); publish(shared, JSONArray().put(a).put(b)); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(alerts.isEmpty())
            publish(shared, JSONArray().put(JSONObject(a.toString()).put("status", "idle")).put(b))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
            assertEquals(listOf("pane-a"), alerts)
            monitor.stop()
            val restarted = AgentMonitor(shared, prefs, { _, _, _ -> fail("replayed completion") }, {})
            restarted.start(); publish(shared, JSONArray().put(JSONObject(a.toString()).put("status", "idle"))); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); restarted.stop()
        } finally { monitor.stop() }
    }
    @Test fun mutedAgentDoesNotAffectOtherOrNewAgentsAndReconnectIsNotCompletion() {
        val shared = SharedRelay.get(context); val prefs = MonitorPreferences(context); val alerts = mutableListOf<String>()
        val a = agent("a"); val b = agent("b"); prefs.mute(MonitorIdentity.key("relay", a), true)
        val monitor = AgentMonitor(shared, prefs, { _, row, _ -> alerts += row.getString("pane_id") }, {})
        try {
            monitor.start(); publish(shared, JSONArray().put(a).put(b)); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            shared.event(JSONObject().put("type", "status").put("relayId", "relay").put("status", "closed")); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20)); assertTrue(alerts.isEmpty())
            publish(shared, JSONArray().put(JSONObject(a.toString()).put("status", "idle")).put(JSONObject(b.toString()).put("status", "idle")))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); assertEquals(listOf("pane-b"), alerts)
            prefs.mute(MonitorIdentity.key("relay", a), false); monitor.reset(MonitorIdentity.key("relay", a)); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); assertEquals(1, alerts.size)
        } finally { monitor.stop() }
    }
    @Test fun aNewAgentIsAutomaticallyMonitoredWhileRemovedAgentsNeverGenerateCompletion() {
        val shared = SharedRelay.get(context); val prefs = MonitorPreferences(context); val alerts = mutableListOf<String>()
        val monitor = AgentMonitor(shared, prefs, { _, row, _ -> alerts += row.getString("pane_id") }, {})
        try {
            monitor.start(); publish(shared, JSONArray().put(agent("old"))); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            publish(shared, JSONArray().put(agent("new"))); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(alerts.isEmpty())
            publish(shared, JSONArray().put(agent("new").put("status", "idle"))); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
            assertEquals(listOf("pane-new"), alerts)
        } finally { monitor.stop() }
    }
    @Test fun notificationContainsOnlyLabelsAndRoutesExactSessionWithoutBody() {
        val shared = SharedRelay.get(context); val a = agent().put("prompt", "PRIVATE PROMPT").put("command", "PRIVATE COMMAND")
        MonitorNotifications.createChannels(context)
        MonitorNotifications.completed(context, shared, "relay", a, JSONObject().put("source", "codex_runtime").put("timing", "turn_elapsed").put("elapsed_ms", 123000))
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals(1, notifications.size)
        val notification = notifications.single(); assertEquals("writer-a · 已完成", notification.extras.getCharSequence("android.title").toString())
        assertFalse(notification.extras.toString().contains("PRIVATE")); assertNotNull(notification.publicVersion)
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.visibility)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MonitorNotifications.OPEN_AGENT, intent.action)
        val route = JSONObject(intent.getStringExtra(MonitorNotifications.EXTRA_ROUTE)!!)
        assertTrue(MonitorIdentity.matchesRoute("relay", a, route)); assertFalse(MonitorIdentity.matchesRoute("relay", agent("other"), route))
    }
}
