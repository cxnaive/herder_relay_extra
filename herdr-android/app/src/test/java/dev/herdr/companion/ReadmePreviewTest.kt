package dev.herdr.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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

/** Public documentation scenes rendered by native Views. No real accounts, endpoints, or user data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadmePreviewTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun setup() {
        SharedRelay.resetForTest()
        for (name in listOf("private_state", "background-monitor", "file-browser", "terminal-appearance", AgentGroupExpansion.PREFERENCES)) {
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    private fun get(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    private fun call(owner: Any, name: String) = owner.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(owner)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun agent(id: String, title: String, ws: String, status: String) = JSONObject().put("pane_id", id).put("tab_id", id)
        .put("terminal_id", "terminal-$id").put("generation", 1).put("agent_session_id", "session-$id")
        .put("workspace_id", ws).put("server_session_id", "primary").put("cwd", "/work/$ws")
        .put("agent", "codex").put("tab_label", title).put("status", status)
    private fun workspace(id: String, title: String, number: Int) = JSONObject().put("workspace_id", id).put("label", title)
        .put("cwd", "/work/$id").put("number", number)
    private fun capture(root: View, name: String) {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1700, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 800, 1700)
        }
        views(root).filterIsInstance<android.widget.ScrollView>().forEach { it.scrollTo(0, 0) }
        val image = Bitmap.createBitmap(800, 1700, Bitmap.Config.ARGB_8888)
        image.eraseColor(AgentStyle.background); root.draw(Canvas(image))
        val target = File("build/reports/readme-preview/$name.png"); target.parentFile?.mkdirs()
        target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun renderFourCurrentNativeScreensWithoutConnectingToAnyComputer() {
        val shared = SharedRelay.get(context)
        set(shared.vault, "key\$delegate", lazyOf(SecretKeySpec(ByteArray(32) { 47 }, "AES")))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get(); val root = activity.window.decorView
        val ui = get(activity, "nativeAgents") as NativeAgents
        val writer = agent("writer", "writer", "robotics", "working")
        val trainer = agent("trainer", "trainer", "experiments", "idle")
        val reviewer = agent("reviewer", "reviewer", "paper", "done")
        val computers = JSONArray().put(JSONObject().put("id", "lab").put("label", "研究工作站"))
            .put(JSONObject().put("id", "server").put("label", "训练服务器"))
        // Populate presentation state after Activity startup; the engine has no remote endpoints.
        set(shared.engine, "state", JSONObject().put("relays", computers).put("auth", JSONObject().put("relays",
            JSONObject().put("lab", JSONObject().put("role", "controller")).put("server", JSONObject().put("role", "controller")))))
        set(activity, "relays", computers); set(activity, "relayId", "lab"); set(activity, "tab", "agents")
        @Suppress("UNCHECKED_CAST") val inventory = get(activity, "agents") as MutableMap<String, JSONArray>
        inventory["lab"] = JSONArray().put(writer).put(trainer)
        inventory["server"] = JSONArray().put(reviewer)
        @Suppress("UNCHECKED_CAST") val statuses = get(activity, "statuses") as MutableMap<String, String>
        statuses["lab"] = "connected"; statuses["server"] = "connected"
        @Suppress("UNCHECKED_CAST") val online = get(ui, "online") as MutableSet<String>
        val workspaces = mapOf("lab" to JSONArray().put(workspace("robotics", "机器人研究", 1)).put(workspace("experiments", "模型实验", 2)),
            "server" to JSONArray().put(workspace("paper", "论文写作", 1)))
        for ((id, list) in workspaces) {
            fun event(value: JSONObject) = ui.onEvent(JSONObject().put("type", "message").put("relayId", id).put("message", value))
            event(JSONObject().put("type", "push_config").put("protocol", 3).put("inventory", JSONObject().put("state", "ready")))
            event(JSONObject().put("type", "workspaces").put("workspaces", list))
            online.add(id)
        }
        try {
            call(activity, "render")
            val content = get(activity, "content") as FrameLayout
            content.findViewWithTag<AgentGroupHeader>(AgentGroupExpansion.workspaceKey(
                AgentPresentation.Group("lab", "primary", "experiments", "", "", 0, emptyList()))).performClick()
            content.findViewWithTag<AgentGroupHeader>(AgentGroupExpansion.computerKey("server")).performClick()
            assertEquals(1, views(root).count { it.contentDescription?.toString() == "Tab：writer" })
            assertTrue(views(root).filterIsInstance<AgentGroupHeader>().all { !it.contentDescription.contains("未连接") })
            capture(root, "agents")

            // Render example transcript/terminal state without starting network streams.
            online.clear(); ui.open("lab", writer)
            val conversation = get(ui, "conversation") as ConversationState
            conversation.entries.clear()
            fun message(id: String, role: String, text: String) = JSONObject().put("id", id).put("role", role).put("text", text)
            conversation.entries["request"] = message("request", "user", "检查评测结果，整理一份简短报告。")
            conversation.entries["answer"] = message("answer", "assistant", "已完成首轮核对。\n\n### 评测摘要\n\n- 成功率：**96.0%**，较基线提升 4%。\n- 验证集划分一致，没有发现泄漏。")
            conversation.entries["tools"] = message("tools", "assistant", "").put("tools", JSONArray()
                .put(JSONObject().put("id", "read").put("name", "read_file").put("state", "completed").put("input", JSONObject().put("report", "validation metrics")))
                .put(JSONObject().put("id", "evaluate").put("name", "exec_command").put("state", "completed").put("input", JSONObject().put("cmd", "python evaluate.py --split validation")).put("output", "48 / 50 episodes succeeded")))
            conversation.entries["followup"] = message("followup", "assistant", "接下来复查两个失败样例，再生成实验报告。")
            set(ui, "uiDraft", ""); call(ui, "render")
            online.add("lab"); call(ui, "updateSendState")
            (get(ui, "stateLabel") as TextView).text = "对话已同步"
            (get(ui, "runStatusView") as AgentRunStatusView).show(AgentRunPresentation.Display("运行中", "本轮 02:18", "示例运行时间", "working", true))
            assertTrue(views(root).filterIsInstance<NativeToolGroup>().isNotEmpty())
            capture(root, "conversation")

            online.clear(); set(ui, "mode", "terminal")
            set(ui, "content", "\u001b[32m$\u001b[0m python evaluate.py --split validation\n\nLoading checkpoint ... done\nDataset:  validation / 50 episodes\nDevice:   cuda:0\n\n[10/50]  success=100.0%\n[30/50]  success= 96.7%\n[50/50]  success= 96.0%\n\n\u001b[32mEvaluation complete\u001b[0m\n  Successful episodes  48 / 50\n  Average latency      42 ms\n\nSaved reports/metrics.json\nSaved reports/summary.md\n\n\u001b[32m$\u001b[0m ")
            set(ui, "frame", JSONObject().put("columns", 58).put("no_echo", false)); set(ui, "uiDraft", "")
            call(ui, "render"); online.add("lab"); call(ui, "updateSendState")
            (get(ui, "stateLabel") as TextView).text = "终端已同步 · 手机换行"
            (get(ui, "runStatusView") as AgentRunStatusView).show(AgentRunPresentation.Display("已完成", "上轮 02:18", "示例运行时间", "done"))
            assertTrue(views(root).any { it is TextView && it.text == "退格" && it.isEnabled })
            capture(root, "terminal")

            ui.back(); set(activity, "tab", "files"); set(activity, "selectedAgent", writer)
            set(activity, "entries", JSONArray().put(JSONObject().put("path", "docs").put("name", "docs").put("kind", "directory"))
                .put(JSONObject().put("path", "reports").put("name", "reports").put("kind", "directory"))
                .put(JSONObject().put("path", "scripts").put("name", "scripts").put("kind", "directory"))
                .put(JSONObject().put("path", "README.md").put("name", "README.md").put("kind", "file").put("size", 4096))
                .put(JSONObject().put("path", "config.yaml").put("name", "config.yaml").put("kind", "file").put("size", 1240))
                .put(JSONObject().put("path", "实验报告.pdf").put("name", "实验报告.pdf").put("kind", "file").put("size", 420000)))
            @Suppress("UNCHECKED_CAST") val capabilities = get(activity, "capabilities") as MutableMap<String, Set<String>>
            capabilities["lab"] = setOf("workspace_file_read_v1")
            call(activity, "render"); capture(root, "workspace")
            assertTrue(views(root).filterIsInstance<TextView>().any { it.text == "实验报告.pdf" })
            assertTrue((get(shared.engine, "connections") as Map<*, *>).isEmpty())
        } finally { controller.pause().stop().destroy() }
    }
}
