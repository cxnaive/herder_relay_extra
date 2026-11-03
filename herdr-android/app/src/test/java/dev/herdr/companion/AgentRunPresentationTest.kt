package dev.herdr.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentRunPresentationTest {
    private val agent = JSONObject().put("status", "working").put("agent", "codex").put("updated_at", 9000000000000L)
    private fun show(p: AgentRunPresentation, at: Long = 1000, online: Boolean = true, ready: Boolean = true, exact: JSONObject? = agent) = p.display(exact, online, ready, true, at)
    @Test fun openingExistingTurnRestoresRealElapsedAndUsesMonotonicSeconds() {
        val p = AgentRunPresentation(); p.accept(sample("working", 123000, true), 1000)
        assertEquals("本轮 02:03", show(p).timer); assertEquals("本轮 02:05", show(p, 3500).timer)
        assertEquals("运行中", show(p).headline); assertTrue(show(p).spinning)
        agent.put("updated_at", 1).put("name", "new-label")
        assertEquals("本轮 02:05", show(p, 3500).timer)
    }
    @Test fun completionStopsTimerAndNewTurnResetsToServerValue() {
        val p = AgentRunPresentation(); p.accept(sample("done", 3723456, false), 1000)
        assertEquals("上轮 01:02:03", show(p).timer); assertEquals(show(p).timer, show(p, 10000).timer); assertFalse(show(p).spinning)
        p.accept(sample("working", 500, true).put("turn_id", "new-turn"), 10000)
        assertEquals("本轮 00:01", show(p, 11000).timer)
    }
    @Test fun approvalAndInputStayDistinctAndIncludeWholeTurnElapsed() {
        val p = AgentRunPresentation()
        p.accept(sample("blocked", 180000, true).put("attention_kind", "approval"), 1000)
        assertEquals("等待审批", show(p).headline); assertFalse(show(p).spinning); assertEquals("本轮 03:02", show(p, 3000).timer)
        p.accept(sample("blocked", 190000, true).put("attention_kind", "question"), 3000)
        assertEquals("等待输入", show(p, 3000).headline)
    }
    @Test fun disconnectionStaleAndReplacedTargetsCannotLookLive() {
        val p = AgentRunPresentation(); p.accept(sample("working", 42000, true), 1000)
        val offline = show(p, 9000, online = false); assertEquals("连接中断", offline.headline); assertEquals("上次确认 00:42", offline.timer); assertFalse(offline.spinning)
        val stale = show(p, 20000); assertEquals("运行状态待确认", stale.headline); assertEquals("上次确认 00:42", stale.timer); assertFalse(stale.spinning)
        assertEquals("正在同步状态", show(p, ready = false).headline)
        assertEquals("会话已结束或被替换", show(p, exact = null).headline)
        assertEquals("", show(p, exact = null).timer)
        p.invalidate(); assertFalse(show(p, 2000).spinning)
        p.accept(sample("done", 44000, false), 21000); assertEquals("已完成", show(p, 21000).headline)
    }
    @Test fun missingStartOrOldRelayNeverInventsRunningDuration() {
        val p = AgentRunPresentation()
        assertEquals("正在确认运行状态", show(p).headline)
        val legacy = p.display(agent, true, true, false, 1000)
        assertEquals("运行中", legacy.headline); assertEquals("", legacy.timer); assertTrue(legacy.detail.contains("未提供可靠"))
        p.accept(sample("working", 0, false).put("elapsed_ms", JSONObject.NULL).put("timing", "unknown"), 1000)
        assertEquals("运行中", show(p).headline); assertEquals("", show(p).timer)
    }
    @Test fun interruptedFailedIdleAndStoppedAreNotRunning() {
        for ((state, title) in mapOf("interrupted" to "已中断", "failed" to "运行失败", "idle" to "空闲", "stopped" to "已结束", "unknown" to "状态未知")) {
            val p = AgentRunPresentation(); p.accept(sample(state, 40000, false), 1000)
            assertEquals(title, show(p).headline); assertFalse(show(p).spinning)
        }
    }
    @Test fun invalidDurationsAndIllegalTickingAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AgentRunPresentation().accept(sample("working", -1, true), 0) }
        assertThrows(IllegalArgumentException::class.java) { AgentRunPresentation().accept(sample("done", 1000, true), 0) }
        assertEquals("25:01:01", AgentRunPresentation.duration(90061000)); assertEquals("00:00", AgentRunPresentation.duration(0))
    }
    companion object {
        fun sample(status: String, elapsed: Long, ticking: Boolean) = JSONObject().put("status", status).put("source", "codex_runtime")
            .put("turn_id", "turn-a").put("timing", "turn_elapsed").put("elapsed_ms", elapsed).put("ticking", ticking).put("observed_at", 1700000000000L)
    }
}
