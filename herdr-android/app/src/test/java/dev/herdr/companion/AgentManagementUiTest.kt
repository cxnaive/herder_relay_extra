package dev.herdr.companion

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.FrameLayout
import org.robolectric.shadows.ShadowAlertDialog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentManagementUiTest {
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun draw(name: String, dialog: AlertDialog) = drawView(name, dialog.window!!.decorView)
    private fun drawView(name: String, view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(840, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.AT_MOST)); view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff0b1218.toInt()); view.draw(Canvas(bitmap))
        val out = File("../screenshots/qa-0.2.9"); out.mkdirs(); File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun workspaceAndAgentMenusAreReachableFromTheRealList() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        val vault = Vault(activity); val engine = RelayEngine(activity, vault) {}
        val state = JSONObject().put("auth", JSONObject().put("relays", JSONObject().put("computer", JSONObject().put("role", "controller"))))
        engine.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(engine, state)
        val agent = JSONObject().put("pane_id", "pane-1").put("terminal_id", "term-1").put("agent_session_id", "session-1").put("generation", 1)
            .put("workspace_id", "ws-1").put("cwd", "/work/paper").put("tab_label", "writer-1").put("agent", "codex").put("name", "Codex")
        val ui = NativeAgents(activity, engine, vault, { mapOf("computer" to JSONArray().put(agent)) }, { _, _ -> }, {}, {})
        val container = FrameLayout(activity); activity.setContentView(container); ui.attach(container)
        fun event(message: JSONObject) = ui.onEvent(JSONObject().put("type", "message").put("relayId", "computer").put("message", message))
        event(JSONObject().put("type", "push_config").put("protocol", 3).put("inventory", JSONObject().put("state", "ready"))
            .put("agent_profiles", JSONArray().put(JSONObject().put("id", "codex").put("label", "Codex"))))
        event(JSONObject().put("type", "workspaces").put("workspaces", JSONArray()
            .put(JSONObject().put("workspace_id", "ws-1").put("cwd", "/work/paper").put("label", "论文项目").put("number", 1))
            .put(JSONObject().put("workspace_id", "ws-empty").put("cwd", "/work/paper").put("label", "空工作空间").put("number", 2))))
        ui.onEvent(JSONObject().put("type", "status").put("relayId", "computer").put("status", "connected")); idle()
        drawView("workspace-list", container)
        val add = views(container).single { it.contentDescription?.toString() == "在 空工作空间 新建 Agent" }
        assertTrue(add.isEnabled); add.performClick(); idle()
        val launch = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(views(launch.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("ws-empty") }); launch.dismiss()
        views(container).single { it.contentDescription?.toString() == "管理 writer-1" }.performClick(); idle()
        val menu = ShadowAlertDialog.getLatestAlertDialog(); assertEquals("重命名 Agent", menu.listView.adapter.getItem(0))
        shadowOf(menu).clickOnItem(0); idle()
        val rename = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("writer-1", views(rename.window!!.decorView).filterIsInstance<EditText>().single().text.toString())
        rename.dismiss(); ui.destroy(); engine.destroy(); controller.pause().stop().destroy()
    }
    @Test fun renameHasVisibleValidationAndSendsOnlyOnceWhileWaiting() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        var calls = 0; var reply: ManagementReply? = null; var payload: JSONObject? = null
        val dialog = AgentManagementDialogs.rename(activity, "旧 Tab") { value, done -> calls++; payload = value; reply = done }; idle()
        val input = views(dialog.window!!.decorView).filterIsInstance<EditText>().single(); input.setText("")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals(0, calls); assertNotNull(input.error)
        input.setText("手机重命名"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, calls); assertEquals("手机重命名", payload!!.getString("name")); assertTrue(dialog.isShowing)
        draw("rename-pending", dialog); reply!!(JSONObject(), null); assertFalse(dialog.isShowing); controller.pause().stop().destroy()
    }
    @Test fun launchShowsFixedWorkspaceAndWaitsForAcknowledgement() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        val ws = JSONObject().put("workspace_id", "workspace-two").put("cwd", "/work/shared").put("label", "论文项目")
        var payload: JSONObject? = null; var reply: ManagementReply? = null
        val dialog = AgentManagementDialogs.launch(activity, ws, JSONArray().put(JSONObject().put("id", "codex").put("label", "Codex")), launchArgsSupported = true) { value, done -> payload = value; reply = done }; idle()
        val inputs = views(dialog.window!!.decorView).filterIsInstance<EditText>(); assertEquals(3, inputs.size)
        inputs.first().setText("Writer"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertNull(payload)
        inputs.first().setText("writer-2"); inputs[1].setText("--no-daemon"); inputs.last().setText("调研论文")
        draw("workspace-launch", dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals("workspace-two", payload!!.getString("workspace_id")); assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertEquals("--no-daemon", payload!!.getJSONArray("agent_args").getString(0)); assertTrue(inputs.none { it.isEnabled })
        reply!!(null, "请求超时，执行结果未确认"); assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled); assertTrue(dialog.isShowing)
        dialog.dismiss(); controller.pause().stop().destroy()
    }
    @Test fun settingsLoadsRuntimeChoicesAndConfirmsEffectiveValues() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        var readReply: ManagementReply? = null; var writeReply: ManagementReply? = null; var payload: JSONObject? = null
        val dialog = AgentManagementDialogs.settings(activity, true, { readReply = it }, { p, done -> payload = p; writeReply = done }); idle()
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        readReply!!(AgentManagementTest.settingsFixture(), null); idle()
        val spinners = views(dialog.window!!.decorView).filterIsInstance<Spinner>(); assertEquals(2, spinners.size)
        spinners[0].setSelection(1); idle(); assertEquals(listOf("high", "maximum-custom"), (0 until spinners[1].count).map { spinners[1].getItemAtPosition(it) })
        spinners[1].setSelection(1); idle(); draw("model-effort", dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals("runtime-model-b", payload!!.getString("model")); assertEquals("maximum-custom", payload!!.getString("effort"))
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        writeReply!!(AgentManagementTest.settingsFixture().put("model", "runtime-model-b").put("effort", "maximum-custom").put("settings_revision", "revision-b"), null)
        assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("已确认，下一轮生效") })
        dialog.dismiss(); controller.pause().stop().destroy()
    }
    @Test fun readerAndUnavailableRuntimeNeverOfferAnEnabledApplyButton() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        for (available in listOf(false, true)) {
            val dialog = AgentManagementDialogs.settings(activity, false, { it(AgentManagementTest.settingsFixture().put("available", available).put("reason", "无法确认身份"), null) }, { _, _ -> fail("reader dispatched write") })
            idle(); assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled); dialog.dismiss()
        }
        controller.pause().stop().destroy()
    }
    @Test fun mismatchedSettingsReceiptCannotClaimSuccessOrAutoRetry() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get(); var count = 0
        val dialog = AgentManagementDialogs.settings(activity, true, { it(AgentManagementTest.settingsFixture(), null) }, { _, done -> count++; done(JSONObject().put("model", "wrong").put("effort", "medium"), null) })
        idle(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals(1, count)
        assertFalse(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("已确认，下一轮生效") }); assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.dismiss(); controller.pause().stop().destroy()
    }
}
