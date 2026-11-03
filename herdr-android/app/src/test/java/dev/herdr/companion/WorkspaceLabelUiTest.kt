package dev.herdr.companion

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
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
import java.io.File
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceLabelUiTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun setup() {
        SharedRelay.resetForTest()
        context.getSharedPreferences("private_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("background-monitor", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    private fun agent() = JSONObject().put("pane_id", "pane-b").put("terminal_id", "terminal-b").put("generation", 1)
        .put("agent_session_id", "session-b").put("workspace_id", "ws-b").put("server_session_id", "primary")
        .put("cwd", "/work/benchmark").put("agent", "codex").put("tab_label", "benchmark").put("status", "idle")
    private fun workspace(id: String, name: String, number: Int, cwd: String, server: String = "primary") = JSONObject()
        .put("workspace_id", id).put("label", name).put("number", number).put("cwd", cwd).put("server_session_id", server)
    private fun workspaces() = JSONArray().put(workspace("ws-a", "Unrelated Project", 1, "/work/other"))
        .put(workspace("ws-b", "Benchmark Lab", 3, "/work/benchmark"))
    private fun message(id: String, data: JSONObject) = JSONObject().put("type", "message").put("relayId", id).put("message", data)
    @Test fun conversationHeaderUsesExactWorkspaceOnOpenAndInventoryUpdates() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        val vault = Vault(activity)
        vault.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }.set(vault, lazyOf(SecretKeySpec(ByteArray(32) { 14 }, "AES")))
        val engine = RelayEngine(activity, vault) {}
        val selected = agent()
        val ui = NativeAgents(activity, engine, vault, { mapOf("computer" to JSONArray().put(selected)) }, { _, _ -> }, {}, {})
        val container = FrameLayout(activity); activity.setContentView(container); ui.attach(container)
        try {
            ui.onEvent(message("computer", JSONObject().put("type", "workspaces").put("workspaces", workspaces())))
            ui.open("computer", selected); shadowOf(Looper.getMainLooper()).idle()
            val subtitle = ui.javaClass.getDeclaredField("identitySubtitle").apply { isAccessible = true }.get(ui) as TextView
            assertEquals("computer · Benchmark Lab · /work/benchmark", subtitle.text.toString())
            // Earlier empty workspaces and another computer may arrive after the detail opens.
            ui.onEvent(message("aaa-other-computer", JSONObject().put("type", "workspaces").put("workspaces", JSONArray()
                .put(workspace("ws-b", "Wrong Computer", 0, "/work/benchmark")))))
            ui.onEvent(message("computer", JSONObject().put("type", "workspaces").put("workspaces", workspaces().put(workspace("empty", "Empty First", 0, "/work/empty")))))
            assertEquals("computer · Benchmark Lab · /work/benchmark", subtitle.text.toString())
            val renamed = workspaces(); renamed.getJSONObject(1).put("label", "Benchmark Renamed")
            ui.onEvent(message("computer", JSONObject().put("type", "workspaces").put("workspaces", renamed)))
            assertEquals("computer · Benchmark Renamed · /work/benchmark", subtitle.text.toString())
            val current = ui.javaClass.getDeclaredField("selected").apply { isAccessible = true }.get(ui) as JSONObject
            assertEquals("session-b", current.getString("agent_session_id"))
            assertEquals(WorkspaceBinding.fromAgent("computer", selected), WorkspaceBinding.fromAgent("computer", current))
            container.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY))
            container.layout(0, 0, 800, 1440)
            val bitmap = Bitmap.createBitmap(800, 220, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff0b1218.toInt()); container.draw(Canvas(bitmap))
            val directory = File("../screenshots/qa-0.2.12"); directory.mkdirs()
            File(directory, "workspace-header.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        } finally { ui.destroy(); engine.destroy(); controller.pause().stop().destroy() }
    }
    @Test fun completionNotificationUsesTargetWorkspaceNotFirstWorkspaceOrOtherServer() {
        val shared = SharedRelay.get(context); val selected = agent()
        shared.event(message("computer", JSONObject().put("type", "workspaces").put("workspaces", workspaces()
            .put(workspace("ws-b", "Wrong Server", 0, "/work/benchmark", "aaa-server")))))
        shared.event(message("aaa-other-computer", JSONObject().put("type", "workspaces").put("workspaces", JSONArray()
            .put(workspace("ws-b", "Wrong Computer", 0, "/work/benchmark")))))
        assertEquals("Benchmark Lab", shared.workspaceName("computer", selected))
        MonitorNotifications.createChannels(context)
        MonitorNotifications.completed(context, shared, "computer", selected, JSONObject())
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val text = notification.extras.getCharSequence("android.text").toString()
        assertTrue(text.contains("Benchmark Lab")); assertFalse(text.contains("Wrong")); assertFalse(text.contains("Unrelated"))
        val route = JSONObject(shadowOf(notification.contentIntent).savedIntent.getStringExtra(MonitorNotifications.EXTRA_ROUTE)!!)
        assertTrue(MonitorIdentity.matchesRoute("computer", selected, route))
    }
}
