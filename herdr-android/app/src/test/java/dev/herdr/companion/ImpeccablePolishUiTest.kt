package dev.herdr.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
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
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Offline native Views/Canvas captures. Never uses the owner's devices or conversation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImpeccablePolishUiTest {
    private val app get() = RuntimeEnvironment.getApplication() as Context
    @Before fun setup() { SharedRelay.resetForTest(); for (name in listOf("private_state", "background-monitor", "file-browser")) app.getSharedPreferences(name, 0).edit().clear().commit() }
    @After fun cleanup() { SharedRelay.resetForTest() }
    private fun get(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    private fun call(owner: Any, name: String) = owner.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(owner)
    private fun agent(id: String, title: String, status: String, ws: String = "lab") = JSONObject().put("pane_id", id).put("terminal_id", "terminal-$id").put("agent_session_id", "session-$id")
        .put("generation", 1).put("workspace_id", ws).put("server_session_id", "primary").put("cwd", "/work/$ws").put("agent", "codex").put("tab_id", id).put("tab_label", title).put("status", status)
    private fun message(id: String, role: String, text: String) = JSONObject().put("id", id).put("role", role).put("text", text)
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun capture(root: View, name: String, widthDp: Int, heightDp: Int) {
        val density = root.resources.displayMetrics.density; val w = (widthDp * density).toInt(); val h = (heightDp * density).toInt()
        repeat(3) { shadowOf(Looper.getMainLooper()).idle(); root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); root.layout(0,0,w,h); root.viewTreeObserver.dispatchOnPreDraw() }
        val bitmap = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff111417.toInt()); root.draw(Canvas(bitmap))
        val out = File("build/reports/ui-polish/${System.getenv("HERDR_UI_CAPTURE") ?: "after"}").apply { mkdirs() }
        File(out,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    private fun screens(width: Int, height: Int, scale: Float, suffix: String) {
        val shared = SharedRelay.get(app)
        shared.vault.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }.set(shared.vault, lazyOf(javax.crypto.spec.SecretKeySpec(ByteArray(32) { 23 }, "AES")))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); val activity = controller.get()
        val configuration = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = scale }
        @Suppress("DEPRECATION") activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        val writer = agent("a", "writer-1", "working"); val researcher = agent("b", "researcher", "done"); val reviewer = agent("c", "reviewer · 长任务名称与多语言测试", "blocked").put("prompt", "运行命令前需要你的确认")
        val rows = JSONArray().put(writer).put(researcher).put(reviewer).put(agent("d", "imager-1", "idle", "figures"))
        val workspaces = JSONArray().put(JSONObject().put("workspace_id","lab").put("label","Research Lab").put("cwd","/work/lab").put("number",1))
            .put(JSONObject().put("workspace_id","figures").put("label","Paper Figures").put("cwd","/work/figures").put("number",2))
        set(activity,"relays",JSONArray().put(JSONObject().put("id","fixture").put("label","研究工作站"))); set(activity,"relayId","fixture"); set(activity,"tab","agents")
        @Suppress("UNCHECKED_CAST") val inventory = get(activity,"agents") as MutableMap<String,JSONArray>
        inventory["fixture"] = rows
        @Suppress("UNCHECKED_CAST") val statuses = get(activity,"statuses") as MutableMap<String,String>
        statuses["fixture"] = "connected"
        val ui = get(activity,"nativeAgents") as NativeAgents
        ui.onEvent(JSONObject().put("type","message").put("relayId","fixture").put("message",JSONObject().put("type","workspaces").put("workspaces",workspaces)))
        val root = activity.window.decorView
        try {
            call(activity,"render"); capture(root,"agents-$suffix",width,height)
            val nav = get(activity,"nav") as LinearLayout
            assertEquals(if (width >= 600) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL, nav.orientation)
            assertEquals(3, nav.childCount)
            for (i in 0 until nav.childCount) assertTrue(nav.getChildAt(i).height >= AgentStyle.dp(activity,48))
            ui.open("fixture",writer)
            val state = get(ui,"conversation") as ConversationState
            state.entries.clear(); state.entries["user"] = message("user","user","帮我检查这次实验的结果，先看关键变化。")
            state.entries["intro"] = message("intro","assistant","已完成第一轮核对。\n\n### 两处值得关注\n\n- 数据集划分保持一致，没有发现泄漏。\n- 新模型的成功率提高，但推理延迟略有增加。")
            state.entries["tools"] = message("tools","assistant","").put("tools",JSONArray().put(JSONObject().put("id","t1").put("name","exec_command").put("state","completed").put("input",JSONObject().put("cmd","python evaluate.py --split validation")).put("output","Validation completed. 48 / 50 episodes succeeded."))
                .put(JSONObject().put("id","t2").put("name","view_image").put("state","completed").put("input",JSONObject().put("note","Synthetic fixture only"))))
            state.entries["followup"] = message("followup","assistant","我会继续核对失败样例，再给出可复现的结论。")
            set(ui,"uiDraft",""); call(ui,"render")
            (get(ui,"runStatusView") as AgentRunStatusView).show(AgentRunPresentation.Display("运行中","本轮 04:32","本轮总用时，包含工具执行和等待时间","working",true))
            (get(ui,"stateLabel") as TextView).text = "已同步 4 条对话"
            capture(root,"conversation-$suffix",width,height)
            val transcript = get(ui,"conversationScroll") as ScrollView
            assertTrue("Transcript has no room", transcript.height > AgentStyle.dp(activity, if (height < 650) 170 else 350))
            val editor = (get(ui,"composerPanel") as NativeComposerView).editor
            val inputPanel = get(ui,"composerPanel") as NativeComposerView
            assertTrue(inputPanel.sendButton.height >= AgentStyle.dp(activity,48))
            assertEquals(WorkspaceBinding.fromAgent("fixture",writer), WorkspaceBinding.fromAgent("fixture",get(ui,"selected") as JSONObject))
            if (width == 400 && scale == 1f) {
                capture(root,"keyboard-phone",width,500)
                assertTrue((get(ui,"conversationScroll") as ScrollView).height >= AgentStyle.dp(activity,150))
                capture(root,"conversation-$suffix",width,height)
            }
            assertTrue(editor.isEnabled); assertTrue(editor.text.isEmpty())
            if (scale == 1f && width == 400) {
                val group = views(root).filterIsInstance<NativeToolGroup>().first(); val header = views(group).first { it.contentDescription?.startsWith("工具组：") == true }; header.performClick()
                capture(root,"tools-$suffix",width,height)
                set(ui,"mode","terminal"); set(ui,"content","$ python evaluate.py\nValidation: 48 / 50\nSuccess rate: 96.0%\nResults saved to reports/summary.json\n"); call(ui,"render")
                (get(ui,"runStatusView") as AgentRunStatusView).show(AgentRunPresentation.Display("已完成","上轮 04:32","已停止计时 · 等待下一条指令","done"))
                capture(root,"terminal-$suffix",width,height)
            }
            ui.back()
            set(activity,"tab","files"); set(activity,"selectedAgent",writer); set(activity,"entries",JSONArray().put(JSONObject().put("path","docs").put("name","docs").put("kind","directory"))
                .put(JSONObject().put("path","results").put("name","results").put("kind","directory"))
                .put(JSONObject().put("path","README.md").put("name","README.md").put("kind","file").put("size",2048))
                .put(JSONObject().put("path","实验报告.pdf").put("name","实验报告.pdf").put("kind","file").put("size",420000)))
            call(activity,"render"); capture(root,"workspace-$suffix",width,height)
            set(activity,"tab","devices"); call(activity,"render"); capture(root,"devices-$suffix",width,height)
            val view = DocumentReviewer(activity,"/work/lab",{ true },{ _,_ -> }) { path,done -> done(JSONObject().put("path",path).put("kind","pdf").put("size",48L*1024*1024).put("preview_available",false).put("preview_reason","文件超过 12 MiB 预览限制；仍可下载或分享原文件"),null) }
            view.open(ReviewReference("reports/实验报告.pdf")); capture(ShadowDialog.getLatestDialog().window!!.decorView,"file-$suffix",width,height); view.close()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun phoneScreens() = screens(400,850,1f,"phone")
    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi") fun narrowLargeTextScreens() = screens(360,800,1.3f,"large-text")
    @Test @Config(qualifiers = "w800dp-h600dp-xhdpi") fun expandedScreens() = screens(800,600,1f,"expanded")
}
