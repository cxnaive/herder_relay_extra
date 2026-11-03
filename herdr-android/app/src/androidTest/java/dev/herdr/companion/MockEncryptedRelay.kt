package dev.herdr.companion

import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.security.SecureRandom
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer

/** Real TLS + WebSocket + E2EE fixture. UI tests never inject Agent rows into the Activity. */
class MockEncryptedRelay(private val gateway: Boolean = false) : AutoCloseable {
    val server = MockWebServer()
    val requests = LinkedBlockingQueue<JSONObject>()
    val recorded = CopyOnWriteArrayList<JSONObject>()
    val failures = CopyOnWriteArrayList<Throwable>()
    val sessions = CopyOnWriteArrayList<Session>()
    var denyWrites = false
    @Volatile var emitLiveUpdate = false
    @Volatile var dropNextWrite = false
    var uploadedBytes = java.io.ByteArrayOutputStream()
    var text = "# 原生文件\n\n来自模拟 relay 的真实加密回复。\n"
    val key = "0123456789abcdef0123456789abcdef".toByteArray()
    val credential = ByteArray(32) { 9 }
    var enrolled = false
    val client: OkHttpClient
    val agent = JSONObject().put("pane_id", "pane-a").put("terminal_id", "term-a").put("server_session_id", "primary").put("generation", 1)
        .put("agent_session_id", "session-a").put("name", "原生测试 Agent").put("agent", "codex").put("project", "Pocket Test").put("cwd", "/work/project").put("status", "idle")
    init {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server.useHttps(serverTls.sslSocketFactory(), false)
        client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().addHeader("Sec-WebSocket-Protocol", "herdr-e2ee-v2").withWebSocketUpgrade(Session())
        }
        server.start()
    }
    val url get() = server.url("/").toString().replace("https:", "wss:").trimEnd('/')
    val setup get() = "https://localhost/#setup=" + key.toString(Charsets.UTF_8) + (if (gateway) "&gateways=" else "&relay=") + java.net.URLEncoder.encode(url, "UTF-8") + "&label=MockRelay"
    fun storedState(): String = JSONObject().put("relays", JSONArray().put(JSONObject().put("id", "fixture").put("label", "模拟电脑").put("url", url).put("token", "").put("paired", true)))
        .put("auth", JSONObject().put("version", 1).put("relays", JSONObject().put("fixture", JSONObject().put("kind", "credential").put("id", "credential-a").put("secret", RelayCrypto.encode(credential)).put("version", 1).put("deviceId", "device-a").put("role", if (denyWrites) "reader" else "controller").put("locale", "zh").put("issuedAt", 1000)))).toString()
    fun push(value: JSONObject) { sessions.filter { it.ready }.forEach { it.send(value) } }
    fun disconnect() { sessions.forEach { it.socket?.close(1001, "fixture reconnect") } }
    fun expect(type: String, timeout: Long = 10): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout)
        while (System.nanoTime() < deadline) { val item = requests.poll(200, TimeUnit.MILLISECONDS) ?: continue; if (item.optString("type") == type) return item }
        error("Expected request $type; failures=$failures")
    }
    inner class Session : WebSocketListener() {
        private var uploadedName = "test.txt"
        var socket: WebSocket? = null
        var ready = false
        private var outgoing = ByteArray(0); private var incoming = ByteArray(0)
        private var sendSequence = 0L; private var receiveSequence = 0L
        private var invitation = false
        private var gatewayReady = !gateway
        private val challenge = ByteArray(32) { 42 }
        private val chunks = RelayChunks()
        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket; sessions += this; if (gateway) webSocket.send(JSONObject().put("type", "gateway_hello").put("proto", 1).put("nonce", RelayCrypto.encode(challenge)).toString()) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                val logical = chunks.push(bytes.toByteArray()) ?: return
                if (logical[0] == 2.toByte()) {
                    val sequence = ByteBuffer.wrap(logical, 2, 8).long
                    onMessage(webSocket, JSONObject().put("type", "e2ee").put("version", 2).put("sequence", sequence).put("ciphertext", RelayCrypto.encode(logical.copyOfRange(10, logical.size))).toString())
                } else onMessage(webSocket, logical.toString(Charsets.UTF_8))
            } catch (error: Throwable) { failures += error; webSocket.close(4401, "invalid binary frame") }
        }
        override fun onMessage(webSocket: WebSocket, raw: String) {
            try {
                val value = JSONObject(raw)
                if (!gatewayReady) {
                    val id = RelayCrypto.encode(RelayCrypto.hkdf(key, "herdr-gateway-v1".toByteArray(), "herdr-gw-id", 16))
                    val rendezvous = RelayCrypto.hkdf(key, "herdr-gateway-v1".toByteArray(), "herdr-gw-auth")
                    require(value.getString("type") == "connect" && value.getString("relay_id") == id)
                    require(java.security.MessageDigest.isEqual(RelayCrypto.decode(value.getString("proof")), RelayCrypto.hmac(rendezvous, "herdr-gw-connect\u0000".toByteArray(), id.toByteArray(), challenge)))
                    gatewayReady = true; webSocket.send("{\"type\":\"ready\"}"); return
                }
                if (value.optString("type") == "e2ee_client_hello") {
                    invitation = value.getString("auth_kind") == "invitation"
                    require(!invitation || !enrolled)
                    val secret = if (invitation) key else credential
                    val binding = "herdr-e2ee-v2 auth\u0000${value.getString("auth_kind")}\u0000${value.getString("auth_id")}\u0000${value.getLong("auth_version")}\u0000".toByteArray()
                    val clientNonce = RelayCrypto.decode(value.getString("nonce")); val clientPublic = RelayCrypto.decode(value.getString("public_key"))
                    require(java.security.MessageDigest.isEqual(RelayCrypto.decode(value.getString("proof")), RelayCrypto.hmac(secret, "herdr-e2ee-v2 client\u0000".toByteArray(), binding, clientNonce, clientPublic)))
                    val pair = RelayCrypto.pair(); val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }; val pub = RelayCrypto.publicBytes(pair.public)
                    val transcript = binding + clientNonce + clientPublic + nonce + pub
                    val shared = RelayCrypto.shared(pair.private, RelayCrypto.publicKey(clientPublic)); val salt = RelayCrypto.hmac(secret, "herdr-e2ee-v2 key\u0000".toByteArray(), transcript)
                    incoming = RelayCrypto.hkdf(shared, salt, "herdr-e2ee-v2 c2s"); outgoing = RelayCrypto.hkdf(shared, salt, "herdr-e2ee-v2 s2c")
                    sendLogical(JSONObject().put("type", "e2ee_server_hello").put("version", 2).put("nonce", RelayCrypto.encode(nonce)).put("public_key", RelayCrypto.encode(pub)).put("proof", RelayCrypto.encode(RelayCrypto.hmac(secret, "herdr-e2ee-v2 server\u0000".toByteArray(), transcript))).toString().toByteArray()); return
                }
                require(value.getLong("sequence") == receiveSequence)
                val plain = JSONObject(RelayCrypto.aes(incoming, receiveSequence++, "c2s", RelayCrypto.decode(value.getString("ciphertext")), false).toString(Charsets.UTF_8))
                if (plain.optString("type") == "e2ee_client_finish") {
                    val finish = JSONObject().put("type", "e2ee_server_finish").put("version", 2).put("device_id", "device-a").put("credential_id", "credential-a").put("credential_version", 1).put("role", if (denyWrites) "reader" else "controller").put("locale", "zh")
                    if (invitation) { finish.put("credential_secret", RelayCrypto.encode(credential)); enrolled = true }
                    send(finish); ready = true; snapshot(); return
                }
                requests.offer(plain); recorded += plain
                if (dropNextWrite && plain.optString("type") in listOf("submit_prompt", "send_terminal_text", "respond")) { dropNextWrite = false; webSocket.close(1001, "lost acknowledgement"); return }
                respond(plain)
            } catch (error: Throwable) { failures += error; webSocket.close(4401, "fixture rejected") }
        }
        private fun sendLogical(bytes: ByteArray) { if (gateway) RelayChunks.split(bytes).forEach { socket?.send(it.toByteString()) } else socket?.send(bytes.toString(Charsets.UTF_8)) }
        @Synchronized fun send(value: JSONObject) {
            val seq = sendSequence++; val encrypted = RelayCrypto.aes(outgoing, seq, "s2c", value.toString().toByteArray(), true)
            sendLogical(if (gateway) ByteBuffer.allocate(10 + encrypted.size).put(2).put(0).putLong(seq).put(encrypted).array()
                else JSONObject().put("type", "e2ee").put("version", 2).put("sequence", seq).put("ciphertext", RelayCrypto.encode(encrypted)).toString().toByteArray())
        }
        fun snapshot() {
            send(JSONObject().put("type", "push_config").put("protocol", 3).put("inventory", JSONObject().put("state", "ready")).put("capabilities", JSONArray(listOf("conversation_history", "conversation_activity", "workspace_inspection", "workspace_mobile_files_v1", "pane_realtime_delta", "device_management"))).put("agent_profiles", JSONArray().put(JSONObject().put("id", "codex").put("label", "Codex"))))
            send(JSONObject().put("type", "agents").put("agents", JSONArray().put(agent)))
        }
        private fun result(request: JSONObject, data: JSONObject = JSONObject(), error: String? = null) {
            send(JSONObject().put("type", "command_result").put("request_id", request.optString("request_id")).put("ok", error == null).put("phase", "completed").put("data", data).apply { if (error != null) put("error", error) })
        }
        private fun target(request: JSONObject) {
            require(request.getJSONObject("target").toString().let { val t = JSONObject(it); t.getString("pane_id") == "pane-a" && t.getString("terminal_id") == "term-a" && t.getLong("generation") == agent.getLong("generation") && t.getString("agent_session_id") == "session-a" })
        }
        private fun respond(request: JSONObject) {
            val type = request.getString("type")
            if (request.has("pane_id") && type !in listOf("unwatch_pane", "unwatch_conversation", "release_pane_size")) target(request)
            when (type) {
                "refresh_agents" -> snapshot()
                "get_conversation_history" -> result(request, JSONObject().put("available", true).put("state", "ready").put("source_revision", "revision-1").put("subscription_id", "subscription-1").put("sequence", 0).put("entries", JSONArray().put(JSONObject().put("id", "message-0").put("role", "user").put("text", "帮我检查这个项目，先给我一份修改计划。")).put(JSONObject().put("id", "message-1").put("role", "assistant").put("text", "# 原生对话\n\n这条消息来自加密模拟服务器。"))))
                "get_conversation_updates" -> {
                    Thread.sleep(200)
                    val update = emitLiveUpdate && request.optLong("after") == 0L
                    result(request, JSONObject().put("kind", if (update) "update" else "heartbeat").put("source_revision", "revision-1").put("subscription_id", "subscription-1").put("sequence", request.optLong("after") + if (update) 1 else 0)
                        .put("entries", if (update) JSONArray().put(JSONObject().put("id", "message-2").put("role", "assistant").put("text", "实时增量消息已到达")) else JSONArray()))
                }
                "read_pane" -> send(JSONObject().put("type", "pane_content").put("request_id", request.getString("request_id")).put("pane_id", "pane-a").put("target", request.getJSONObject("target")).put("content", "\u001b[32mNative terminal\u001b[0m\n中文输入测试\n$ ").put("format", "ansi").put("content_fingerprint", "screen-1").put("no_echo", false))
                "submit_prompt", "send_terminal_text", "send_keys", "respond", "answer_question" -> result(request, error = if (denyWrites) "reader_denied" else null)
                "workspace_tree" -> result(request, JSONObject().put("root", "/work/project").put("entries", JSONArray().put(JSONObject().put("name", "README.md").put("path", "README.md").put("kind", "file").put("size", text.toByteArray().size))))
                "workspace_file_read" -> result(request, file())
                "workspace_file_write" -> { if (request.getString("expected_revision") != hash(text.toByteArray())) result(request, error = "File changed on the computer") else { text = request.getString("text"); result(request, file()) } }
                "watch_pane", "unwatch_pane", "pane_applied", "release_pane_size", "unwatch_conversation" -> Unit
                "device_list" -> result(request, JSONObject().put("devices", JSONArray()))
                "upload_begin" -> { uploadedBytes.reset(); uploadedName = request.getJSONArray("files").getJSONObject(0).getString("name"); send(JSONObject().put("type", "upload_begin_result").put("request_id", request.getString("request_id")).put("result", JSONObject().put("upload_id", "upload-1").put("chunk_bytes", 32768))) }
                "upload_chunk" -> {
                    val bytes = java.util.Base64.getDecoder().decode(request.getString("data")); require(hash(bytes) == request.getString("sha256")); uploadedBytes.write(bytes)
                    send(JSONObject().put("type", "upload_chunk_result").put("request_id", request.getString("request_id")).put("result", JSONObject().put("next_sequence", request.getInt("sequence") + 1).put("received_bytes", uploadedBytes.size())))
                }
                "upload_finish" -> {
                    require(request.getJSONArray("files").getJSONObject(0).getString("sha256") == hash(uploadedBytes.toByteArray()))
                    send(JSONObject().put("type", "upload_finish_result").put("request_id", request.getString("request_id")).put("result", JSONObject().put("attachments", JSONArray().put(JSONObject().put("ref", (if (uploadedName == "test.txt") "a" else "b").repeat(32)).put("name", uploadedName)))))
                }
                else -> result(request)
            }
        }
        private fun file() = JSONObject().put("path", "README.md").put("kind", "text").put("media_type", "text/markdown").put("data", java.util.Base64.getEncoder().encodeToString(text.toByteArray())).put("size", text.toByteArray().size).put("revision", hash(text.toByteArray()))
    }
    private fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    override fun close() { sessions.forEach { it.socket?.close(1000, "fixture complete") }; server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}
