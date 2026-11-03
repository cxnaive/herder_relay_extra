package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject

/** Pure validation and exact-target payloads shared by the management dialogs. */
object AgentManagement {
    fun launchPayload(workspace: JSONObject, profile: String, name: String, prompt: String): JSONObject {
        val id = workspace.optString("workspace_id")
        val cwd = workspace.optString("cwd")
        require(id.isNotBlank() && !id.startsWith("cwd:") && cwd.startsWith('/') && cwd.none { it.isISOControl() }) { "工作空间身份或目录不可用，请刷新列表" }
        require(AgentPresentation.server(workspace) == "primary") { "此 Relay 尚不支持在其他 server session 下创建 Agent" }
        require(profile.isNotBlank()) { "请选择 Agent 类型" }
        require(name.matches(Regex("[a-z][a-z0-9_-]{0,31}"))) { "名称须以小写字母开头，最多 32 位，可含数字、_ 和 -" }
        require(prompt.codePointCount(0, prompt.length) <= 100000) { "初始提示过长" }
        return JSONObject().put("type", "agent_start").put("server_session_id", "primary").put("workspace_id", id)
            .put("cwd", cwd).put("profile_id", profile).put("name", name).put("prompt", prompt)
    }
    fun renamePayload(name: String): JSONObject {
        val value = name.trim()
        require(value.isNotEmpty() && value.codePointCount(0, value.length) <= 128 && value.none { it.isISOControl() }) { "请输入 1–128 字的名称，不包含换行或控制字符" }
        return JSONObject().put("name", value)
    }
    fun settingsPayload(settings: JSONObject, model: String, effort: String): JSONObject {
        require(settings.optBoolean("available") && settings.optString("settings_revision").isNotBlank()) { "请先读取当前设置" }
        val models = settings.optJSONArray("models") ?: JSONArray()
        val option = (0 until models.length()).map { models.getJSONObject(it) }.find { it.optString("model") == model }
        val efforts = option?.optJSONArray("supportedReasoningEfforts") ?: JSONArray()
        require((0 until efforts.length()).any { efforts.getJSONObject(it).optString("reasoningEffort") == effort }) { "此模型不支持所选思考强度" }
        return JSONObject().put("model", model).put("effort", effort).put("settings_revision", settings.getString("settings_revision"))
    }
    fun unknown(data: JSONObject?, error: String?): Boolean = error?.let { it.contains("未确认") || it.contains("超时") }
        ?: (data?.optString("_phase") in listOf("unconfirmed", "dispatched_unknown", "awaiting_evidence"))
}
