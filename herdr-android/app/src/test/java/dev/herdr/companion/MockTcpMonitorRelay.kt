package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real TCP + gateway framing + E2EE, containing only generated metadata fixtures. */
class MockTcpMonitorRelay : AutoCloseable {
    private val server = ServerSocket(0)
    val secret = ByteArray(32) { 17 }
    private val rendezvous = ByteArray(32) { 18 }
    private val relayId = RelayCrypto.encode(ByteArray(16) { 19 })
    private val stopped = AtomicBoolean()
    val connected = AtomicInteger()
    val requests = CopyOnWriteArrayList<String>()
    val failures = CopyOnWriteArrayList<Throwable>()
    val states = ConcurrentHashMap<String, String>().apply { put("a", "working"); put("b", "working") }
    val turn = ConcurrentHashMap<String, String>().apply { put("a", "turn-a"); put("b", "turn-b") }
    val peers = CopyOnWriteArrayList<Socket>()
    private val workers = CopyOnWriteArrayList<Thread>()
    val agents: JSONArray get() = JSONArray().put(agent("a")).put(agent("b"))
    fun agent(id: String) = JSONObject().put("pane_id", id).put("terminal_id", "terminal-$id").put("agent_session_id", "session-$id").put("generation", 1)
        .put("server_session_id", "primary").put("workspace_id", "project").put("cwd", "/fixture/project").put("agent", "codex").put("name", "Codex").put("tab_label", "writer-$id").put("status", "working")
    fun state(): JSONObject = JSONObject().put("relays", JSONArray().put(JSONObject().put("id", "fixture").put("label", "模拟电脑").put("transport", "hybrid")
        .put("gatewayUrl", "tcp://127.0.0.1:${server.localPort}").put("gatewayRelayId", relayId).put("rendezvousKey", RelayCrypto.encode(rendezvous)).put("paired", true)))
        .put("auth", JSONObject().put("version", 1).put("relays", JSONObject().put("fixture", JSONObject().put("kind", "credential").put("id", "credential-a").put("version", 1).put("deviceId", "device-a").put("role", "reader").put("secret", RelayCrypto.encode(secret)))))
    private val acceptor = Thread({
        while (!stopped.get()) {
            val peer = try { server.accept() } catch (_: Exception) { break }
            peers += peer; connected.incrementAndGet()
            val worker = Thread({
                try { session(peer) } catch (e: Throwable) { if (!stopped.get() && !peer.isClosed) failures += e }
                finally { runCatching { peer.close() }; peers.remove(peer) }
            }, "mock-monitor-relay").apply { isDaemon = true; start() }
            workers += worker
        }
    }, "mock-monitor-accept").apply { isDaemon = true; start() }
    fun disconnect() { peers.forEach { it.close() } }
    private fun record(out: DataOutputStream, kind: Int, bytes: ByteArray) { out.writeByte(kind); out.writeInt(bytes.size); out.write(bytes); out.flush() }
    private fun read(input: DataInputStream): Pair<Int, ByteArray> {
        val kind = input.readUnsignedByte(); val size = input.readInt(); require(size in 0..NativeTcpSocket.MAX_RECORD)
        return kind to ByteArray(size).also(input::readFully)
    }
    private fun session(peer: Socket) {
        peer.soTimeout = 20000
        val input = DataInputStream(peer.getInputStream()); val out = DataOutputStream(peer.getOutputStream())
        val preface = ByteArray(9).also(input::readFully); check(preface.contentEquals("HRDRTCP1".toByteArray() + byteArrayOf(2)))
        val challenge = ByteArray(32) { 21 }
        record(out, 1, JSONObject().put("type", "gateway_hello").put("proto", 1).put("nonce", RelayCrypto.encode(challenge)).toString().toByteArray())
        val routing = read(input); require(routing.first == 1); val connect = JSONObject(routing.second.toString(Charsets.UTF_8))
        require(connect.getString("relay_id") == relayId)
        require(MessageDigest.isEqual(RelayCrypto.decode(connect.getString("proof")), RelayCrypto.hmac(rendezvous, "herdr-gw-connect\u0000".toByteArray(), relayId.toByteArray(), challenge)))
        record(out, 1, "{\"type\":\"ready\"}".toByteArray())
        fun logical(bytes: ByteArray) { RelayChunks.split(bytes).forEach { record(out, 2, it) } }
        fun next(): ByteArray {
            val chunks = RelayChunks()
            while (true) { val r = read(input); require(r.first == 2); chunks.push(r.second)?.let { return it } }
        }
        val hello = JSONObject(next().toString(Charsets.UTF_8)); require(hello.getString("auth_kind") == "credential" && hello.getString("auth_id") == "credential-a")
        val binding = "herdr-e2ee-v2 auth\u0000credential\u0000credential-a\u00001\u0000".toByteArray()
        val cn = RelayCrypto.decode(hello.getString("nonce")); val cp = RelayCrypto.decode(hello.getString("public_key"))
        require(MessageDigest.isEqual(RelayCrypto.decode(hello.getString("proof")), RelayCrypto.hmac(secret, "herdr-e2ee-v2 client\u0000".toByteArray(), binding, cn, cp)))
        val pair = RelayCrypto.pair(); val sn = ByteArray(32) { 22 }; val sp = RelayCrypto.publicBytes(pair.public)
        val transcript = binding + cn + cp + sn + sp
        val shared = RelayCrypto.shared(pair.private, RelayCrypto.publicKey(cp)); val salt = RelayCrypto.hmac(secret, "herdr-e2ee-v2 key\u0000".toByteArray(), transcript)
        val incoming = RelayCrypto.hkdf(shared, salt, "herdr-e2ee-v2 c2s"); val outgoing = RelayCrypto.hkdf(shared, salt, "herdr-e2ee-v2 s2c")
        logical(JSONObject().put("type", "e2ee_server_hello").put("version", 2).put("nonce", RelayCrypto.encode(sn)).put("public_key", RelayCrypto.encode(sp))
            .put("proof", RelayCrypto.encode(RelayCrypto.hmac(secret, "herdr-e2ee-v2 server\u0000".toByteArray(), transcript))).toString().toByteArray())
        var sent = 0L; var received = 0L
        fun send(value: JSONObject) {
            val seq = sent++; val ciphertext = RelayCrypto.aes(outgoing, seq, "s2c", value.toString().toByteArray(), true)
            logical(ByteBuffer.allocate(10 + ciphertext.size).put(2).put(0).putLong(seq).put(ciphertext).array())
        }
        fun receive(): JSONObject {
            val frame = next(); require(frame[0] == 2.toByte() && frame[1] == 0.toByte())
            val seq = ByteBuffer.wrap(frame, 2, 8).long; require(seq == received++)
            return JSONObject(RelayCrypto.aes(incoming, seq, "c2s", frame.copyOfRange(10, frame.size), false).toString(Charsets.UTF_8))
        }
        require(receive().getString("type") == "e2ee_client_finish")
        send(JSONObject().put("type", "e2ee_server_finish").put("version", 2).put("device_id", "device-a").put("credential_id", "credential-a").put("credential_version", 1).put("role", "reader").put("locale", "zh"))
        send(JSONObject().put("type", "push_config").put("protocol", 3).put("inventory", JSONObject().put("state", "ready")).put("capabilities", JSONArray().put("agent_run_state_v1")))
        send(JSONObject().put("type", "agents").put("agents", agents))
        send(JSONObject().put("type", "workspaces").put("workspaces", JSONArray().put(JSONObject().put("workspace_id", "project").put("cwd", "/fixture/project").put("label", "模拟工作空间"))))
        while (!stopped.get()) {
            val request = receive(); require(request.getString("type") == "get_agent_run_state") { "background monitor must not fetch history, terminal or mutate agents" }
            val pane = request.getString("pane_id"); require(pane in states.keys)
            val target = request.getJSONObject("target"); require(target.getString("agent_session_id") == "session-$pane" && target.getString("terminal_id") == "terminal-$pane")
            requests += pane
            val status = states.getValue(pane)
            send(JSONObject().put("type", "command_result").put("request_id", request.getString("request_id")).put("ok", true).put("phase", "completed")
                .put("data", JSONObject().put("source", "codex_runtime").put("status", status).put("turn_id", turn.getValue(pane))
                    .put("timing", "turn_elapsed").put("elapsed_ms", 42000).put("ticking", status == "working")))
        }
    }
    override fun close() { stopped.set(true); server.close(); disconnect(); acceptor.join(1000); workers.forEach { it.join(1000) } }
}
