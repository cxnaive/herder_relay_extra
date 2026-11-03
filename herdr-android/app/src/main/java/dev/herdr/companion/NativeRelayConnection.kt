package dev.herdr.companion

import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One connection owns its sequence counters and never replays application writes. */
class NativeRelayConnection(
    private val relay: JSONObject,
    private val client: OkHttpClient,
    private val serial: ScheduledExecutorService,
    private val authentication: () -> JSONObject?,
    private val enrolled: (JSONObject, JSONObject) -> Unit,
    private val message: (JSONObject) -> Unit,
    private val status: (String, String, Boolean) -> Unit,
    private val directFactory: ((JSONObject, (JSONObject) -> Unit, (ByteArray) -> Unit, () -> Unit, () -> Unit) -> DirectChannel)? = null
) {
    interface DirectChannel { fun signal(value: JSONObject); fun send(bytes: ByteArray): Boolean; fun close() }
    private var socket: WebSocket? = null
    private var cipher: RelayCipher? = null
    private var handshake: RelayHandshake? = null
    private var presented: JSONObject? = null
    private var phase = "closed"
    private var active = false
    private var epoch = 0
    private var failures = 0
    private var gatewayIndex = 0
    private var activeGateway = ""
    private var nativeTCP = false
    private var chunks = RelayChunks()
    private var direct: DirectChannel? = null
    private var directCipher: RelayCipher? = null
    private var directHandshake: RelayHandshake? = null
    private var directReady = false
    private var directChunks = RelayChunks()
    private var stunPort = 0
    private var directAttempted = false
    private var directEpoch = 0
    private val gateway get() = relay.optString("transport") == "hybrid"

    fun connect() { serial.execute { active = true; dial() } }
    private fun dial() {
        if (!active) return
        retire()
        val auth = authentication()
        if (auth == null || auth.optString("kind") == "invitation" && auth.optLong("expiresAt") <= System.currentTimeMillis()) {
            active = false; status("closed", "配对邀请已失效，请重新配对", true); return
        }
        val owner = epoch
        presented = JSONObject(auth.toString()); phase = if (gateway) "gateway_hello" else "opening"
        val bases = relay.optJSONArray("gatewayUrls")
        val base = if (gateway) bases?.optString(gatewayIndex % bases.length().coerceAtLeast(1))?.ifBlank { null } ?: relay.getString("gatewayUrl") else relay.getString("url")
        if (gateway) activeGateway = base
        nativeTCP = gateway && base.startsWith("tcp://")
        status("connecting", if (nativeTCP) "tcp" else if (gateway) "gateway" else "websocket", false)
        if (nativeTCP) {
            // The reader waits for serial processing: native records cannot create an unbounded callback queue.
            socket = NativeTcpSocket(base, { bytes, binary ->
                if (!serial.isShutdown) serial.submit { if (owner == epoch && active) {
                    try { receive(bytes, binary) }
                    catch (_: RelayChunkTimeout) { fail("TCP 分片传输停滞，正在重连", false) }
                    catch (_: Exception) { fail("TCP 端到端加密校验失败", true) }
                } }.get(120, TimeUnit.SECONDS)
            }, { dispatch(owner) { fail("TCP 连接中断，正在重新连接", false) } })
            serial.schedule({ if (epoch == owner && phase != "ready") fail("TCP 连接或加密握手超时", false) }, 15, TimeUnit.SECONDS)
            return
        }
        val request = Request.Builder().url(if (gateway) base.trimEnd('/') + "/connect" else directSocketUrl(base))
        if (!gateway) request.header("Sec-WebSocket-Protocol", "herdr-e2ee-v2")
        socket = client.newWebSocket(request.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = dispatch(owner) {
                if (!gateway) {
                    if (response.header("Sec-WebSocket-Protocol") != "herdr-e2ee-v2") { fail("服务器未启用加密协议", true); return@dispatch }
                    startHandshake()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) = dispatch(owner) { receive(text.toByteArray(), false) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = dispatch(owner) { receive(bytes.toByteArray(), true) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dispatch(owner) { fail("无法连接电脑，请检查网络或 relay", false) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = dispatch(owner) { fail(if (code == 4401) "设备凭据已被拒绝，请重新配对" else "连接已关闭", code == 4401 && !gateway) }
        })
        serial.schedule({ if (epoch == owner && phase != "ready") fail("连接或加密握手超时", false) }, 12, TimeUnit.SECONDS)
    }
    private fun dispatch(owner: Int, work: () -> Unit) {
        if (serial.isShutdown) return
        try { serial.execute { if (owner == epoch && active) try { work() } catch (_: RelayChunkTimeout) { fail("分片传输停滞，正在重新连接", false) } catch (_: Exception) { fail("加密协议校验失败", true) } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { /* The owner was destroyed while a socket callback was queued. */ }
    }
    private fun startHandshake() {
        handshake = RelayHandshake(requireNotNull(presented)); phase = "hello"
        wire(handshake!!.hello().toString().toByteArray())
    }
    private fun receive(bytes: ByteArray, binary: Boolean) {
        require(bytes.size <= RelayChunks.MAX_BYTES)
        if (phase == "gateway_hello") {
            require(!binary)
            val hello = JSONObject(bytes.toString(Charsets.UTF_8))
            require(hello.getString("type") == "gateway_hello" && hello.getInt("proto") == 1)
            val nonce = RelayCrypto.decode(hello.getString("nonce")); require(nonce.size == 32)
            val id = relay.getString("gatewayRelayId")
            stunPort = hello.optInt("stun_port").takeIf { it in 1..65535 } ?: 0
            val proof = RelayCrypto.hmac(RelayCrypto.decode(relay.getString("rendezvousKey")), "herdr-gw-connect\u0000".toByteArray(), id.toByteArray(), nonce)
            phase = "gateway_ready"
            socket!!.send(JSONObject().put("type", "connect").put("proto", 1).put("relay_id", id).put("proof", RelayCrypto.encode(proof)).toString()); return
        }
        if (phase == "gateway_ready") {
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            if (value.optString("type") != "ready") { fail("网关暂时无法连接此电脑", false); return }
            startHandshake(); return
        }
        val logical = if (gateway) { require(binary); chunks.push(bytes) ?: return } else { require(!binary); bytes }
        if (phase == "hello") {
            cipher = handshake!!.complete(JSONObject(logical.toString(Charsets.UTF_8)), gateway); handshake = null; phase = "finish"
            wire(cipher!!.encrypt("{\"type\":\"e2ee_client_finish\",\"version\":2}")); return
        }
        val value = JSONObject(requireNotNull(cipher).decrypt(logical))
        if (phase == "finish") {
            val auth = requireNotNull(presented)
            validateEnrollment(value, auth); enrolled(auth, value)
            if (nativeTCP && auth.getString("kind") == "invitation") {
                // Confirm the persisted credential immediately, consuming the retryable invitation.
                val saved = requireNotNull(authentication())
                require(saved.optString("kind") == "credential" && saved.optString("id") == value.getString("credential_id"))
                dial()
                return
            }
            phase = "ready"; failures = 0; status("connected", if (nativeTCP) "tcp" else if (gateway) "gateway" else "websocket", false); return
        }
        require(phase == "ready")
        if (value.optString("type").startsWith("webrtc_")) { direct?.signal(value); return }
        if (gateway && !nativeTCP && !directAttempted && value.optString("type") == "push_config" && value.optJSONArray("capabilities")?.toString()?.contains("webrtc_direct") == true) startDirect()
        val settlement = value.optString("type") in listOf("command_result", "error", "action_receipt", "upload_begin_result", "upload_chunk_result", "upload_finish_result", "upload_cancel_result")
        if (!directReady || settlement) message(value)
    }
    private fun startDirect() {
        val factory = directFactory ?: return
        directAttempted = true; val owner = epoch; val directOwner = ++directEpoch
        val settings = JSONObject(relay.toString()).put("stun_port", stunPort).put("gatewayUrl", activeGateway)
        direct = factory(settings, { signal -> dispatch(owner) { sendGateway(signal) } }, { frame -> dispatch(owner) {
            if (directOwner == directEpoch) try { receiveDirect(frame) } catch (_: Exception) { loseDirect("直连读取中断，已回退网关") }
        } }, {
            dispatch(owner) {
                if (directOwner != directEpoch) return@dispatch
                val auth = authentication() ?: return@dispatch
                directHandshake = RelayHandshake(auth)
                sendDirectWire(directHandshake!!.hello().toString().toByteArray())
            }
        }, { dispatch(owner) { if (directOwner == directEpoch) loseDirect("直连中断，已回退网关") } })
        serial.schedule({ if (owner == epoch && directOwner == directEpoch && !directReady) loseDirect("直连握手超时，继续使用网关") }, 20, TimeUnit.SECONDS)
    }
    private fun clearDirect() {
        directEpoch++
        val previous = direct; direct = null; directReady = false; directCipher = null; directHandshake = null; directChunks = RelayChunks()
        previous?.close()
    }
    private fun loseDirect(reason: String) {
        val wasReady = directReady
        clearDirect()
        if (wasReady && phase == "ready") {
            status("closed", reason, false); status("connected", "gateway", false)
            sendGateway(JSONObject().put("type", "refresh_agents"))
        }
    }
    private fun receiveDirect(frame: ByteArray) {
        val logical = directChunks.push(frame) ?: return
        if (directHandshake != null) {
            directCipher = directHandshake!!.complete(JSONObject(logical.toString(Charsets.UTF_8)), true); directHandshake = null
            sendDirectWire(directCipher!!.encrypt("{\"type\":\"e2ee_client_finish\",\"version\":2}")); return
        }
        val value = JSONObject(requireNotNull(directCipher).decrypt(logical))
        if (value.optString("type") == "e2ee_server_finish") { validateEnrollment(value, requireNotNull(authentication())); return }
        if (!directReady) { directReady = true; status("closed", "正在切换到直连", false); status("connected", "webrtc", false) }
        message(value)
    }
    private fun validateEnrollment(value: JSONObject, auth: JSONObject) {
        require(value.getString("type") == "e2ee_server_finish" && value.getInt("version") == 2)
        require(value.getString("role") in listOf("reader", "controller") && value.getLong("credential_version") > 0)
        for (field in listOf("device_id", "credential_id")) require(value.getString(field).length in 1..256)
        if (auth.getString("kind") == "invitation") require(RelayCrypto.decode(value.getString("credential_secret")).size == 32)
        else require(!value.has("credential_secret"))
    }
    private fun sendDirectWire(bytes: ByteArray) { for (piece in RelayChunks.split(bytes, 16384)) check(direct?.send(piece) == true) }
    private fun wire(bytes: ByteArray) {
        if (gateway) for (piece in RelayChunks.split(bytes)) check(socket?.send(piece.toByteString()) == true)
        else check(socket?.send(bytes.toString(Charsets.UTF_8)) == true)
    }
    private fun sendGateway(payload: JSONObject) { if (phase == "ready") wire(cipher!!.encrypt(payload.toString())) }
    fun send(payload: JSONObject, rejected: () -> Unit) { serial.execute {
        if (!active || phase != "ready") { rejected(); return@execute }
        try { if (directReady) sendDirectWire(directCipher!!.encrypt(payload.toString())) else sendGateway(payload) }
        catch (_: Exception) { rejected(); fail("发送失败，请核对执行结果", false) }
    } }
    private fun fail(reason: String, fatal: Boolean) {
        retire(); status("closed", reason, fatal)
        if (fatal) active = false
        if (active) { failures++; gatewayIndex++; val owner = epoch; serial.schedule({ if (active && owner == epoch) dial() }, minOf(30, 1 shl failures.coerceAtMost(5)).toLong(), TimeUnit.SECONDS) }
    }
    private fun retire() { epoch++; socket?.cancel(); socket = null; clearDirect(); cipher = null; handshake = null; chunks = RelayChunks(); directAttempted = false; phase = "closed" }
    companion object {
        internal fun directSocketUrl(base: String): String = if (base.trimEnd('/').endsWith("/ws")) base.trimEnd('/') else base.trimEnd('/') + "/ws"
    }
    fun close() { serial.execute { active = false; retire() } }
}
