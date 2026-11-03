package dev.herdr.companion

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Button
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonitorUiTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun setup() {
        SharedRelay.resetForTest()
        context.getSharedPreferences("private_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("background-monitor", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun draw(name: String, view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.AT_MOST)); view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff0b1218.toInt()); view.draw(Canvas(bitmap))
        val out = File("../screenshots/qa-0.2.13"); out.mkdirs(); File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun prepare(): Pair<SharedRelay, JSONObject> {
        val shared = SharedRelay.get(context)
        shared.vault.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }.set(shared.vault, lazyOf(SecretKeySpec(ByteArray(32) { 4 }, "AES")))
        shared.vault.write("state", JSONObject().put("relays", JSONArray().put(JSONObject().put("id", "computer").put("label", "本机"))).toString())
        val agent = JSONObject().put("pane_id", "a").put("terminal_id", "terminal-a").put("agent_session_id", "session-a").put("generation", 1)
            .put("workspace_id", "project").put("cwd", "/work/paper").put("agent", "codex").put("tab_label", "writer-1").put("status", "working")
        shared.event(JSONObject().put("type", "status").put("relayId", "computer").put("status", "connected"))
        fun message(value: JSONObject) = shared.event(JSONObject().put("type", "message").put("relayId", "computer").put("message", value))
        message(JSONObject().put("type", "push_config").put("protocol", 3).put("inventory", JSONObject().put("state", "ready")))
        message(JSONObject().put("type", "agents").put("agents", JSONArray().put(agent)))
        message(JSONObject().put("type", "workspaces").put("workspaces", JSONArray().put(JSONObject().put("workspace_id", "project").put("cwd", "/work/paper").put("label", "论文项目"))))
        return shared to agent
    }
    @Test fun globalMonitoringExplainsPermissionBatteryAndDefaultAllWithoutSilentlyStarting() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); var enabled = 0
        val dialog = MonitorSettingsUi.show(controller.get(), { enabled++ }, {})
        shadowOf(Looper.getMainLooper()).idle()
        val text = views(dialog.window!!.decorView).filterIsInstance<TextView>().joinToString { it.text }
        assertTrue(text.contains("默认关注所有 Agent")); assertTrue(text.contains("强行停止")); assertTrue(text.contains("约 15 秒"))
        assertEquals(0, enabled); assertFalse(MonitorPreferences(context).enabled)
        draw("monitor-settings", dialog.window!!.decorView)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, enabled)
        controller.pause().stop().destroy()
    }
    @Test fun realAgentMenuCanMuteAndUnmuteWithoutMutatingRemoteAgent() {
        val (shared, agent) = prepare(); val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        views(activity.window.decorView).single { it.contentDescription?.toString() == "管理 writer-1" }.performClick(); shadowOf(Looper.getMainLooper()).idle()
        var menu = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("关闭此 Agent 完成提醒", menu.listView.adapter.getItem(3))
        draw("agent-notification-menu", menu.window!!.decorView)
        shadowOf(menu).clickOnItem(3); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(MonitorPreferences(context).muted(MonitorIdentity.key("computer", agent)))
        views(activity.window.decorView).single { it.contentDescription?.toString() == "管理 writer-1" }.performClick(); shadowOf(Looper.getMainLooper()).idle()
        menu = ShadowAlertDialog.getLatestAlertDialog(); assertEquals("开启此 Agent 完成提醒", menu.listView.adapter.getItem(3)); shadowOf(menu).clickOnItem(3)
        assertFalse(MonitorPreferences(context).muted(MonitorIdentity.key("computer", agent)))
        assertEquals("session-a", shared.agents.getValue("computer").getJSONObject(0).getString("agent_session_id"))
        controller.pause().stop().destroy()
    }
    @Test fun notificationOpensExactConversationAndServiceOwnsConnectionAfterActivityDestroyed() {
        val (shared, agent) = prepare(); val serviceOwner = Any(); shared.attachMonitor(serviceOwner) {}
        val controller = Robolectric.buildActivity(MainActivity::class.java, MonitorNotifications.routeIntent(context, "computer", agent)).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        assertTrue(views(activity.window.decorView).any { it.contentDescription?.toString() == "对话选项" })
        val native = activity.javaClass.getDeclaredField("nativeAgents").apply { isAccessible = true }.get(activity)
        val selected = native.javaClass.getDeclaredField("selected").apply { isAccessible = true }.get(native) as JSONObject
        assertEquals("session-a", selected.getString("agent_session_id"))
        controller.pause().stop().destroy()
        assertFalse(shared.uiVisible); assertTrue(shared.monitorOwned); assertTrue(shared.ready("computer"))
        shared.detachMonitor(serviceOwner)
    }
    @Test fun replacedSessionNotificationFallsBackToListNotWrongAgent() {
        val (_, agent) = prepare(); val stale = JSONObject(agent.toString()).put("agent_session_id", "old-session")
        val controller = Robolectric.buildActivity(MainActivity::class.java, MonitorNotifications.routeIntent(context, "computer", stale)).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        assertTrue(views(activity.window.decorView).any { it.contentDescription?.toString() == "管理 writer-1" })
        assertFalse(views(activity.window.decorView).any { it.contentDescription?.toString() == "对话选项" })
        controller.pause().stop().destroy()
    }
    @Test fun monitoringSettingsCanTestSoundAndOpenOnlyCompletionChannelWithoutStartingService() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        val dialog = MonitorSettingsUi.show(activity, { fail("Test must not start monitoring") }, {})
        try {
            val buttons = views(dialog.window!!.decorView).filterIsInstance<Button>()
            buttons.single { it.text == "测试完成提醒" }.performClick()
            val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
            assertEquals(MonitorNotifications.COMPLETION_CHANNEL, notification.channelId)
            assertFalse(MonitorPreferences(context).enabled); assertFalse(AgentMonitorService.active)
            assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("常驻监控状态保持静音") })
            buttons.single { it.text == "完成提醒声音与弹出设置" }.performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
            assertEquals(activity.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            assertEquals(MonitorNotifications.COMPLETION_CHANNEL, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        } finally { dialog.dismiss(); controller.pause().stop().destroy() }
    }
    @Test fun blockedTestNotificationExplainsPermissionWithoutStartingService() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val dialog = MonitorSettingsUi.show(controller.get(), { fail("Test must not start monitoring") }, {})
        try {
            views(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text == "测试完成提醒" }.performClick()
            assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("测试通知未发送"))
            assertFalse(MonitorPreferences(context).enabled)
        } finally { dialog.dismiss(); controller.pause().stop().destroy() }
    }
}
