package dev.herdr.companion

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.util.Base64
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val background = Color.rgb(16, 27, 34)
    private val surface = Color.rgb(27, 42, 50)
    private val accent = Color.rgb(112, 225, 197)
    private val ink = Color.rgb(232, 240, 242)
    private val muted = Color.rgb(151, 173, 182)
    private lateinit var vault: Vault
    private lateinit var engine: RelayEngine
    private lateinit var sharedRelay: SharedRelay
    private var monitorRoute: JSONObject? = null
    private var monitorSettingsPending = false
    private val monitorPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) startMonitoring() else toast("未允许通知，常驻监控尚未开启。可在系统设置中允许后再试。")
    }
    private fun monitorDialog() = MonitorSettingsUi.show(this, ::enableMonitoring) { monitorControlsChanged() }
    private fun monitorControlsChanged() { if (tab == "devices") renderDevices() else nativeAgents.updateMonitorControls() }
    private fun enableMonitoring() {
        if (relays.length() == 0) { toast("请先配对至少一台电脑"); return }
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            monitorPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else startMonitoring()
    }
    private fun startMonitoring() {
        runCatching { AgentMonitorService.start(this) }.onSuccess { toast("已开启：默认关注全部 Agent；请留意电池后台限制") }.onFailure { toast(it.message ?: "监控未启动，请检查通知权限") }
        monitorControlsChanged()
    }
    private fun acceptMonitorIntent(value: Intent) {
        if (value.action == MonitorNotifications.OPEN_AGENT) {
            monitorRoute = runCatching {
                val raw = value.getStringExtra(MonitorNotifications.EXTRA_ROUTE).orEmpty(); require(raw.length <= 1024)
                JSONObject(raw).also { require(it.getString("relay").length in 1..256 && it.getString("key").matches(Regex("[a-f0-9]{64}"))) }
            }.getOrNull()
        }
        monitorSettingsPending = value.action == "dev.herdr.companion.MONITOR_SETTINGS"
    }
    private fun openMonitorRoute() {
        val route = monitorRoute ?: return
        val id = route.optString("relay")
        if (!sharedRelay.ready(id)) return
        val list = agents[id] ?: return
        val agent = (0 until list.length()).map { list.getJSONObject(it) }.find { MonitorIdentity.matchesRoute(id, it, route) }
        fun navigate() {
            monitorRoute = null; relayId = id; tab = "agents"; render()
            if (agent == null) toast("该通知对应的会话已结束或替换，请从列表选择") else nativeAgents.open(id, agent)
        }
        if (file != null && tab == "files") leaveFile(::navigate) else navigate()
    }
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var header: LinearLayout
    private var keyboardVisible = false
    private var agentDetailVisible = false
    private lateinit var heading: TextView
    private lateinit var subtitle: TextView
    private lateinit var stateDot: TextView
    private lateinit var nativeAgents: NativeAgents
    private var relays = JSONArray()
    private val statuses = mutableMapOf<String, String>()
    private val capabilities = mutableMapOf<String, Set<String>>()
    private val agents = mutableMapOf<String, JSONArray>()
    private var tab = "files"
    private var relayId = ""
    private var selectedAgent: JSONObject? = null
    private var entries = JSONArray()
    private var directory = ""
    private var treeRoot = ""
    private var treeTruncated = false
    private var query = ""
    private var searchField: EditText? = null
    private var fileList: ListView? = null
    private var file: JSONObject? = null
    private var originalText = ""
    private var draft = ""
    private var editing = false
    private var saving = false
    private var busy = false
    private var fileGeneration = 0
    private var pdf: PdfPreview? = null
    private var fileReviewer: DocumentReviewer? = null
    private var editor: EditText? = null
    private var fileBody: FrameLayout? = null
    private var editToggle: Button? = null
    private var saveButton: Button? = null
    private var editStatus: TextView? = null
    private var pendingExport: Pair<String, ByteArray>? = null
    private var sharedLink = ""
    private val main = Handler(Looper.getMainLooper())
    private var transientToast: Toast? = null
    private val persistDraft = Runnable { keepDraft() }
    private val picker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val export = pendingExport; pendingExport = null
        if (uri != null && export != null) {
            Thread {
                val result = runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(export.second) } }
                runOnUiThread { toast(if (result.isSuccess) "已保存到所选位置" else "保存失败，请检查存储空间") }
            }.start()
        }
    }
    private val attachmentPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> nativeAgents.upload(uris) }
    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            if (result.data?.getBooleanExtra(QrScannerActivity.EXTRA_MANUAL, false) == true) pairDialog()
            else result.data?.getStringExtra(QrScannerActivity.EXTRA_CONTENT)?.let { confirmPair(it) }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = background; window.navigationBarColor = background
        sharedRelay = SharedRelay.get(this)
        vault = sharedRelay.vault
        readRelays()
        relayId = state?.getString("relay") ?: relays.optJSONObject(0)?.optString("id").orEmpty()
        tab = state?.getString("tab") ?: if (relays.length() == 0) "devices" else "agents"
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(this@MainActivity.background) }
        header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(16)) }
        val titles = column()
        titles.addView(label("HERDR  /  POCKET", 11, accent).apply { letterSpacing = .17f })
        heading = label("工作文件", 27, ink, true)
        subtitle = label("把工作现场，装进口袋。", 12, muted)
        titles.addView(heading); titles.addView(subtitle)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        stateDot = label("●", 19, muted); header.addView(stateDot)
        root.addView(header)
        content = FrameLayout(this); root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        nav = LinearLayout(this).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(surface) }
        root.addView(nav)
        engine = sharedRelay.engine
        nativeAgents = NativeAgents(this, engine, vault, { agents }, ::selectWorkspace,
            { attachmentPicker.launch(arrayOf("*/*")) }, { detail -> agentDetailVisible = detail; updateChrome() }, { monitorDialog() })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime()); updateChrome()
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    file != null && tab == "files" -> leaveFile { render() }
                    tab == "files" && directory.isNotEmpty() -> { directory = FileRules.parent(directory); query = ""; render() }
                    tab == "agents" && nativeAgents.back() -> Unit
                    tab == "files" && selectedAgent != null -> returnToSelectedAgent()
                    tab != "agents" -> changeTab("agents")
                    else -> moveTaskToBack(true)
                }
            }
        })
        File(cacheDir, "shared").listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86400000 }?.forEach { it.deleteRecursively() }
        sharedLink = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        acceptMonitorIntent(intent)
        render()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); intent.getStringExtra(Intent.EXTRA_TEXT)?.let { confirmPair(it) }; acceptMonitorIntent(intent); openMonitorRoute(); if (monitorSettingsPending) { monitorSettingsPending = false; monitorDialog() } }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("relay", relayId); outState.putString("tab", tab); super.onSaveInstanceState(outState) }
    override fun onStart() {
        super.onStart()
        if (::sharedRelay.isInitialized) sharedRelay.attachUI(this, ::onEvent)
        if (::nativeAgents.isInitialized && tab == "agents") nativeAgents.attach(content)
        if (MonitorPreferences(this).enabled && !AgentMonitorService.active && MonitorNotifications.allowed(this)) runCatching { AgentMonitorService.start(this) }
        openMonitorRoute()
        if (monitorSettingsPending) { monitorSettingsPending = false; monitorDialog() }
        if (sharedLink.isNotBlank() && engine.ready) { val link = sharedLink; sharedLink = ""; confirmPair(link) }
        monitorControlsChanged()
    }
    override fun onStop() { keepDraft(); nativeAgents.pause(); sharedRelay.detachUI(this); super.onStop() }
    override fun onDestroy() { transientToast?.cancel(); main.removeCallbacks(persistDraft); pdf?.close(); fileReviewer?.close(); nativeAgents.destroy(); sharedRelay.detachUI(this); super.onDestroy() }

    private fun readRelays() { relays = runCatching { JSONObject(vault.read("state") ?: "{}").optJSONArray("relays") ?: JSONArray() }.getOrElse { JSONArray() } }
    private fun updateChrome() {
        val hide = keyboardVisible || agentDetailVisible
        header.visibility = if (hide) View.GONE else View.VISIBLE
        nav.visibility = if (hide) View.GONE else View.VISIBLE
    }
    private fun onEvent(event: JSONObject) {
        when (event.optString("type")) {
            "monitor_state" -> monitorControlsChanged()
            "ready" -> if (sharedLink.isNotBlank()) { confirmPair(sharedLink); sharedLink = "" }
            "persist" -> { readRelays(); if (relayId.isEmpty()) relayId = relays.optJSONObject(0)?.optString("id").orEmpty() }
            "paired" -> { relayId = event.getJSONObject("relay").getString("id"); render(); toast("已添加，正在进行加密配对…") }
            "enrolled" -> { toast("设备配对成功"); if (tab == "devices") render() }
            "removed" -> { readRelays(); relayId = relays.optJSONObject(0)?.optString("id").orEmpty(); selectedAgent = null; render() }
            "status" -> {
                val id = event.getString("relayId"); statuses[id] = event.getString("status"); updateHeader()
                if (tab == "devices") render()
                if (event.optJSONObject("detail")?.optBoolean("fatal") == true) toast("配对凭据已失效或连接被拒绝，请检查设备邀请")
            }
            "message" -> {
                val id = event.getString("relayId"); val message = event.getJSONObject("message")
                when (message.optString("type")) {
                    "push_config" -> {
                        val list = message.optJSONArray("capabilities") ?: JSONArray()
                        capabilities[id] = (0 until list.length()).map { list.getString(it) }.toSet()
                    }
                    "blocked", "agent_update" -> {
                        val list = agents[id] ?: JSONArray()
                        val pane = message.optString("pane_id")
                        val index = (0 until list.length()).firstOrNull { list.getJSONObject(it).optString("pane_id") == pane }
                        val updated = if (index == null) JSONObject() else JSONObject(list.getJSONObject(index).toString())
                        message.keys().forEach { key -> if (key != "type") updated.put(key, message.get(key)) }
                        if (message.optString("type") == "blocked") updated.put("status", "blocked")
                        if (index == null) list.put(updated) else list.put(index, updated)
                        agents[id] = list
                    }
                    "agent_removed" -> {
                        val list = agents[id] ?: JSONArray()
                        agents[id] = JSONArray((0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("pane_id") != message.optString("pane_id") })
                    }
                    "agents" -> {
                        agents[id] = message.optJSONArray("agents") ?: JSONArray()
                        if (id == relayId && file == null && selectedAgent != null) {
                            val current = agents[id]!!
                            val replacement = (0 until current.length()).map { current.getJSONObject(it) }.find { it.optString("pane_id") == selectedAgent?.optString("pane_id") }
                            if (replacement == null || WorkspaceBinding.fromAgent(id, replacement) != selectedBinding()) {
                                fileReviewer?.close(); fileReviewer = null
                                selectedAgent = null; entries = JSONArray(); busy = false; fileGeneration++
                            }
                            else selectedAgent = replacement
                        }
                        if (file != null) updateEditorState()
                        if (tab == "files" && selectedAgent == null && file == null) render()
                    }
                }
            }
            "failure" -> toast(event.optString("message", "操作失败"))
        }
        if (::nativeAgents.isInitialized) nativeAgents.onEvent(event)
        if (::sharedRelay.isInitialized && event.optString("type") == "message" && event.optJSONObject("message")?.optString("type") == "agents") openMonitorRoute()
    }
    private fun updateHeader() {
        heading.text = when (tab) { "agents" -> "Agent 工作台"; "devices" -> "我的设备"; else -> if (file != null) FileRules.safeName(file!!.optString("path")) else "Agent 工作区" }
        heading.maxLines = 1; heading.ellipsize = android.text.TextUtils.TruncateAt.END
        val connected = statuses[relayId] == "connected"
        stateDot.setTextColor(if (connected) accent else muted)
        subtitle.text = if (relayId.isEmpty()) "从连接第一台电脑开始" else "${relayLabel()}  ·  ${if (connected) "已加密连接" else "等待连接"}"
    }
    private fun relayLabel(): String = (0 until relays.length()).map { relays.getJSONObject(it) }.find { it.optString("id") == relayId }?.optString("label") ?: "设备"
    private fun changeTab(next: String) {
        if (saving) { toast("正在保存，请稍候"); return }
        if (tab == "files" && file != null && next != tab) { leaveFile { tab = next; render() }; return }
        tab = next; render()
    }
    private fun render() {
        updateHeader(); nav.removeAllViews()
        listOf("agents" to "◎  Agents", "files" to "▤  工作区", "devices" to "◉  设备").forEach { (id, title) ->
            nav.addView(button(title, id == tab) { changeTab(id) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        if (tab != "agents") nativeAgents.pause()
        content.removeAllViews()
        when (tab) { "devices" -> renderDevices(); "agents" -> renderAgents(); else -> if (file == null) renderFiles() else renderFile() }
    }
    private fun renderDevices() {
        val body = scrollColumn()
        body.addView(label("连接你的工作现场", 21, ink, true))
        body.addView(label("扫描电脑端的设备邀请，或粘贴完整配对链接。每台手机有独立凭据。", 14, muted).space(0, 8, 0, 20))
        val actions = LinearLayout(this)
        actions.addView(button("扫描二维码", true) {
            scanner.launch(Intent(this, QrScannerActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(button("粘贴链接") { pairDialog() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        body.addView(actions)
        body.addView(button(if (AgentMonitorService.active) "常驻监控：运行中 · 通知设置" else "开启常驻监控 / 完成提醒") { monitorDialog() }.space(0, 16, 0, 4))
        body.addView(label("默认关注全部 Agent；单个 Agent 的完成提醒可在其 ⋯ 菜单关闭。", 12, muted))
        body.addView(label("已添加设备  /  ${relays.length()}", 12, muted).space(0, 28, 0, 12))
        for (i in 0 until relays.length()) {
            val relay = relays.getJSONObject(i); val id = relay.getString("id")
            val card = card()
            card.addView(label(relay.optString("label", "我的电脑"), 18, ink, true))
            card.addView(label(when (statuses[id]) { "connected" -> "● 已连接 · 端到端加密"; "connecting" -> "正在连接…"; else -> "未连接 · 可重新连接或重新配对" }, 12, if (statuses[id] == "connected") accent else muted).space(0, 5, 0, 12))
            val row = LinearLayout(this)
            row.addView(button("查看 Agents", true) { relayId = id; selectedAgent = null; tab = "agents"; render() }, LinearLayout.LayoutParams(0, dp(46), 1f))
            row.addView(button("重连") { engine.call("reconnect") }, LinearLayout.LayoutParams(0, dp(46), 1f))
            row.addView(button("移除") {
                AlertDialog.Builder(this).setTitle("从本机移除设备？").setMessage("这会清除本机的连接凭据。电脑端的设备授权可在 Agent 工作台的设置中撤销。")
                    .setPositiveButton("移除") { _, _ -> engine.call("remove", id) }.setNegativeButton("取消", null).show()
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
            card.addView(row); body.addView(card.space(0, 0, 0, 12))
        }
        body.addView(label("HERDR POCKET  ${BuildConfig.VERSION_NAME}", 12, accent).space(0, 28, 0, 8))
        body.addView(label("原生 Agent 对话、终端、审批与文件工具\n基于 Herdr Mobile Relay 0.24.0 · AGPL-3.0-or-later\n文件下载与 PDF：单个文件最大 12 MiB；文本编辑：UTF-8，最大 1 MiB。", 12, muted))
        body.addView(button("查看开源许可证") { AlertDialog.Builder(this).setTitle("GNU AGPL v3").setMessage(assets.open("LICENSE").bufferedReader().readText()).setPositiveButton("关闭", null).show() }.space(0, 14, 0, 0))
    }
    private fun pairDialog() {
        val input = EditText(this).apply { hint = "herdr-tcp://IP:端口/#invite=… 或 HTTPS 链接"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; minLines = 3; maxLines = 6 }
        AlertDialog.Builder(this).setTitle("添加电脑").setView(input).setPositiveButton("检查链接") { _, _ -> confirmPair(input.text.toString()) }.setNegativeButton("取消", null).show()
    }
    private fun confirmPair(value: String) {
        if (!engine.ready) { sharedLink = value; toast("连接引擎正在启动"); return }
        val uri = runCatching { Uri.parse(value.trim()) }.getOrNull()
        if (uri?.scheme !in listOf("https", "herdr-tcp") || uri?.host.isNullOrBlank()) { toast("请输入完整的 HTTPS 或 herdr-tcp 配对链接"); return }
        val params = Uri.parse("https://invitation.invalid/?" + uri.encodedFragment.orEmpty())
        val destination = (params.getQueryParameter("relay") ?: params.getQueryParameter("gateways"))
            ?.split(',')?.mapNotNull { runCatching { Uri.parse(it).host }.getOrNull() }?.joinToString("、") ?: uri.host
        // Never display or log invitation fragments: they contain the one-use secret.
        AlertDialog.Builder(this).setTitle("连接这台电脑？").setMessage("连接地址：$destination" + (if (uri.scheme == "herdr-tcp") "\n原生 TCP · 应用层端到端加密（无 HTTPS）" else "") + "\n请确认二维码或链接来自你信任的电脑。")
            .setPositiveButton("配对") { _, _ -> engine.call("pair", value.trim()) }.setNegativeButton("取消", null).show()
    }
    private fun renderAgents() {
        if (relays.length() == 0) { emptyState("还没有连接电脑", "先添加设备，即可查看原生对话、终端和审批。", "添加设备") { changeTab("devices") }; return }
        nativeAgents.attach(content)
    }
    private fun agentName(agent: JSONObject): String = agent.optString("name").ifBlank { agent.optString("agent", "Agent") }
    private fun selectWorkspace(relay: String, agent: JSONObject) {
        relayId = relay; selectedAgent = JSONObject(agent.toString()); directory = ""; query = ""; treeRoot = ""; entries = JSONArray(); busy = false
        tab = "files"; fileGeneration++; loadTree()
    }
    private fun selectedBinding(): WorkspaceBinding? = selectedAgent?.let { WorkspaceBinding.fromAgent(relayId, it) }
    private fun returnToSelectedAgent() {
        selectedAgent?.let { nativeAgents.open(relayId, it) }
        tab = "agents"; render()
    }
    private fun selectedAgentIsCurrent(): Boolean {
        val selected = selectedBinding() ?: return false
        val list = agents[relayId] ?: return false
        return (0 until list.length()).any { WorkspaceBinding.fromAgent(relayId, list.getJSONObject(it)) == selected }
    }

    private fun renderFiles() {
        if (relays.length() == 0) { emptyState("工作文件，随身可用", "浏览电脑项目、修改文本、阅读 Markdown 和 PDF，再通过安卓分享给其他应用。", "连接第一台电脑") { changeTab("devices") }; return }
        val body = column().apply { setPadding(dp(20), 0, dp(20), 0) }; content.addView(body)
        val top = LinearLayout(this)
        top.addView(button("${relayLabel()}  ▾") { chooseRelay() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        top.addView(button("刷新") { if (selectedAgent == null) engine.call("reconnect") else loadTree() })
        body.addView(top)
        if (selectedAgent == null) {
            body.addView(label("选择 Agent 的工作区", 20, ink, true).space(0, 24, 0, 8))
            body.addView(label("也可以在 Agent 的对话或终端页面，直接打开它的工作区。", 13, muted).space(0, 0, 0, 16))
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
            val all = agents[relayId] ?: JSONArray()
            if (all.length() == 0) list.addView(label("${if (statuses[relayId] == "connected") "暂无 Agent 工作目录，请先在电脑端打开一个项目。" else "连接后会显示工作目录。"}", 14, muted).space(0, 20, 0, 0))
            for (i in 0 until all.length()) {
                val agent = all.getJSONObject(i)
                if (agent.optString("cwd").isBlank()) continue
                val card = card()
                card.addView(label(agentName(agent), 17, ink, true))
                card.addView(label("${agent.optString("agent")} · ${agent.optString("project")} · ${agent.optString("pane_id")}", 12, muted))
                card.addView(label(agent.optString("cwd"), 12, muted).space(0, 5, 0, 8))
                card.addView(label("打开此 Agent 的工作区 →", 13, accent))
                card.setOnClickListener { selectWorkspace(relayId, agent) }
                list.addView(card.space(0, 0, 0, 12))
            }
            val drafts = vault.names("draft:")
            if (drafts.isNotEmpty()) body.addView(button("恢复本机草稿（${drafts.size}）", true) { chooseDraft() }.space(0, 12, 0, 12))
            return
        }
        val crumb = LinearLayout(this)
        crumb.addView(button("‹  ${if (directory.isEmpty()) "返回 Agent" else "上一级"}") {
            if (directory.isEmpty()) returnToSelectedAgent() else { directory = FileRules.parent(directory); query = ""; render() }
        })
        body.addView(crumb)
        body.addView(label("${agentName(selectedAgent!!)} 的工作区", 18, ink, true).space(0, 8, 0, 4))
        body.addView(label(if (directory.isEmpty()) treeRoot.ifBlank { selectedAgent!!.optString("cwd") } else directory, 12, muted).apply { maxLines = 2 }.space(0, 4, 0, 10))
        searchField = EditText(this).apply {
            hint = "搜索此 Agent 的工作区…"; setTextColor(ink); setHintTextColor(muted); textSize = 14f; setSingleLine(); setText(query)
            background = rounded(surface); setPadding(dp(14), dp(8), dp(14), dp(8))
            addTextChangedListener(watcher { query = it; renderEntries() })
        }
        body.addView(searchField, LinearLayout.LayoutParams(-1, dp(48)))
        if (treeTruncated) body.addView(label("目录较大，仅显示前 4,000 项。生成目录与符号链接已过滤。", 12, muted).space(0, 8, 0, 8))
        if (capabilities[relayId]?.contains("workspace_mobile_files_v1") != true) body.addView(label("当前 relay 支持只读预览；安装随附扩展后可编辑、下载和查看 PDF。", 12, muted).space(0, 8, 0, 8))
        fileList = ListView(this).apply { divider = android.graphics.drawable.ColorDrawable(surface); dividerHeight = dp(1) }
        body.addView(fileList, LinearLayout.LayoutParams(-1, 0, 1f))
        renderEntries()
    }
    private fun renderEntries() {
        val list = fileList ?: return
        if (busy) { list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("正在读取…")); return }
        val shown = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { if (query.isBlank()) FileRules.parent(it.getString("path")) == directory else it.getString("path").contains(query, true) }
            .sortedWith(compareBy<JSONObject> { it.optString("kind") != "directory" }.thenBy { it.optString("name").lowercase() })
        if (shown.isEmpty()) { list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf(if (query.isBlank()) "这个目录是空的" else "没有匹配的文件")); return }
        list.adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = shown[position]
            val path = entry.getString("path"); val folder = entry.optString("kind") == "directory"
            val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(16), 0, dp(16)); minimumHeight = dp(66) }
            val type = if (folder) "DIR" else path.substringAfterLast('.', "TXT").uppercase().take(4)
            row.addView(label(type, 10, if (folder) accent else muted, true).apply { gravity = Gravity.CENTER; background = rounded(surface) }, LinearLayout.LayoutParams(dp(45), dp(42)))
            val texts = column().apply { setPadding(dp(14), 0, dp(8), 0) }
            texts.addView(label(entry.optString("name", FileRules.safeName(path)), 15, ink).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            texts.addView(label(if (query.isNotBlank()) path else if (folder) "文件夹" else FileRules.size(entry.optLong("size")), 11, muted).space(0, 3, 0, 0))
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(label("›", 22, muted))
            row.setOnClickListener { if (folder) { directory = path; query = ""; render() } else openFile(path) }
            return row
            }
        }
    }
    private fun chooseRelay() {
        AlertDialog.Builder(this).setTitle("选择电脑").setItems((0 until relays.length()).map { relays.getJSONObject(it).optString("label") }.toTypedArray()) { _, i ->
            relayId = relays.getJSONObject(i).getString("id"); selectedAgent = null; query = ""; render()
        }.show()
    }
    private fun command(type: String) = JSONObject().put("type", type).apply {
        selectedAgent?.let { a ->
            put("pane_id", a.optString("pane_id")); put("cwd", a.optString("cwd"))
            put("target", JSONObject().put("server_session_id", a.optString("server_session_id").ifBlank { "primary" })
                .put("pane_id", a.optString("pane_id")).put("terminal_id", a.optString("terminal_id"))
                .put("generation", a.optLong("generation")).put("agent_session_id", a.optString("agent_session_id").ifBlank { a.optString("session_id") }))
        }
    }
    private fun loadTree() {
        if (!selectedAgentIsCurrent()) { toast("Agent 会话已变化，请重新选择它的工作区"); render(); return }
        busy = true; val binding = selectedBinding(); val generation = fileGeneration; render()
        engine.request(relayId, command("workspace_tree")) { data, error ->
            if (selectedBinding() != binding || fileGeneration != generation) return@request
            busy = false
            if (error != null) toast(error) else { entries = data?.optJSONArray("entries") ?: JSONArray(); treeRoot = data?.optString("root").orEmpty(); treeTruncated = data?.optBoolean("truncated") == true }
            if (tab == "files" && file == null) render()
        }
    }
    private fun openFile(path: String) {
        if (busy) return
        if (!selectedAgentIsCurrent()) { toast("Agent 会话已变化，请重新打开它的工作区"); return }
        val extended = capabilities[relayId]?.any { it in setOf("workspace_mobile_files_v1", "workspace_file_read_v1") } == true
        busy = true; val binding = selectedBinding(); val generation = fileGeneration; renderEntries()
        val receive: (JSONObject?, String?) -> Unit = receive@ { data, error ->
            if (selectedBinding() != binding || fileGeneration != generation) return@receive
            busy = false
            if (error != null || data == null) { toast(error ?: "文件内容为空"); renderEntries(); return@receive }
            if (!extended) {
                val raw = if (data.optString("kind") == "image") data.optString("data_url").substringAfter(',') else Base64.encodeToString(data.optString("text").toByteArray(), Base64.NO_WRAP)
                data.put("data", raw)
            }
            file = data; fileGeneration++; originalText = if (data.optString("kind") == "text") bytes().toString(Charsets.UTF_8) else ""
            draft = originalText; editing = false; render()
        }
        if (capabilities[relayId]?.contains("workspace_file_read_v1") == true) {
            DocumentTransfer.read(path, { selectedBinding() == binding && fileGeneration == generation },
                { payload, callback -> engine.request(relayId, command("workspace_file_read").put("path", path).apply { payload.keys().forEach { put(it, payload.get(it)) } }, 30000, callback) }, receive)
        } else engine.request(relayId, command(if (extended) "workspace_file_read" else "workspace_file").put("path", path), callback = receive)
    }
    private fun openFileReviewer(path: String) {
        val binding = selectedBinding() ?: return
        if (!selectedAgentIsCurrent()) return
        fileReviewer?.close()
        fileReviewer = DocumentReviewer(this, binding.cwd, { selectedBinding() == binding && selectedAgentIsCurrent() }) { requested, done ->
            val extended = capabilities[relayId]?.any { it in setOf("workspace_mobile_files_v1", "workspace_file_read_v1") } == true
            if (capabilities[relayId]?.contains("workspace_file_read_v1") == true) {
                val view = fileReviewer; val version = view?.requestVersion
                DocumentTransfer.read(requested, { fileReviewer === view && view?.isOpen == true && view.requestVersion == version && selectedBinding() == binding && selectedAgentIsCurrent() },
                    { payload, callback -> engine.request(relayId, command("workspace_file_read").apply { payload.keys().forEach { put(it, payload.get(it)) } }, 30000, callback) }, done)
            } else engine.request(relayId, command(if (extended) "workspace_file_read" else "workspace_file").put("path", requested), 30000) { data, error ->
                if (selectedBinding() == binding && selectedAgentIsCurrent()) done(data, error)
            }
        }
        fileReviewer?.open(ReviewReference(path))
    }
    private fun renderFile() {
        val doc = file ?: return
        val body = column().apply { setPadding(dp(18), 0, dp(18), dp(8)) }; content.addView(body)
        body.addView(button("‹  返回文件列表") { leaveFile { render() } })
        selectedAgent?.let { body.addView(label("${agentName(it)} · ${it.optString("cwd")}", 12, accent).apply { maxLines = 2 }) }
        body.addView(label("${doc.optString("path")}  ·  ${FileRules.size(doc.optLong("size"))}", 12, muted).space(0, 5, 0, 12))
        val actions = LinearLayout(this)
        if (doc.optString("kind") == "text") {
            editToggle = button(if (editing) "预览" else "编辑", true) { editing = !editing; showFileBody(); updateEditorState() }
            editToggle!!.isEnabled = canEdit()
            actions.addView(editToggle, LinearLayout.LayoutParams(0, dp(46), 1f))
            saveButton = button("保存") { saveFile() }
            actions.addView(saveButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        if (doc.optString("kind") in listOf("pdf", "image") || FileRules.isMarkdown(doc.optString("path"))) {
            body.addView(button("全屏查看 · 缩放 / 阅读") { openFileReviewer(doc.optString("path")) })
        }
        actions.addView(button("下载") { val docNow = file ?: return@button; pendingExport = FileRules.safeName(docNow.optString("path")) to currentBytes(); picker.launch(pendingExport!!.first) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        actions.addView(button("分享") { shareFile() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        body.addView(actions)
        editStatus = label("", 12, muted); body.addView(editStatus!!.space(0, 8, 0, 8))
        fileBody = FrameLayout(this); body.addView(fileBody, LinearLayout.LayoutParams(-1, 0, 1f))
        showFileBody(); updateEditorState()
    }
    private fun showFileBody() {
        pdf?.close(); pdf = null; val holder = fileBody ?: return; holder.removeAllViews(); editor = null
        val doc = file ?: return
        when (doc.optString("kind")) {
            "text" -> if (editing) {
                editor = EditText(this).apply {
                    setTextColor(ink); setBackgroundColor(surface); textSize = 14f; typeface = Typeface.MONOSPACE
                    gravity = Gravity.TOP or Gravity.START; setPadding(dp(12), dp(12), dp(12), dp(12))
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setHorizontallyScrolling(false); setText(draft); isEnabled = !saving
                    addTextChangedListener(watcher { draft = it; updateEditorState(); main.removeCallbacks(persistDraft); main.postDelayed(persistDraft, 500) })
                }; holder.addView(editor, FrameLayout.LayoutParams(-1, -1))
            } else {
                val text = label(draft, 15, ink).apply { setLineSpacing(0f, 1f); setTextIsSelectable(true); setPadding(dp(12), dp(12), dp(12), dp(24)) }
                if (FileRules.isMarkdown(doc.optString("path"))) Markwon.builder(this).usePlugin(TablePlugin.create(this)).usePlugin(StrikethroughPlugin.create()).build().setMarkdown(text, draft)
                else text.typeface = Typeface.MONOSPACE
                holder.addView(ScrollView(this).apply { addView(text) })
            }
            "pdf" -> { pdf = PdfPreview(this, bytes()); holder.addView(pdf, FrameLayout.LayoutParams(-1, -1)) }
            "image" -> {
                val imageBytes = bytes(); val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
                options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / 2048).coerceAtLeast(1); options.inJustDecodeBounds = false
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
                holder.addView(ImageView(this).apply { adjustViewBounds = true; setImageBitmap(bitmap); contentDescription = doc.optString("path") }, FrameLayout.LayoutParams(-1, -1))
            }
            else -> holder.addView(label("此文件暂不支持预览。\n可以下载到手机，或分享给其他应用打开。", 15, muted).space(10, 24, 10, 0))
        }
    }
    private fun canEdit(): Boolean {
        val state = runCatching { JSONObject(vault.read("state") ?: "{}") }.getOrNull()
        val role = state?.optJSONObject("auth")?.optJSONObject("relays")?.optJSONObject(relayId)?.optString("role")
        return role == "controller" && selectedAgentIsCurrent() && file?.optString("revision").orEmpty().isNotBlank() && file?.optString("kind") == "text"
    }
    private fun updateEditorState() {
        saveButton?.isEnabled = canEdit() && draft != originalText && !saving && draft.toByteArray().size <= 1024 * 1024
        saveButton?.text = if (saving) "保存中…" else "保存"
        editToggle?.text = if (editing) "预览" else "编辑"
        editStatus?.text = when {
            saving -> "正在保存到电脑…"
            !selectedAgentIsCurrent() -> "Agent 会话已变化或尚未同步 · 草稿保留，请重新打开工作区后核对"
            file?.optString("kind") != "text" -> "下载和分享通过安卓系统完成"
            !canEdit() -> "只读预览 · 编辑需要控制者权限和 relay 文件扩展"
            draft.toByteArray().size > 1024 * 1024 -> "文本超过 1 MiB，无法保存到 relay"
            draft != originalText -> "未保存到电脑 · 草稿自动保留在本机"
            else -> "与电脑文件一致"
        }
    }
    private fun saveFile() {
        if (!canEdit() || saving) return
        val doc = file ?: return; val generation = fileGeneration; val toSave = draft
        saving = true; editor?.isEnabled = false; updateEditorState(); keepDraft()
        engine.request(relayId, command("workspace_file_write").put("path", doc.optString("path")).put("text", toSave).put("expected_revision", doc.optString("revision"))) { data, error ->
            if (generation != fileGeneration) return@request
            saving = false; editor?.isEnabled = true
            if (error != null || data == null) {
                keepDraft(); AlertDialog.Builder(this).setTitle("尚未确认保存").setMessage(error ?: "没有收到保存结果。草稿仍保留在本机。")
                    .setPositiveButton("保留草稿", null).setNeutralButton("分享草稿") { _, _ -> shareFile() }.show()
            } else { file = data; originalText = toSave; draft = toSave; vault.remove(draftKey()); toast("已保存到电脑") }
            updateEditorState()
        }
    }
    private fun leaveFile(then: () -> Unit) {
        if (saving) { toast("正在保存，请稍候"); return }
        val leave = { fileReviewer?.close(); fileReviewer = null; pdf?.close(); pdf = null; editor = null; file = null; fileGeneration++; then() }
        if (draft != originalText && file?.optString("kind") == "text") AlertDialog.Builder(this).setTitle("保留未保存的修改？").setMessage("可保留本机草稿，稍后从工作目录页面恢复。")
            .setPositiveButton("保留并返回") { _, _ -> keepDraft(); leave() }.setNegativeButton("继续编辑", null)
            .setNeutralButton("放弃修改") { _, _ -> vault.remove(draftKey()); leave() }.show()
        else leave()
    }
    private fun keepDraft() {
        if (!::vault.isInitialized || file?.optString("kind") != "text") return
        runCatching {
            if (draft == originalText) vault.remove(draftKey())
            else vault.write(draftKey(), JSONObject().put("file", file).put("text", draft).put("relayId", relayId).put("agent", selectedAgent).toString())
        }
            .onFailure { toast("草稿保存失败，请先分享或复制内容") }
    }
    private fun draftKey(): String {
        val identity = "$relayId\u0000${selectedAgent?.optString("cwd")}\u0000${file?.optString("path")}"
        return "draft:" + java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun chooseDraft() {
        val values = vault.names("draft:").mapNotNull { runCatching { vault.read(it) }.getOrNull() }
        val labels = values.map { runCatching { JSONObject(it).getJSONObject("file").getString("path") }.getOrDefault("草稿") }
        AlertDialog.Builder(this).setTitle("恢复未保存的修改").setItems(labels.toTypedArray()) { _, i -> restoreDraft(values[i]) }.setNegativeButton("取消", null).show()
    }
    private fun restoreDraft(value: String) {
        runCatching {
            val saved = JSONObject(value); relayId = saved.getString("relayId"); selectedAgent = saved.getJSONObject("agent"); file = saved.getJSONObject("file")
            originalText = bytes().toString(Charsets.UTF_8); draft = saved.getString("text"); editing = true; fileGeneration++; render()
        }.onFailure { toast("草稿无法恢复") }
    }
    private fun bytes() = Base64.decode(file?.optString("data").orEmpty(), Base64.DEFAULT)
    private fun currentBytes() = if (file?.optString("kind") == "text") draft.toByteArray(Charsets.UTF_8) else bytes()
    private fun shareFile() {
        val doc = file ?: return
        runCatching {
            val folder = File(cacheDir, "shared/${UUID.randomUUID()}").apply { mkdirs() }
            val output = File(folder, FileRules.safeName(doc.optString("path"))).apply { writeBytes(currentBytes()) }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", output)
            val send = Intent(Intent.ACTION_SEND).apply { type = doc.optString("media_type").ifBlank { "application/octet-stream" }; putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri(output.name, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startActivity(Intent.createChooser(send, "分享 ${output.name}"))
        }.onFailure { toast("暂时无法分享，请检查存储空间或先下载文件") }
    }
    private fun emptyState(title: String, detail: String, action: String, run: () -> Unit) {
        val body = scrollColumn(); body.addView(label("▤", 64, accent).space(0, 52, 0, 26)); body.addView(label(title, 23, ink, true))
        body.addView(label(detail, 15, muted).space(0, 14, 0, 28)); body.addView(button(action, true, run))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD); setLineSpacing(dp(3).toFloat(), 1f) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun scrollColumn(): LinearLayout {
        val body = column().apply { setPadding(dp(22), dp(10), dp(22), dp(28)) }
        content.addView(ScrollView(this).apply { isFillViewport = true; addView(body) })
        return body
    }
    private fun card() = column().apply { setPadding(dp(17), dp(17), dp(17), dp(17)); background = rounded(surface) }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }
    private fun button(title: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 13f; setTextColor(if (primary) this@MainActivity.background else ink)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) accent else surface)
        minimumHeight = dp(46); setPadding(dp(10), 0, dp(10), 0); setOnClickListener { action() }
    }
    private fun <T : View> T.space(left: Int, top: Int, right: Int, bottom: Int): T = apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(left), dp(top), dp(right), dp(bottom)) } }
    private fun watcher(action: (String) -> Unit) = object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}; override fun afterTextChanged(s: Editable?) { action(s.toString()) } }
    private fun toast(value: String) { transientToast?.cancel(); transientToast = Toast.makeText(this, value, Toast.LENGTH_LONG).also { it.show() } }
}
