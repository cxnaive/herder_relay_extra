package dev.herdr.companion

import android.content.Context
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** One connection/credential writer shared by the visible UI and foreground monitor service. */
class SharedRelay private constructor(context: Context) {
    private val context = context.applicationContext as android.app.Application
    val vault = Vault(this.context)
    val engine = RelayEngine(this.context, vault, ::event)
    private val listeners = linkedMapOf<Any, (JSONObject) -> Unit>()
    private val uiOwners = mutableSetOf<Any>()
    val agents = linkedMapOf<String, JSONArray>()
    val configs = linkedMapOf<String, JSONObject>()
    val workspaces = linkedMapOf<String, JSONArray>()
    val online = mutableSetOf<String>()
    val fatalRelays = mutableSetOf<String>()
    private val freshAgents = mutableSetOf<String>()
    private val epochs = mutableMapOf<String, Int>()
    var monitorOwned = false; private set
    val uiVisible get() = uiOwners.isNotEmpty()
    fun epoch(relay: String) = epochs[relay] ?: 0
    fun monitorStateChanged() = event(JSONObject().put("type", "monitor_state"))
    fun ready(relay: String) = relay in online && relay in freshAgents && configs[relay]?.optJSONObject("inventory")?.optString("state") == "ready"
    fun relayName(id: String): String {
        val rows = engine.snapshot().optJSONArray("relays") ?: JSONArray()
        return (0 until rows.length()).map { rows.getJSONObject(it) }.find { it.optString("id") == id }?.optString("label").orEmpty().ifBlank { "电脑" }
    }
    fun workspaceName(id: String, agent: JSONObject): String = AgentPresentation.workspaceTitle(id, agent, workspaces)
    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    fun attachUI(owner: Any, listener: (JSONObject) -> Unit) {
        mainThread(); uiOwners.add(owner); listeners[owner] = listener; replay(listener); engine.resume()
    }
    fun detachUI(owner: Any) {
        mainThread(); uiOwners.remove(owner); listeners.remove(owner)
        if (!uiVisible) engine.cancelForegroundRequests()
        releaseIfUnused()
    }
    fun attachMonitor(owner: Any, listener: (JSONObject) -> Unit) {
        mainThread(); monitorOwned = true; listeners[owner] = listener; engine.resume()
    }
    fun detachMonitor(owner: Any) {
        mainThread(); listeners.remove(owner); monitorOwned = false; engine.cancelMonitorRequests(); releaseIfUnused()
    }
    private fun releaseIfUnused() {
        if (uiVisible || monitorOwned) return
        engine.pause(); online.clear(); freshAgents.clear()
        configs.values.forEach { it.put("inventory", JSONObject().put("state", "stale")) }
    }
    private fun message(id: String, value: JSONObject) = JSONObject().put("type", "message").put("relayId", id).put("message", value)
    private fun replay(listener: (JSONObject) -> Unit) {
        for (id in agents.keys + configs.keys + online) {
            listener(JSONObject().put("type", "status").put("relayId", id).put("status", if (id in online) "connected" else "closed"))
            configs[id]?.let { listener(message(id, JSONObject(it.toString()))) }
            agents[id]?.let { listener(message(id, JSONObject().put("type", "agents").put("agents", JSONArray(it.toString())))) }
            workspaces[id]?.let { listener(message(id, JSONObject().put("type", "workspaces").put("workspaces", JSONArray(it.toString())))) }
        }
    }
    internal fun event(event: JSONObject) {
        mainThread(); val id = event.optString("relayId")
        when (event.optString("type")) {
            "status" -> {
                if (event.optString("status") == "connected") { online.add(id); fatalRelays.remove(id) } else {
                    if (event.optJSONObject("detail")?.optBoolean("fatal") == true) fatalRelays.add(id)
                    online.remove(id); freshAgents.remove(id); epochs[id] = epoch(id) + 1
                    configs[id]?.put("inventory", JSONObject().put("state", "stale"))
                }
            }
            "removed" -> { agents.remove(id); configs.remove(id); workspaces.remove(id); online.remove(id); fatalRelays.remove(id); freshAgents.remove(id); epochs[id] = epoch(id) + 1 }
            "message" -> {
                val value = event.getJSONObject("message")
                when (value.optString("type")) {
                    "push_config" -> configs[id] = JSONObject(value.toString())
                    "inventory_status" -> configs[id]?.put("inventory", JSONObject(value.toString()))
                    "agents" -> { agents[id] = JSONArray((value.optJSONArray("agents") ?: JSONArray()).toString()); freshAgents.add(id) }
                    "workspaces" -> workspaces[id] = JSONArray((value.optJSONArray("workspaces") ?: JSONArray()).toString())
                    "agent_update", "blocked" -> {
                        val rows = agents[id] ?: JSONArray(); val pane = value.optString("pane_id")
                        val index = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).optString("pane_id") == pane }
                        if (index != null) {
                            val updated = JSONObject(rows.getJSONObject(index).toString())
                            value.keys().forEach { key -> if (key != "type") updated.put(key, value.get(key)) }
                            if (value.optString("type") == "blocked") updated.put("status", "blocked")
                            rows.put(index, updated)
                        }
                    }
                    "agent_removed" -> agents[id]?.let { rows -> agents[id] = JSONArray((0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("pane_id") != value.optString("pane_id") }) }
                }
            }
        }
        listeners.values.toList().forEach { it(JSONObject(event.toString())) }
    }
    companion object {
        @Volatile private var instance: SharedRelay? = null
        fun get(context: Context): SharedRelay = instance ?: synchronized(this) { instance ?: SharedRelay(context).also { instance = it } }
        fun notifyMonitorStateIfPresent() { instance?.monitorStateChanged() }
        internal fun resetForTest() { instance?.engine?.destroy(); instance = null }
    }
}
