package dev.herdr.companion

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class AgentLaunchArgumentsUiTest {
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private val workspace = JSONObject().put("workspace_id", "workspace-one").put("cwd", "/work/project")
    private val profiles = JSONArray().put(JSONObject().put("id", "codex").put("label", "Codex"))

    @Test fun invalidArgumentsStayEditableAndConfirmedFailureCanRetry() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        var calls = 0
        var reply: ManagementReply? = null
        var payload: JSONObject? = null
        val dialog = AgentManagementDialogs.launch(activity.get(), workspace, profiles, true) { value, done -> calls++; payload = value; reply = done }
        shadowOf(Looper.getMainLooper()).idle()
        val inputs = views(dialog.window!!.decorView).filterIsInstance<EditText>()
        inputs[0].setText("writer"); inputs[1].setText("'unclosed")
        val create = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        create.performClick()
        assertEquals(0, calls); assertTrue(inputs.all { it.isEnabled })
        assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("引号未闭合") })
        inputs[1].setText("--no-daemon --model 'custom model'"); inputs[2].setText("调研任务")
        create.performClick(); create.performClick()
        assertEquals(1, calls); assertTrue(inputs.none { it.isEnabled })
        assertEquals("custom model", payload!!.getJSONArray("agent_args").getString(2))
        assertEquals("调研任务", payload!!.getString("prompt"))
        reply!!(null, "配置不可用，尚未创建")
        assertTrue(create.isEnabled); assertTrue(inputs.all { it.isEnabled })
        assertEquals("--no-daemon --model 'custom model'", inputs[1].text.toString())
        create.performClick(); assertEquals(2, calls)
        reply!!(JSONObject().put("pane_id", "pane-new"), null)
        assertFalse(dialog.isShowing)
        activity.pause().stop().destroy()
    }

    @Test fun oldRelayExplainsDisabledFieldAndStillAllowsNoArgumentLaunch() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        var payload: JSONObject? = null
        val dialog = AgentManagementDialogs.launch(activity.get(), workspace, profiles, false) { value, done -> payload = value; done(null, "尚未创建") }
        shadowOf(Looper.getMainLooper()).idle()
        val inputs = views(dialog.window!!.decorView).filterIsInstance<EditText>()
        assertFalse(inputs[1].isEnabled)
        assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("升级 Relay") })
        inputs[0].setText("writer")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertNotNull(payload); assertFalse(payload!!.has("agent_args"))
        assertFalse(inputs[1].isEnabled); assertTrue(inputs[0].isEnabled)
        dialog.dismiss(); activity.pause().stop().destroy()
    }

    @Test fun uncertainCreationCannotResubmitOrEditArguments() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        var calls = 0
        val dialog = AgentManagementDialogs.launch(activity.get(), workspace, profiles, true) { _, done -> calls++; done(null, "请求超时，执行结果未确认") }
        shadowOf(Looper.getMainLooper()).idle()
        val inputs = views(dialog.window!!.decorView).filterIsInstance<EditText>()
        inputs[0].setText("writer"); inputs[1].setText("--no-daemon")
        repeat(2) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        assertEquals(1, calls); assertTrue(inputs.none { it.isEnabled })
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.dismiss(); activity.pause().stop().destroy()
    }
    @Test fun realWorkspaceLaunchRechecksCapabilityRoleConnectionAndDirectory() {
        for (change in listOf("capability", "role", "connection", "directory")) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup()
            val vault = Vault(activity.get())
            val engine = RelayEngine(activity.get(), vault) {}
            val auth = JSONObject().put("role", "controller")
            val state = JSONObject().put("auth", JSONObject().put("relays", JSONObject().put("computer", auth)))
            engine.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(engine, state)
            val ui = NativeAgents(activity.get(), engine, vault, { emptyMap() }, { _, _ -> }, {}, {})
            val container = FrameLayout(activity.get()); activity.get().setContentView(container); ui.attach(container)
            fun event(message: JSONObject) = ui.onEvent(JSONObject().put("type", "message").put("relayId", "computer").put("message", message))
            fun config(supported: Boolean) = JSONObject().put("type", "push_config").put("protocol", 3)
                .put("inventory", JSONObject().put("state", "ready")).put("agent_profiles", profiles)
                .put("capabilities", JSONArray(if (supported) listOf(AgentLaunchArguments.CAPABILITY) else emptyList<String>()))
            event(config(true))
            event(JSONObject().put("type", "workspaces").put("workspaces", JSONArray().put(JSONObject(workspace.toString()).put("label", "Demo"))))
            ui.onEvent(JSONObject().put("type", "status").put("relayId", "computer").put("status", "connected"))
            shadowOf(Looper.getMainLooper()).idle()
            views(container).single { it.contentDescription?.toString() == "在 Demo 新建 Agent" }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val inputs = views(dialog.window!!.decorView).filterIsInstance<EditText>()
            inputs[0].setText("writer"); inputs[1].setText("--no-daemon")
            assertTrue(inputs[1].isEnabled)
            when (change) {
                "capability" -> event(config(false))
                "role" -> auth.put("role", "reader")
                "connection" -> ui.onEvent(JSONObject().put("type", "status").put("relayId", "computer").put("status", "disconnected"))
                "directory" -> event(JSONObject().put("type", "workspaces").put("workspaces", JSONArray().put(JSONObject(workspace.toString()).put("cwd", "/work/other"))))
            }
            shadowOf(Looper.getMainLooper()).idle()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val reason = if (change == "capability") "启动参数能力已变化" else "工作空间或连接已变化"
            assertTrue(change, views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains(reason) })
            assertNull(vault.read("launch:computer:primary:workspace-one"))
            assertEquals("--no-daemon", inputs[1].text.toString())
            dialog.dismiss(); ui.destroy(); engine.destroy(); activity.pause().stop().destroy()
        }
    }

}
