package dev.herdr.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RelayEngine(private val context: Context, private val vault: Vault, private val listener: (JSONObject) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val serial = Executors.newSingleThreadScheduledExecutor()
    private val ownsClient = testClientFactory == null
    private val client = testClientFactory?.invoke() ?: OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val connections = mutableMapOf<String, NativeRelayConnection>()
    private val pending = mutableMapOf<String, Pending>()
    private var state = JSONObject(vault.read("state") ?: "{}")
    private var active = false
    private var destroyed = false
    var ready = true; private set
    private data class Pending(val relay: String, val readOnly: Boolean, val callback: (JSONObject?, String?) -> Unit, val timeout: Runnable, val monitor: Boolean)

    init { main.post { listener(JSONObject().put("type", "ready")) } }
    @Synchronized fun snapshot(): JSONObject = JSONObject(state.toString())
    @Synchronized private fun authentication(id: String): JSONObject? = state.optJSONObject("auth")?.optJSONObject("relays")?.optJSONObject(id)?.let { JSONObject(it.toString()) }
    @Synchronized private fun persist() { vault.write("state", state.toString()); emit(JSONObject().put("type", "persist").put("state", JSONObject(state.toString()))) }
    private fun emit(event: JSONObject) { main.post { if (!destroyed) { handle(event); listener(event) } } }
    private fun handle(event: JSONObject) {
        if (event.optString("type") == "status" && event.getString("status") == "closed") failPending(event.getString("relayId"), "连接中断，执行结果未确认；请核对后再操作")
        if (event.optString("type") != "message") return
        val value = event.getJSONObject("message"); val type = value.optString("type")
        if (type != "command_result" && type != "error" && !type.endsWith("_result") && type !in listOf("pane_content", "pane_unchanged")) return
        val id = value.optString("request_id"); val entry = pending[id] ?: return
        if (entry.relay != event.optString("relayId")) return
        pending.remove(id); main.removeCallbacks(entry.timeout)
        val error = when {
            type == "error" -> value.optJSONObject("error")?.optString("code") ?: value.optString("error", "请求被拒绝")
            value.has("ok") && !value.optBoolean("ok") -> (if (value.optString("phase") in listOf("dispatched_unknown", "unconfirmed")) "执行结果未确认：" else "") + value.optString("error", "操作失败")
            value.has("error") && value.optString("error").isNotBlank() && value.optString("error") != "null" -> value.optString("error")
            else -> null
        }
        entry.callback(if (error == null) (value.optJSONObject("data") ?: value.optJSONObject("result") ?: value).apply { if (value.has("phase")) put("_phase", value.get("phase")) } else null, error)
    }
    private fun failPending(relay: String, reason: String) {
        pending.filterValues { it.relay == relay }.keys.toList().forEach { key -> pending.remove(key)?.let { main.removeCallbacks(it.timeout); it.callback(null, if (it.readOnly) "连接中断，请重试读取；已读内容保留" else reason) } }
    }
    private fun connectAll() {
        if (!active || destroyed) return
        val relays = snapshot().optJSONArray("relays") ?: JSONArray()
        for (index in 0 until relays.length()) {
            val relay = relays.getJSONObject(index); val id = relay.getString("id")
            if (connections.containsKey(id)) continue
            val link = NativeRelayConnection(relay, client, serial, { authentication(id) }, { presented, finish ->
                synchronized(this) {
                    val auth = state.optJSONObject("auth") ?: JSONObject().put("version", 1).put("relays", JSONObject()).also { state.put("auth", it) }
                    val credential = JSONObject().put("kind", "credential").put("id", finish.getString("credential_id")).put("version", finish.getLong("credential_version"))
                        .put("secret", if (presented.getString("kind") == "invitation") finish.getString("credential_secret") else presented.getString("secret"))
                        .put("deviceId", finish.getString("device_id")).put("role", finish.getString("role")).put("locale", finish.optString("locale", "zh")).put("issuedAt", System.currentTimeMillis())
                    auth.getJSONObject("relays").put(id, credential); persist()
                }
                if (presented.getString("kind") == "invitation") emit(JSONObject().put("type", "enrolled").put("relayId", id))
            }, { value -> emit(JSONObject().put("type", "message").put("relayId", id).put("message", value)) }, { status, reason, fatal ->
                emit(JSONObject().put("type", "status").put("relayId", id).put("status", status).put("detail", JSONObject().put("reason", reason).put("fatal", fatal)))
            }, { settings, signal, frame, open, close -> NativeWebRtc(context, settings, serial, signal, frame, open, close) })
            connections[id] = link; link.connect()
        }
    }
    fun call(method: String, vararg args: Any) {
        when (method) {
            "pair" -> serial.execute { try {
                val parsed = PairingLink.parse(args[0] as String)
                synchronized(this) {
                    val relays = state.optJSONArray("relays") ?: JSONArray().also { state.put("relays", it) }; relays.put(parsed.relay)
                    val auth = state.optJSONObject("auth") ?: JSONObject().put("version", 1).put("relays", JSONObject()).also { state.put("auth", it) }
                    auth.getJSONObject("relays").put(parsed.relay.getString("id"), parsed.invitation); persist()
                }
                emit(JSONObject().put("type", "paired").put("relay", parsed.relay)); main.post { connectAll() }
            } catch (error: Exception) { emit(JSONObject().put("type", "failure").put("message", error.message ?: "配对链接无效")) } }
            "send" -> send(args[0] as String, args[1] as JSONObject)
            "reconnect" -> { pause(); resume() }
            "pause" -> pause()
            "remove" -> {
                val id = args[0] as String; connections.remove(id)?.close(); failPending(id, "设备已移除")
                synchronized(this) { val relays = state.optJSONArray("relays") ?: JSONArray(); state.put("relays", JSONArray((0 until relays.length()).map { relays.getJSONObject(it) }.filter { it.optString("id") != id })); state.optJSONObject("auth")?.optJSONObject("relays")?.remove(id); persist() }
                emit(JSONObject().put("type", "removed").put("relayId", id))
            }
        }
    }
    fun send(relay: String, payload: JSONObject) {
        val link = connections[relay]
        val reject = { emit(JSONObject().put("type", "message").put("relayId", relay).put("message", JSONObject().put("type", "error").put("request_id", payload.optString("request_id")).put("error", "设备未连接，未发送请求"))) }
        if (link == null) reject() else link.send(payload, reject)
    }
    fun request(relay: String, payload: JSONObject, timeoutMs: Long = 45000, callback: (JSONObject?, String?) -> Unit) = requestOwned(relay, payload, timeoutMs, false, callback)
    fun requestMonitor(relay: String, payload: JSONObject, callback: (JSONObject?, String?) -> Unit) = requestOwned(relay, payload, 8000, true, callback)
    private fun requestOwned(relay: String, payload: JSONObject, timeoutMs: Long, monitor: Boolean, callback: (JSONObject?, String?) -> Unit) {
        if (destroyed) { callback(null, "客户端已关闭"); return }
        val id = (if (payload.optString("type") == "send_pi_prompt_unconfirmed") payload.optJSONObject("pi_prompt_lease")?.optString("incarnation").orEmpty() + "_" else "") + UUID.randomUUID().toString()
        payload.put("request_id", id).put("protocol", 3).put("client_id", "android-pocket")
        val readOnly = payload.optString("type") in setOf("get_conversation_history", "get_conversation_updates", "read_pane", "workspace_file", "workspace_file_read", "workspace_tree", "get_agent_settings", "get_agent_run_state")
        val timeout = Runnable { pending.remove(id)?.callback?.invoke(null, if (readOnly) "读取超时，可重试；已读内容保留" else "请求超时，执行结果未确认；请核对后再操作") }
        pending[id] = Pending(relay, readOnly, callback, timeout, monitor); main.postDelayed(timeout, timeoutMs); send(relay, payload)
    }
    fun cancelForegroundRequests() = cancelOwned(false)
    fun cancelMonitorRequests() = cancelOwned(true)
    private fun cancelOwned(monitor: Boolean) {
        pending.filterValues { it.monitor == monitor }.keys.toList().forEach { id ->
            pending.remove(id)?.let { main.removeCallbacks(it.timeout); it.callback(null, if (it.readOnly) "读取已暂停" else "界面已离开，执行结果未确认；请核对后再操作") }
        }
    }
    fun resume() { if (!active) { active = true; connectAll() } }
    fun pause() { active = false; connections.forEach { (id, link) -> link.close(); failPending(id, "连接暂停，执行结果未确认") }; connections.clear() }
    fun destroy() { destroyed = true; ready = false; cancelForegroundRequests(); cancelMonitorRequests(); pause(); serial.shutdown(); if (ownsClient) { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() } }
    companion object { internal var testClientFactory: (() -> OkHttpClient)? = null }
}
