package dev.herdr.companion

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Spinner
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentLaunchDefaultsUiTest {
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun codexDefaultsAreVisibleButExtraArgumentsRemainEmpty() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val workspace = JSONObject().put("workspace_id", "w1").put("cwd", "/work/demo").put("label", "演示项目")
        val profiles = JSONArray().put(JSONObject().put("id", "codex").put("label", "Codex")).put(JSONObject().put("id", "claude").put("label", "Claude"))
        val defaults = JSONObject().put("codex", JSONArray().put("-c").put("tui.terminal_title=[\"thread-id\",\"thread-name\"]"))
        var payload: JSONObject? = null
        val dialog = AgentManagementDialogs.launch(activity.get(), workspace, profiles, true, defaults) { value, _ -> payload = value }
        shadowOf(Looper.getMainLooper()).idle()
        val decor = dialog.window!!.decorView
        val inputs = views(decor).filterIsInstance<EditText>()
        val summary = views(decor).filterIsInstance<TextView>().single { it.contentDescription == "电脑端默认启动参数" }
        assertEquals(View.VISIBLE, summary.visibility)
        assertTrue(summary.text.contains("thread-id")); assertTrue(summary.text.contains("无需手填"))
        assertEquals("", inputs[1].text.toString())
        val spinner = views(decor).filterIsInstance<Spinner>().single()
        spinner.setSelection(1); shadowOf(Looper.getMainLooper()).idle(); assertEquals(View.GONE, summary.visibility)
        inputs[1].setText("--custom")
        spinner.setSelection(0); shadowOf(Looper.getMainLooper()).idle(); assertEquals(View.VISIBLE, summary.visibility)
        assertEquals("--custom", inputs[1].text.toString())
        inputs[1].setText(""); inputs[0].setText("writer")
        decor.measure(View.MeasureSpec.makeMeasureSpec(840, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.AT_MOST)); decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        val image = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888); decor.draw(Canvas(image))
        val file = File("build/reports/launch-defaults/launch-api-${android.os.Build.VERSION.SDK_INT}.png"); file.parentFile!!.mkdirs(); file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertNotNull(payload); assertFalse(payload!!.has("agent_args")); assertEquals("codex", payload!!.getString("profile_id"))
        dialog.dismiss(); activity.pause().stop().destroy()
    }
}
