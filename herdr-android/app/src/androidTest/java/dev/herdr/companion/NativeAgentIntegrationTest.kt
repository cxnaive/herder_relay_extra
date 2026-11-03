package dev.herdr.companion

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class NativeAgentIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    @Before fun clearPreviousDraftsAndDeliveryState() {
        val vault = Vault(context)
        listOf("prompt:", "draft:", "delivery:").forEach { prefix -> vault.names(prefix).forEach(vault::remove) }
    }
    private fun visible(text: String, timeout: Long = 15000): androidx.test.uiautomator.UiObject2 {
        val description = when (text) { "发送", "输入文本" -> text; "‹ Agents" -> "返回 Agents"; else -> null }
        return requireNotNull(device.wait(Until.findObject(if (description == null) By.text(text) else By.desc(description)), timeout)) { "UI control not found: $text" }
    }
    private fun screenshot(name: String) { device.waitForIdle(); Thread.sleep(300); val path = File(context.getExternalFilesDir(null), "native-qa/$name.png"); path.parentFile!!.mkdirs(); assertTrue(device.takeScreenshot(path)) }
    private fun inspectTree(view: View) { assertFalse("WebView remains in native UI", view is android.webkit.WebView); if (view is ViewGroup) for (i in 0 until view.childCount) inspectTree(view.getChildAt(i)) }
    @Test fun encryptedRelayDrivesNativeConversationTerminalApprovalAndFiles() {
        MockEncryptedRelay().use { relay ->
            RelayEngine.testClientFactory = { relay.client }
            val vault = Vault(context); vault.write("state", relay.storedState()); vault.names("prompt:").forEach(vault::remove); vault.names("draft:").forEach(vault::remove)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    visible("原生测试 Agent"); screenshot("agents")
                    visible("打开对话").click(); visible("原生对话\n\n这条消息来自加密模拟服务器。")
                    relay.emitLiveUpdate = true; visible("实时增量消息已到达")
                    scenario.onActivity { inspectTree(it.window.decorView) }
                    screenshot("conversation")
                    val editor = device.findObject(By.clazz("android.widget.EditText")); editor.text = "请检查这个项目"
                    visible("发送").click(); val prompt = relay.expect("submit_prompt"); assertEquals("请检查这个项目", prompt.getString("text")); assertEquals("term-a", prompt.getJSONObject("target").getString("terminal_id"))
                    val beforeDisconnect = relay.sessions.size; relay.disconnect()
                    val deadline = System.currentTimeMillis() + 15000
                    while (relay.sessions.size <= beforeDisconnect && System.currentTimeMillis() < deadline) Thread.sleep(100)
                    assertTrue(relay.sessions.size > beforeDisconnect)
                    visible("终端").click(); relay.expect("read_pane")
                    assertNotNull(device.wait(Until.findObject(By.desc("原生终端输出，可横向和纵向滚动")), 10000))
                    visible("Enter").click(); assertEquals("Enter", relay.expect("send_keys").getJSONArray("keys").getString(0))
                    val terminalInput = device.findObject(By.clazz("android.widget.EditText")); terminalInput.text = "echo native"
                    visible("输入文本").click(); assertEquals("echo native", relay.expect("send_terminal_text").getString("text")); screenshot("terminal")
                    relay.agent.put("status", "blocked").put("attention_kind", "approval").put("approval_fingerprint", "approval-1").put("event_id", "event-1").put("prompt", "允许修改项目文件？").put("options", JSONArray().put("允许一次").put("拒绝"))
                    relay.push(JSONObject().put("type", "agents").put("agents", JSONArray().put(relay.agent)))
                    visible("允许一次").click(); val approval = relay.expect("respond"); assertEquals("approval-1", approval.getString("approval_fingerprint")); assertEquals(0, approval.getInt("index"))
                    relay.agent.remove("approval_fingerprint"); relay.agent.remove("options")
                    relay.agent.put("interaction", JSONObject().put("id", "question-1").put("kind", "multi_select").put("question", "选择要检查的部分").put("options", JSONArray().put(JSONObject().put("index", 0).put("label", "后端")).put(JSONObject().put("index", 1).put("label", "客户端"))))
                    relay.push(JSONObject().put("type", "agents").put("agents", JSONArray().put(relay.agent)))
                    visible("回答问题…").click(); device.wait(Until.findObject(By.textContains("后端")), 5000).click(); visible("提交").click()
                    val question = relay.expect("answer_question"); assertEquals("question-1", question.getString("interaction_id")); assertEquals(0, question.getJSONArray("selected_indices").getInt(0))
                    relay.agent.put("status", "idle"); relay.agent.remove("interaction"); relay.push(JSONObject().put("type", "agents").put("agents", JSONArray().put(relay.agent)))
                    visible("工作区").click(); visible("README.md").click(); visible("编辑").click()
                    device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000).text = "# 修改后的文件\n\n保存通过加密协议。"
                    visible("保存").click(); val write = relay.expect("workspace_file_write"); assertEquals("/work/project", write.getString("cwd")); assertEquals("# 修改后的文件\n\n保存通过加密协议。", write.getString("text"))
                    visible("与电脑文件一致"); screenshot("workspace-edit")
                    assertTrue("Fixture protocol errors: ${relay.failures}", relay.failures.isEmpty())
                }
            } finally { RelayEngine.testClientFactory = null }
        }
    }
    @Test fun bootstrapPairingUsesRealHandshakeAndCredentialSurvivesRestart() {
        MockEncryptedRelay().use { relay ->
            RelayEngine.testClientFactory = { relay.client }; Vault(context).write("state", "{}")
            try {
                ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, relay.setup)).use {
                    visible("配对").click(); visible("● 已连接 · 端到端加密"); visible("查看 Agents").click(); visible("原生测试 Agent")
                }
                val saved = JSONObject(Vault(context).read("state")!!); val id = saved.getJSONArray("relays").getJSONObject(0).getString("id")
                assertTrue(relay.enrolled); assertEquals("credential", saved.getJSONObject("auth").getJSONObject("relays").getJSONObject(id).getString("kind"))
                ActivityScenario.launch(MainActivity::class.java).use { visible("原生测试 Agent") }
                assertTrue(relay.failures.isEmpty())
            } finally { RelayEngine.testClientFactory = null }
        }
    }
    @Test fun readerCannotSendAndReplacedSessionDoesNotReceiveOldDraft() {
        MockEncryptedRelay().use { relay ->
            relay.denyWrites = true; RelayEngine.testClientFactory = { relay.client }; Vault(context).write("state", relay.storedState())
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    visible("打开对话").click(); visible("对话已同步"); assertFalse(visible("发送").isEnabled)
                    relay.agent.put("generation", 2); relay.push(JSONObject().put("type", "agents").put("agents", JSONArray().put(relay.agent)))
                    visible("此 Agent 会话已被替换。请返回列表重新打开。")
                    assertFalse(visible("发送").isEnabled)
                    assertFalse(relay.requests.any { it.optString("type") in listOf("submit_prompt", "send_keys", "respond") })
                }
            } finally { RelayEngine.testClientFactory = null }
        }
    }
    @Test fun gatewayChallengeAndBinaryEncryptedFramesDriveNativeUI() {
        MockEncryptedRelay(gateway = true).use { relay ->
            RelayEngine.testClientFactory = { relay.client }; Vault(context).write("state", "{}")
            try {
                ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, relay.setup)).use {
                    visible("配对").click(); visible("● 已连接 · 端到端加密"); visible("查看 Agents").click(); visible("打开对话").click(); visible("对话已同步")
                    assertTrue(relay.enrolled); assertTrue(relay.failures.isEmpty())
                }
            } finally { RelayEngine.testClientFactory = null }
        }
    }
    @Test fun attachmentChunksAndReferencesMatchUpstreamProtocol() {
        MockEncryptedRelay().use { relay ->
            RelayEngine.testClientFactory = { relay.client }; Vault(context).write("state", relay.storedState())
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    visible("打开对话").click(); visible("对话已同步")
                    val bytes = "附件内容\n".repeat(20000).toByteArray()
                    val folder = File(context.cacheDir, "shared").apply { mkdirs() }; val file = File(folder, "test.txt").apply { writeBytes(bytes) }
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    scenario.onActivity { activity ->
                        val controller = MainActivity::class.java.getDeclaredField("nativeAgents").apply { isAccessible = true }.get(activity) as NativeAgents
                        controller.upload(listOf(uri))
                    }
                    visible("test.txt")
                    assertArrayEquals(bytes, relay.uploadedBytes.toByteArray())
                    val second = File(folder, "notes.md").apply { writeText("# 任务说明\n检查文件中的实现。") }
                    scenario.onActivity { activity ->
                        val controller = MainActivity::class.java.getDeclaredField("nativeAgents").apply { isAccessible = true }.get(activity) as NativeAgents
                        controller.upload(listOf(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", second)))
                    }
                    visible("notes.md")
                    val editor = device.findObject(By.clazz("android.widget.EditText"))
                    editor.text = "结合这两个附件，帮我检查项目中的问题。\n先列出你的修改计划。"
                    screenshot("composer-attachments")
                    // Removing one attachment must preserve the other attachment and the draft.
                    device.wait(Until.findObject(By.desc("移除附件 notes.md")), 5000).click()
                    assertTrue(device.wait(Until.gone(By.text("notes.md")), 5000)); visible("test.txt")
                    assertEquals("结合这两个附件，帮我检查项目中的问题。\n先列出你的修改计划。", editor.text)
                    scenario.onActivity { activity ->
                        val controller = MainActivity::class.java.getDeclaredField("nativeAgents").apply { isAccessible = true }.get(activity) as NativeAgents
                        val field = NativeAgents::class.java.getDeclaredField("composer").apply { isAccessible = true }.get(controller) as android.widget.EditText
                        field.requestFocus(); (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                    val keyboardShown = AtomicBoolean(false); val keyboardDeadline = System.currentTimeMillis() + 5000
                    while (!keyboardShown.get() && System.currentTimeMillis() < keyboardDeadline) {
                        scenario.onActivity { activity -> keyboardShown.set(androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) }
                        Thread.sleep(100)
                    }
                    assertTrue("The composer must support the real Android keyboard", keyboardShown.get())
                    val sendBounds = visible("发送").visibleBounds
                    scenario.onActivity { activity ->
                        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)!!
                        val keyboardTop = activity.window.decorView.height - insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                        assertTrue("Send button overlaps keyboard", sendBounds.bottom <= keyboardTop)
                    }
                    screenshot("composer-keyboard")
                    visible("发送").click(); val prompt = relay.expect("submit_prompt")
                    assertTrue(prompt.getString("text").contains("Attachment: " + "a".repeat(32)))
                    assertFalse(prompt.getString("text").contains("Attachment: " + "b".repeat(32)))
                    assertTrue(relay.failures.isEmpty())
                }
            } finally { RelayEngine.testClientFactory = null }
        }
    }
    @Test fun lostAcknowledgementRetainsDraftAndDoesNotReplayMutationAfterReconnect() {
        MockEncryptedRelay().use { relay ->
            RelayEngine.testClientFactory = { relay.client }; Vault(context).write("state", relay.storedState())
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    visible("打开对话").click(); visible("对话已同步")
                    device.findObject(By.clazz("android.widget.EditText")).text = "不能重复执行的请求"
                    relay.dropNextWrite = true; val connectionCount = relay.sessions.size
                    visible("发送").click(); relay.expect("submit_prompt")
                    val deadline = System.currentTimeMillis() + 15000
                    while (relay.sessions.size <= connectionCount && System.currentTimeMillis() < deadline) Thread.sleep(100)
                    assertTrue(relay.sessions.size > connectionCount)
                    assertEquals("不能重复执行的请求", device.findObject(By.clazz("android.widget.EditText")).text)
                    assertFalse(visible("发送").isEnabled)
                    visible("‹ Agents").click(); visible("打开对话").click()
                    assertEquals("不能重复执行的请求", device.findObject(By.clazz("android.widget.EditText")).text)
                    assertFalse(visible("发送").isEnabled)
                    assertEquals(1, relay.recorded.count { it.optString("type") == "submit_prompt" })
                }
                ActivityScenario.launch(MainActivity::class.java).use {
                    visible("打开对话").click()
                    assertEquals("不能重复执行的请求", device.findObject(By.clazz("android.widget.EditText")).text)
                    assertFalse(visible("发送").isEnabled)
                    assertEquals(1, relay.recorded.count { it.optString("type") == "submit_prompt" })
                }
            } finally { RelayEngine.testClientFactory = null }
        }
    }
}
