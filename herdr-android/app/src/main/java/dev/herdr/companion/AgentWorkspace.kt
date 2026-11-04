package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Base64

data class AgentTarget(val relay: String, val server: String, val pane: String, val terminal: String, val generation: Long, val session: String, val bindingEpoch: Long = 0) {
    fun json(): JSONObject = JSONObject().put("server_session_id", server).put("pane_id", pane)
        .put("terminal_id", terminal).put("generation", generation).put("agent_session_id", session).put("binding_epoch", bindingEpoch)
    fun identity(): AgentTarget = copy(bindingEpoch = 0)
    fun historyFragment(): String {
        val tuple = JSONArray().put(3).put(relay).put(server).put(pane).put(terminal).put(generation).put(session)
        return "#history=r3." + Base64.getUrlEncoder().withoutPadding().encodeToString(tuple.toString().toByteArray(Charsets.UTF_8))
    }
    companion object {
        fun fromAgent(relay: String, agent: JSONObject): AgentTarget = AgentTarget(relay,
            agent.optString("server_session_id").ifBlank { "primary" }, agent.optString("pane_id"),
            agent.optString("terminal_id"), agent.optLong("generation"),
            agent.optString("agent_session_id").ifBlank { agent.optString("session_id") }, agent.optLong("binding_epoch"))
    }
}

/** Status and last-seen changes must not invalidate an in-flight file read. */
data class WorkspaceBinding(val target: AgentTarget, val cwd: String) {
    fun identity(): WorkspaceBinding = copy(target = target.identity())
    // Preserve the existing vault namespace; availability epochs are never draft identity.
    fun storageKey(): String = "WorkspaceBinding(target=AgentTarget(relay=${target.relay}, server=${target.server}, pane=${target.pane}, terminal=${target.terminal}, generation=${target.generation}, session=${target.session}), cwd=$cwd)"
    companion object { fun fromAgent(relay: String, agent: JSONObject) = WorkspaceBinding(AgentTarget.fromAgent(relay, agent), agent.optString("cwd")) }
}

data class AgentRoute(val relay: String, val pane: String, val target: AgentTarget?) {
    fun matches(agent: JSONObject): Boolean = agent.optString("pane_id") == pane &&
        (target == null || target.identity() == AgentTarget.fromAgent(relay, agent).identity())

    companion object {
        fun parse(url: String): AgentRoute? = runCatching {
            if (url.length > 16000) return null
            val uri = URI(url)
            if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net") return null
            val fragment = uri.fragment ?: return null
            if (!fragment.startsWith("pane=") && !fragment.startsWith("history=")) return null
            val token = fragment.substringAfter('=')
            if (token.startsWith("r3.")) {
                val fields = JSONArray(String(Base64.getUrlDecoder().decode(token.removePrefix("r3.")), Charsets.UTF_8))
                if (fields.length() != 7 || fields.getInt(0) != 3 || fields.getLong(5) < 0) return null
                val ids = listOf(1, 2, 3, 4).map { fields.getString(it) }
                if (ids.any { !it.matches(Regex("[A-Za-z0-9._:@%+\\-]{1,160}")) }) return null
                val target = AgentTarget(ids[0], ids[1], ids[2], ids[3], fields.getLong(5), fields.getString(6))
                AgentRoute(target.relay, target.pane, target)
            } else {
                val parts = token.split("::", limit = 2)
                if (parts.size != 2 || parts.any { it.isBlank() || it.length > 160 }) null else AgentRoute(parts[0], parts[1], null)
            }
        }.getOrNull()
    }
}
