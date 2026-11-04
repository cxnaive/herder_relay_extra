package dev.herdr.companion

import org.json.JSONObject

/** Passive diagnostic viewer; no candidate selection or binding mutation. */
object SessionBindingDiagnostics {
    const val ACTION = "get_session_binding_diagnostics"
    const val CAPABILITY = "session_binding_diagnostics_v1"
    private const val MAX_BYTES = 64 * 1024

    fun render(relay: String, agent: JSONObject, data: JSONObject): String {
        require(data.optInt("schema_version") >= 1)
        require(data.optString("observation") == "inventory_snapshot")
        require(AgentProtocol.targetMatches(relay, agent, data))
        val raw = data.toString()
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val note = if (data.optBoolean("available")) {
            "Relay 诊断快照，仅供查看；不在手机上选择或确认主会话。"
        } else {
            "此 Agent 暂无绑定诊断信息。"
        }
        val truncated = if (data.optBoolean("truncated")) "\n部分诊断信息已截断。" else ""
        // Preserve nested/unknown fields so richer reports do not need a new UI.
        return "$note$truncated\n\n${data.toString(2)}"
    }
}
