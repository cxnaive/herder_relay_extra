package dev.herdr.companion

import org.json.JSONObject

/** Session identity survives temporary loss of proof; permission to interact does not. */
object PrimarySessionBinding {
    fun writable(agent: JSONObject?): Boolean = agent != null && agent.optString("binding_state") in setOf("", "verified")
    fun readable(agent: JSONObject?): Boolean = agent != null && agent.optString("binding_state") in setOf("", "verified", "pinned_history")
    fun live(agent: JSONObject?): Boolean = writable(agent)
    fun label(agent: JSONObject): String = when (agent.optString("binding_state")) {
        "verified" -> if (agent.optString("binding_reason") == "reported_session_conflict") "主会话已核验 · Herdr 的冲突上报已忽略" else "主会话已核验"
        "verifying" -> "正在核验主会话 · 暂不可发送"
        "stale" -> "暂时无法核验 · 已读内容是旧缓存，仅供查看"
        "ambiguous" -> "无法确定当前主会话 · 不猜测、不混合历史"
        "conflict" -> "会话身份冲突 · 暂停同步和发送"
        "pinned_history" -> "人工指定的历史 · 不代表当前主会话，不可发送"
        "ended" -> "Agent 进程已结束 · 已读内容仅供查看"
        else -> ""
    }
    fun initialDiscovery(relay: String, previous: JSONObject, next: JSONObject): Boolean {
        val before = WorkspaceBinding.fromAgent(relay, previous)
        val after = WorkspaceBinding.fromAgent(relay, next)
        return previous.optString("binding_state") == "verifying" && before.target.session.isBlank()
            && next.optString("binding_state") in setOf("verified", "pinned_history") && after.target.session.isNotBlank()
            && before.cwd == after.cwd && before.target.server == after.target.server
            && before.target.pane == after.target.pane && before.target.terminal.isNotBlank()
            && before.target.terminal == after.target.terminal && after.target.generation == before.target.generation + 1
    }
    fun sameIdentity(relay: String, previous: JSONObject, next: JSONObject): Boolean =
        WorkspaceBinding.fromAgent(relay, previous).identity() == WorkspaceBinding.fromAgent(relay, next).identity()
}
