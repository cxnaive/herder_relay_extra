package dev.herdr.companion

import org.json.JSONObject
import java.util.Locale

/** The server supplies turn duration; phone wall-clock and message timestamps never do. */
class AgentRunPresentation {
    data class Display(val headline: String, val timer: String, val detail: String, val tone: String, val spinning: Boolean = false)
    private var sample: JSONObject? = null
    private var receivedAt = 0L
    private var stale = false
    private var failedRead = false
    fun clear() { sample = null; receivedAt = 0; stale = false; failedRead = false }
    fun failed() { failedRead = true }
    fun invalidate() { stale = true }
    fun accept(value: JSONObject, monotonicNow: Long) {
        require(value.optString("status").length <= 64 && value.optString("source") in listOf("codex_runtime", "herdr"))
        val elapsed = if (value.isNull("elapsed_ms")) null else value.optLong("elapsed_ms", -1)
        require(elapsed == null || elapsed in 0..MAX_DURATION)
        require(!value.optBoolean("ticking") || value.optString("status") in listOf("working", "blocked"))
        sample = JSONObject(value.toString()); receivedAt = monotonicNow; stale = false; failedRead = false
    }
    fun display(agent: JSONObject?, online: Boolean, inventoryReady: Boolean, expectsRuntime: Boolean, now: Long): Display {
        val value = sample
        val age = (now - receivedAt).coerceAtLeast(0)
        val elapsed = value?.takeIf { it.optString("timing") == "turn_elapsed" && !it.isNull("elapsed_ms") }?.optLong("elapsed_ms")
        val last = elapsed?.let { "上次确认 ${duration(it)}" }.orEmpty()
        if (!online) return Display("连接中断", last, "无法确认 Agent 是否仍在运行；重连后校准计时", "muted")
        if (!inventoryReady) return Display("正在同步状态", last, "等待电脑确认，不把历史加载当成 Agent 运行", "muted")
        if (agent == null) return Display("会话已结束或被替换", "", "请返回列表重新打开，旧计时已失效", "muted")
        if (value != null && (stale || age > FRESH_MS)) return Display("运行状态待确认", last, "等待刷新；不再继续推算运行时长", "muted")
        if (value == null && failedRead && expectsRuntime) return Display("运行状态暂不可用", "", "未取得可靠状态，稍后自动重试或点按刷新", "muted")
        if (value == null && expectsRuntime) return Display("正在确认运行状态", "", "正在读取当前会话，不读取整段历史", "muted")
        val status = value?.optString("status") ?: agent.optString("status")
        val attention = value?.optString("attention_kind") ?: agent.optString("attention_kind")
        val isRunning = status in listOf("working", "running", "busy")
        val isWaiting = status in listOf("blocked", "waiting", "needs_input")
        val headline = when {
            isRunning -> "运行中"
            isWaiting -> when (attention) { "approval" -> "等待审批"; "question", "chat" -> "等待输入"; else -> "需要处理" }
            status in listOf("done", "completed", "finished") -> "已完成"
            status == "interrupted" -> "已中断"
            status in listOf("failed", "error") -> "运行失败"
            status in listOf("stopped", "exited", "ended") -> "已结束"
            status in listOf("idle", "ready") -> "空闲"
            else -> "状态未知"
        }
        val hasTime = elapsed != null && value?.optString("source") == "codex_runtime"
        val shown = if (hasTime) elapsed!! + if (value!!.optBoolean("ticking")) age else 0 else null
        val timer = shown?.let { (if (isRunning || isWaiting) "本轮 " else "上轮 ") + duration(it) }.orEmpty()
        val detail = when {
            value?.optString("source") != "codex_runtime" -> "Herdr 状态 · 未提供可靠的本轮开始时间"
            isRunning || isWaiting -> if (hasTime) "本轮总用时，包含工具执行和等待时间" else "已确认当前状态，开始时间暂不可用"
            status in listOf("done", "completed", "finished") -> if (hasTime) "已停止计时 · 等待下一条指令" else "等待下一条指令 · 上轮用时不可用"
            status == "interrupted" || status in listOf("failed", "error") -> "本轮已停止；不会继续累计时长"
            status in listOf("idle", "ready") -> "当前没有正在执行的轮次"
            else -> "未取得可靠运行状态"
        }
        val tone = when { isWaiting -> "waiting"; isRunning -> "working"; status in listOf("done", "completed", "finished") -> "done"; status in listOf("failed", "error") -> "error"; else -> "muted" }
        return Display(headline, timer, detail, tone, isRunning)
    }
    companion object {
        const val FRESH_MS = 15000L
        private const val MAX_DURATION = 10L * 366 * 24 * 60 * 60 * 1000
        fun duration(milliseconds: Long): String {
            val seconds = milliseconds.coerceAtLeast(0) / 1000
            val hours = seconds / 3600; val minutes = seconds % 3600 / 60; val rest = seconds % 60
            return if (hours > 0) String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, rest)
                else String.format(Locale.ROOT, "%02d:%02d", minutes, rest)
        }
    }
}
