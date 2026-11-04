package dev.herdr.companion

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject

/** Polls metadata, not history/terminal contents; a single in-flight request keeps load bounded. */
class AgentMonitor(
    private val shared: SharedRelay,
    private val preferences: MonitorPreferences,
    private val completed: (String, JSONObject, JSONObject) -> Unit,
    private val health: (String) -> Unit,
    private val fatal: (String) -> Unit = {}
) {
    private data class Target(val relay: String, val agent: JSONObject, val key: String)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var generation = 0
    private var inFlight = false
    private var ledger = CompletionLedger(preferences.ledger())
    private val next = mutableMapOf<String, Long>()
    private val lastSuccess = mutableMapOf<String, Long>()
    private var persisted = ledger.encode()
    private var scheduledAt = Long.MAX_VALUE
    private val pump = Runnable { scheduledAt = Long.MAX_VALUE; tick() }
    private fun targets(): List<Target> = shared.agents.flatMap { (relay, rows) ->
        (0 until rows.length()).mapNotNull { i ->
            val agent = rows.optJSONObject(i) ?: return@mapNotNull null
            runCatching { Target(relay, agent, MonitorIdentity.key(relay, agent)) }.getOrNull()
        }
    }.distinctBy { it.key }
    fun start() {
        if (running) return
        running = true; generation++
        shared.attachMonitor(this) { event ->
            if (event.optString("type") in listOf("message", "status", "removed")) schedule(200)
        }
        schedule(0)
    }
    fun stop() {
        if (!running) return
        running = false; generation++; handler.removeCallbacks(pump); scheduledAt = Long.MAX_VALUE; shared.detachMonitor(this)
    }
    fun reset(key: String) {
        ledger.forget(key); persist(); next.remove(key); lastSuccess.remove(key); schedule(0)
    }
    private fun persist() {
        val encoded = ledger.encode()
        if (encoded != persisted) { preferences.saveLedger(encoded); persisted = encoded }
    }
    private fun schedule(delay: Long) {
        if (!running) return
        val at = SystemClock.elapsedRealtime() + delay
        if (at >= scheduledAt) return
        scheduledAt = at; handler.removeCallbacks(pump); handler.postDelayed(pump, delay)
    }
    private fun tick() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        val all = targets(); val keys = all.map { it.key }.toSet()
        next.keys.retainAll(keys); lastSuccess.keys.retainAll(keys)
        val audible = all.filterNot { preferences.muted(it.key) }
        val connected = shared.online.size
        val fresh = audible.count { shared.ready(it.relay) && lastSuccess[it.key]?.let { time -> now - time < 45000 } == true }
        health(when {
            connected == 0 && shared.fatalRelays.isNotEmpty() -> "设备凭据失效 · 请打开 App 检查配对"
            connected == 0 -> "等待加密连接重连 · 暂不能检测完成"
            all.isEmpty() -> "已连接 $connected 台电脑 · 等待 Agent"
            audible.isEmpty() -> "${all.size} 个 Agent 均已关闭完成提醒"
            fresh < audible.size -> "正在确认 $fresh/${audible.size} 个 Agent · ${all.size - audible.size} 个已静音"
            else -> "监控 ${audible.size} 个 Agent · ${all.size - audible.size} 个已静音 · 已加密连接"
        })
        if (inFlight) { schedule(1000); return }
        val target = audible.filter { PrimarySessionBinding.live(it.agent) && shared.ready(it.relay) && (next[it.key] ?: 0) <= now }.minByOrNull { next[it.key] ?: 0 }
        if (target == null) { schedule(1000); return }
        val capabilities = shared.configs[target.relay]?.optJSONArray("capabilities")
        val runtime = capabilities?.let { (0 until it.length()).any { i -> it.optString(i) == "agent_run_state_v1" } } == true
        val owner = generation; val connectionEpoch = shared.epoch(target.relay)
        next[target.key] = now + 15000
        val snapshot = JSONObject(target.agent.toString())
        val tuple = WorkspaceBinding.fromAgent(target.relay, snapshot)
        fun accept(data: JSONObject?, error: String?) {
            inFlight = false
            if (!running || generation != owner) return
            val current = targets().find { it.key == target.key }
            if (error == null && data != null && current != null && shared.ready(target.relay) && shared.epoch(target.relay) == connectionEpoch && WorkspaceBinding.fromAgent(target.relay, current.agent) == tuple && !preferences.muted(target.key)) {
                if (data.optString("status") in setOf("working", "blocked", "done", "idle", "completed", "finished", "interrupted", "failed", "stopped")) lastSuccess[target.key] = SystemClock.elapsedRealtime()
                // Persist BEFORE posting; a process restart must not replay a delivered completion.
                val alert = ledger.observe(target.key, data, System.currentTimeMillis())
                try { persist() } catch (_: Exception) { preferences.setError("监控记录无法保存，已停止以避免重复提醒"); stop(); fatal("监控记录保存失败，请重新开启"); return }
                if (alert) completed(target.relay, current.agent, data)
            }
            schedule(150)
        }
        if (snapshot.optString("agent") == "codex" && runtime) {
            inFlight = true
            shared.engine.requestMonitor(target.relay, AgentProtocol.command(target.relay, snapshot, "get_agent_run_state"), ::accept)
        } else {
            // Older relays/other agents use observed Herdr transitions; no turn completion is fabricated.
            accept(JSONObject().put("source", "herdr").put("status", snapshot.optString("status")), null)
        }
    }
}
