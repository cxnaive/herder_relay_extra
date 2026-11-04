package dev.herdr.companion

import org.json.JSONObject

/** Session identity survives temporary loss of proof; permission to interact does not. */
object PrimarySessionBinding {
    fun writable(agent: JSONObject?): Boolean = agent != null && (agent.optString("agent") != "codex" || agent.optString("binding_state") in setOf("", "verified"))
    fun readable(agent: JSONObject?): Boolean = writable(agent)
    fun live(agent: JSONObject?): Boolean = writable(agent)
    fun label(agent: JSONObject): String {
        if (agent.optString("agent").let { it.isNotBlank() && it != "codex" }) return ""
        when (agent.optString("binding_reason")) {
            "standalone_runtime_unavailable" -> return "已使用 --no-daemon · 无法读取本进程会话写锁，请检查版本与进程权限"
            "standalone_metadata_unavailable" -> return "独立会话元数据尚不可读 · 新会话请在电脑端开始首轮输入后刷新"
            "standalone_ambiguous" -> return "独立 Codex 仍持有多个主会话 · 旧 hook 上报不足以确认，请退出原 TUI 后在独立终端恢复目标会话"
            "standalone_ancestry_unavailable" -> return "独立 Codex 的主子会话关系无法完整确认 · 不会猜选"
            "standalone_metadata_conflict" -> return "独立 Codex 的写锁与会话元数据冲突 · 不会改读其他会话"
            "runtime_changed" -> return "终端或主会话已变化 · 正在重新确认，旧请求已失效"
            "runtime_metadata_conflict", "process_project_conflict" -> return "Codex 运行时与会话信息冲突 · 请检查终端和工作目录"
            "runtime_platform_unsupported" -> return "此平台暂不支持 Codex 终端运行时确认"
        }
        return when (agent.optString("binding_state")) {
            "verified" -> "终端对应的 Codex 主会话已确认"
            "verifying", "stale", "ambiguous", "conflict" -> "无法唯一确认主会话 · 可尝试 codex --no-daemon 并检查 Herdr hook，不保证修复；已读内容仅为旧缓存"
            else -> ""
        }
    }
    /** Follow a final ID from Relay inventory; never derive or confirm one locally. */
    fun relayReplacement(relay: String, previous: JSONObject, next: JSONObject): Boolean {
        val before = WorkspaceBinding.fromAgent(relay, previous)
        val after = WorkspaceBinding.fromAgent(relay, next)
        return previous.optString("agent") == "codex" && next.optString("agent") == "codex"
            && next.optString("binding_state").isNotBlank()
            && before.target.session != after.target.session && after.target.generation > before.target.generation
            && before.cwd == after.cwd && before.target.server == after.target.server
            && before.target.pane == after.target.pane && before.target.terminal.isNotBlank()
            && before.target.terminal == after.target.terminal
    }
    fun sameIdentity(relay: String, previous: JSONObject, next: JSONObject): Boolean =
        WorkspaceBinding.fromAgent(relay, previous).identity() == WorkspaceBinding.fromAgent(relay, next).identity()
}
