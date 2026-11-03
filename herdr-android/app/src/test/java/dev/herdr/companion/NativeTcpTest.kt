package dev.herdr.companion

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.*

class NativeTcpTest {
    private val secret = ByteArray(32) { 9 }
    private val rendezvous = ByteArray(32) { 6 }
    private val relayId = RelayCrypto.encode(ByteArray(16) { 7 })
    private fun invitation(host: String = "127.0.0.1:8377") = "herdr-tcp://$host/#setup=${RelayCrypto.encode(secret)}&invite=${"i".repeat(20)}&invite_version=1&invite_expires=9000000000000&relay_id=$relayId&rendezvous=${RelayCrypto.encode(rendezvous)}"
    @Test fun nativePairingRequiresInvitationAndSingleExplicitAddress() {
        for (host in listOf("127.0.0.1:8377", "[::1]:8377")) {
            val p = PairingLink.parse(invitation(host))
            assertEquals("tcp://$host", p.relay.getString("gatewayUrl"))
            assertEquals("", p.relay.getString("token"))
            assertEquals("invitation", p.invitation.getString("kind"))
        }
        for (link in listOf(
            invitation("127.0.0.1"), invitation("user:pass@127.0.0.1:8377"), invitation("127.0.0.1:0"),
            invitation() + "&relay=wss%3A%2F%2Fother.example", invitation() + "&gateways=wss%3A%2F%2Fother.example",
            invitation() + "&setup=bad", invitation().replace("9000000000000", "1"),
            "herdr-tcp://127.0.0.1:8377/#setup=0123456789abcdef0123456789abcdef"
        )) assertThrows(IllegalArgumentException::class.java) { PairingLink.parse(link) }
    }
    private fun record(out: DataOutputStream, kind: Int, bytes: ByteArray, fragmentHeader: Boolean = false) {
        val header = ByteBuffer.allocate(5).put(kind.toByte()).putInt(bytes.size).array()
        if (fragmentHeader) header.forEach { out.write(it.toInt()); out.flush() } else out.write(header)
        out.write(bytes); out.flush()
    }
    private fun read(input: DataInputStream): Pair<Int, ByteArray> {
        val kind = input.readUnsignedByte(); val n = input.readInt()
        require(n in 0..NativeTcpSocket.MAX_RECORD)
        return kind to ByteArray(n).also { input.readFully(it) }
    }
    private fun preface(input: DataInputStream) {
        val bytes = ByteArray(9); input.readFully(bytes)
        assertArrayEquals("HRDRTCP1".toByteArray() + byteArrayOf(2), bytes)
    }
    @Test fun nativeCarrierHandlesFragmentedHeaderPingAndRejectsOversizeBeforeAllocation() {
        ServerSocket(0).use { server ->
            val received = LinkedBlockingQueue<Pair<ByteArray, Boolean>>()
            val failed = CountDownLatch(1)
            val tcp = NativeTcpSocket("tcp://127.0.0.1:${server.localPort}", { b, binary -> received.offer(b to binary) }, { failed.countDown() })
            try { server.accept().use { peer ->
                peer.soTimeout = 5000
                val input = DataInputStream(peer.getInputStream()); val out = DataOutputStream(peer.getOutputStream())
                preface(input)
                record(out, 3, ByteArray(8) { 4 }, true)
                val pong = read(input); assertEquals(4, pong.first); assertArrayEquals(ByteArray(8) { 4 }, pong.second)
                val body = ByteArray(400000) { (it % 251).toByte() }; record(out, 2, body, true)
                val got = received.poll(5, TimeUnit.SECONDS) ?: error("no data")
                assertTrue(got.second); assertArrayEquals(body, got.first)
                out.writeByte(2); out.writeInt(Int.MAX_VALUE); out.flush()
                assertTrue(failed.await(5, TimeUnit.SECONDS))
                assertFalse(tcp.send("after close"))
            } } finally { tcp.cancel() }
        }
    }
    private class Session(val peer: Socket, val input: DataInputStream, val out: DataOutputStream) {
        var incoming = ByteArray(0); var outgoing = ByteArray(0); var sent = 0L; var received = 0L
    }
    private fun logical(s: Session, bytes: ByteArray) { RelayChunks.split(bytes).forEach { record(s.out, 2, it) } }
    private fun readLogical(s: Session): ByteArray {
        val chunks = RelayChunks()
        while (true) { val (kind, data) = read(s.input); require(kind == 2); chunks.push(data)?.let { return it } }
    }
    private fun encrypted(s: Session, value: JSONObject, tamper: Boolean = false) {
        val seq = s.sent++; val ciphertext = RelayCrypto.aes(s.outgoing, seq, "s2c", value.toString().toByteArray(), true)
        if (tamper) ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        logical(s, ByteBuffer.allocate(10 + ciphertext.size).put(2).put(0).putLong(seq).put(ciphertext).array())
    }
    private fun decrypted(s: Session): JSONObject {
        val frame = readLogical(s); require(frame[0] == 2.toByte() && frame[1] == 0.toByte())
        val seq = ByteBuffer.wrap(frame, 2, 8).long; require(seq == s.received++)
        return JSONObject(RelayCrypto.aes(s.incoming, seq, "c2s", frame.copyOfRange(10, frame.size), false).toString(Charsets.UTF_8))
    }
    private fun handshake(s: Session, badProof: Boolean, invitation: Boolean = false) {
        preface(s.input)
        val challenge = ByteArray(32) { 4 }
        record(s.out, 1, JSONObject().put("type", "gateway_hello").put("proto", 1).put("nonce", RelayCrypto.encode(challenge)).toString().toByteArray(), true)
        val connect = read(s.input); require(connect.first == 1)
        val routing = JSONObject(connect.second.toString(Charsets.UTF_8))
        assertEquals(relayId, routing.getString("relay_id"))
        assertArrayEquals(RelayCrypto.hmac(rendezvous, "herdr-gw-connect\u0000".toByteArray(), relayId.toByteArray(), challenge), RelayCrypto.decode(routing.getString("proof")))
        record(s.out, 1, "{\"type\":\"ready\"}".toByteArray())
        val hello = JSONObject(readLogical(s).toString(Charsets.UTF_8))
        val kind = if (invitation) "invitation" else "credential"
        val id = if (invitation) "invitation-a" else "credential-a"
        assertEquals(id, hello.getString("auth_id")); assertEquals(kind, hello.getString("auth_kind"))
        val binding = "herdr-e2ee-v2 auth\u0000$kind\u0000$id\u00001\u0000".toByteArray()
        val cn = RelayCrypto.decode(hello.getString("nonce")); val cp = RelayCrypto.decode(hello.getString("public_key"))
        assertTrue(MessageDigest.isEqual(RelayCrypto.decode(hello.getString("proof")), RelayCrypto.hmac(secret, "herdr-e2ee-v2 client\u0000".toByteArray(), binding, cn, cp)))
        val pair = RelayCrypto.pair(); val sn = ByteArray(32) { 5 }; val sp = RelayCrypto.publicBytes(pair.public)
        val transcript = binding + cn + cp + sn + sp
        val shared = RelayCrypto.shared(pair.private, RelayCrypto.publicKey(cp))
        val salt = RelayCrypto.hmac(secret, "herdr-e2ee-v2 key\u0000".toByteArray(), transcript)
        s.incoming = RelayCrypto.hkdf(shared, salt, "herdr-e2ee-v2 c2s"); s.outgoing = RelayCrypto.hkdf(shared, salt, "herdr-e2ee-v2 s2c")
        val proof = RelayCrypto.hmac(secret, "herdr-e2ee-v2 server\u0000".toByteArray(), transcript)
        if (badProof) proof[0] = (proof[0].toInt() xor 1).toByte()
        logical(s, JSONObject().put("type", "e2ee_server_hello").put("version", 2).put("nonce", RelayCrypto.encode(sn)).put("public_key", RelayCrypto.encode(sp)).put("proof", RelayCrypto.encode(proof)).toString().toByteArray())
        if (badProof) return
        assertEquals("e2ee_client_finish", decrypted(s).getString("type"))
        encrypted(s, JSONObject().put("type", "e2ee_server_finish").put("version", 2).put("device_id", "device-a").put("credential_id", "credential-a").put("credential_version", 1).put("role", "controller").put("locale", "zh").apply { if (invitation) put("credential_secret", RelayCrypto.encode(secret)) })
    }
    private fun exercise(mode: String) {
        ServerSocket(0).use { server ->
            server.soTimeout = 8000
            val serial = Executors.newSingleThreadScheduledExecutor()
            val serverThread = Executors.newSingleThreadExecutor()
            val client = OkHttpClient()
            val messages = LinkedBlockingQueue<JSONObject>(); val fatal = CountDownLatch(1)
            val relay = PairingLink.parse(invitation("127.0.0.1:${server.localPort}")).relay
            val auth = JSONObject().put("kind", if (mode == "invitation") "invitation" else "credential").put("id", if (mode == "invitation") "invitation-a" else "credential-a").put("expiresAt", System.currentTimeMillis() + 600000).put("version", 1).put("secret", RelayCrypto.encode(secret)).put("deviceId", "device-a")
            val connection = NativeRelayConnection(relay, client, serial, { JSONObject(auth.toString()) }, { presented, finish ->
                if (presented.getString("kind") == "invitation") auth.put("kind", "credential").put("id", finish.getString("credential_id")).put("secret", finish.getString("credential_secret"))
            }, { messages.offer(it) }, { _, _, isFatal -> if (isFatal) fatal.countDown() }, { _, _, _, _, _ -> error("TCP must not open UDP/WebRTC") })
            val body = "历史图像 markdown ".repeat(40000)
            val work = serverThread.submit {
                repeat(if (mode in listOf("reconnect", "invitation")) 2 else 1) { attempt ->
                    server.accept().use { peer ->
                        peer.soTimeout = 8000
                        val s = Session(peer, DataInputStream(peer.getInputStream()), DataOutputStream(peer.getOutputStream()))
                        handshake(s, mode == "proof", mode == "invitation" && attempt == 0)
                        if (mode == "invitation" && attempt == 0) { assertEquals(-1, s.input.read()); return@use }
                        if (mode == "proof") { assertEquals(-1, s.input.read()); return@use }
                        if (mode == "cipher") { encrypted(s, JSONObject().put("type", "never-deliver"), true); assertEquals(-1, s.input.read()); return@use }
                        encrypted(s, JSONObject().put("type", "push_config").put("capabilities", JSONArray().put("webrtc_direct")))
                        encrypted(s, JSONObject().put("type", "history").put("body", body))
                        val command = decrypted(s); assertEquals("probe", command.getString("type"))
                    }
                }
            }
            try {
                connection.connect()
                if (mode == "proof" || mode == "cipher") {
                    assertTrue("no fatal authentication error", fatal.await(8, TimeUnit.SECONDS))
                    assertTrue(messages.isEmpty())
                } else repeat(if (mode == "reconnect") 2 else 1) {
                    assertEquals("push_config", messages.poll(8, TimeUnit.SECONDS)?.optString("type"))
                    assertEquals(body, messages.poll(8, TimeUnit.SECONDS)?.optString("body"))
                    connection.send(JSONObject().put("type", "probe")) { error("send rejected") }
                }
                work.get(12, TimeUnit.SECONDS)
            } finally {
                connection.close(); serial.submit {}.get(5, TimeUnit.SECONDS)
                serial.shutdownNow(); serverThread.shutdownNow()
                client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
            }
        }
    }
    @Test fun nativeInvitationImmediatelyConfirmsPersistedCredential() = exercise("invitation")
    @Test fun nativeEndToEndEncryptionAndMultiChunkHistory() = exercise("valid")
    @Test fun nativeReconnectKeepsCredentialAndFreshEncryptionSession() = exercise("reconnect")
    @Test fun nativeRejectsTamperedServerProof() = exercise("proof")
    @Test fun nativeRejectsTamperedCiphertext() = exercise("cipher")
}
