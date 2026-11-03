package dev.herdr.companion

import org.json.JSONObject
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object RelayCrypto {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
    fun hmac(key: ByteArray, vararg bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); bytes.forEach { update(it) }; doFinal()
    }
    fun hkdf(secret: ByteArray, salt: ByteArray, info: String, size: Int = 32): ByteArray {
        require(size in 1..32)
        return hmac(hmac(salt, secret), info.toByteArray(), byteArrayOf(1)).copyOf(size)
    }
    fun pair(): KeyPair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
    fun publicBytes(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w
        fun padded(value: BigInteger) = value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        return byteArrayOf(4) + padded(point.affineX) + padded(point.affineY)
    }
    fun publicKey(bytes: ByteArray): PublicKey {
        require(bytes.size == 65 && bytes[0] == 4.toByte()) { "Invalid P-256 key" }
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65))), params))
    }
    fun shared(privateKey: PrivateKey, publicKey: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run { init(privateKey); doPhase(publicKey, true); generateSecret() }
    fun aes(key: ByteArray, sequence: Long, direction: String, data: ByteArray, encrypt: Boolean): ByteArray {
        require(sequence >= 0)
        val nonce = ByteBuffer.allocate(12).putInt(0).putLong(sequence).array()
        val aad = "herdr-e2ee-v2 $direction\u0000".toByteArray() + ByteBuffer.allocate(8).putLong(sequence).array()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(data)
        }
    }
}

class RelayHandshake(private val auth: JSONObject, private val keys: KeyPair = RelayCrypto.pair(), private val nonce: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) {
    private val secret = RelayCrypto.decode(auth.getString("secret"))
    private val binding = "herdr-e2ee-v2 auth\u0000${auth.getString("kind")}\u0000${auth.getString("id")}\u0000${auth.getLong("version")}\u0000".toByteArray()
    private val publicBytes = RelayCrypto.publicBytes(keys.public)
    init {
        require(secret.size == 32 && nonce.size == 32 && auth.getLong("version") > 0)
        require(auth.getString("kind") in listOf("invitation", "credential"))
        require(auth.getString("id").length in 1..256 && auth.getString("id").none { it.code < 32 || it.code == 127 })
    }
    fun hello(): JSONObject = JSONObject().put("type", "e2ee_client_hello").put("version", 2)
        .put("auth_kind", auth.getString("kind")).put("auth_id", auth.getString("id")).put("auth_version", auth.getLong("version"))
        .put("locale", "zh-CN").put("nonce", RelayCrypto.encode(nonce)).put("public_key", RelayCrypto.encode(publicBytes))
        .put("proof", RelayCrypto.encode(RelayCrypto.hmac(secret, "herdr-e2ee-v2 client\u0000".toByteArray(), binding, nonce, publicBytes)))
    fun complete(server: JSONObject, binary: Boolean): RelayCipher {
        require(server.getString("type") == "e2ee_server_hello" && server.getInt("version") == 2)
        val serverNonce = RelayCrypto.decode(server.getString("nonce")); require(serverNonce.size == 32)
        val serverPublic = RelayCrypto.decode(server.getString("public_key"))
        val transcript = binding + nonce + publicBytes + serverNonce + serverPublic
        require(MessageDigest.isEqual(RelayCrypto.decode(server.getString("proof")), RelayCrypto.hmac(secret, "herdr-e2ee-v2 server\u0000".toByteArray(), transcript))) { "设备认证失败" }
        val salt = RelayCrypto.hmac(secret, "herdr-e2ee-v2 key\u0000".toByteArray(), transcript)
        val shared = RelayCrypto.shared(keys.private, RelayCrypto.publicKey(serverPublic))
        return RelayCipher(RelayCrypto.hkdf(shared, salt, "herdr-e2ee-v2 c2s"), RelayCrypto.hkdf(shared, salt, "herdr-e2ee-v2 s2c"), binary).also { shared.fill(0); secret.fill(0) }
    }
}

class RelayCipher(private val outgoing: ByteArray, private val incoming: ByteArray, val binary: Boolean) {
    private var sent = 0L
    private var received = 0L
    fun encrypt(text: String): ByteArray {
        val sequence = sent
        require(sequence < Long.MAX_VALUE)
        val encrypted = RelayCrypto.aes(outgoing, sequence, "c2s", text.toByteArray(Charsets.UTF_8), true)
        sent++
        return if (binary) ByteBuffer.allocate(10 + encrypted.size).put(2).put(0).putLong(sequence).put(encrypted).array()
        else JSONObject().put("type", "e2ee").put("version", 2).put("sequence", sequence).put("ciphertext", RelayCrypto.encode(encrypted)).toString().toByteArray()
    }
    fun decrypt(bytes: ByteArray): String {
        val sequence: Long; val encrypted: ByteArray
        if (binary) {
            require(bytes.size >= 26 && bytes[0] == 2.toByte() && bytes[1] == 0.toByte())
            sequence = ByteBuffer.wrap(bytes, 2, 8).long; encrypted = bytes.copyOfRange(10, bytes.size)
        } else {
            val frame = JSONObject(bytes.toString(Charsets.UTF_8))
            require(frame.getString("type") == "e2ee" && frame.getInt("version") == 2)
            sequence = frame.getLong("sequence"); encrypted = RelayCrypto.decode(frame.getString("ciphertext"))
        }
        require(sequence == received && received < Long.MAX_VALUE) { "加密消息序号错误" }
        val result = RelayCrypto.aes(incoming, sequence, "s2c", encrypted, false)
        received++
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(result)).toString()
    }
}

class RelayChunkTimeout : IllegalStateException("分片传输停滞，请重试")

class RelayChunks(private val now: () -> Long = System::nanoTime) {
    private var buffer: ByteArray? = null
    private var offset = 0
    private var startedAt = 0L
    private var lastProgressAt = 0L
    fun push(chunk: ByteArray): ByteArray? {
        require(chunk.size >= 2 && chunk[0] == 1.toByte())
        val flags = chunk[1].toInt(); require(flags in 0..3)
        var header = 2
        if (flags and 1 != 0) {
            require(buffer == null && chunk.size >= 6)
            val size = ByteBuffer.wrap(chunk, 2, 4).int; require(size in 0..MAX_BYTES)
            buffer = ByteArray(size); offset = 0; header = 6; startedAt = now(); lastProgressAt = startedAt
        }
        val target = requireNotNull(buffer)
        val timestamp = now()
        if (timestamp - lastProgressAt >= 30_000_000_000L || timestamp - startedAt >= 120_000_000_000L) throw RelayChunkTimeout()
        require(offset + chunk.size - header <= target.size)
        chunk.copyInto(target, offset, header); offset += chunk.size - header
        if (chunk.size > header) lastProgressAt = timestamp
        if (flags and 2 == 0) return null
        require(offset == target.size)
        buffer = null
        return target
    }
    companion object {
        const val MAX_BYTES = 21 * 1024 * 1024
        fun split(bytes: ByteArray, limit: Int = 262144): List<ByteArray> {
            require(bytes.size <= MAX_BYTES && limit > 6)
            val result = mutableListOf<ByteArray>(); var offset = 0
            do {
                val header = if (offset == 0) 6 else 2
                val length = minOf(bytes.size - offset, limit - header)
                val flags = (if (offset == 0) 1 else 0) or (if (offset + length == bytes.size) 2 else 0)
                val piece = ByteBuffer.allocate(header + length).put(1).put(flags.toByte())
                if (offset == 0) piece.putInt(bytes.size)
                piece.put(bytes, offset, length); result += piece.array(); offset += length
            } while (offset < bytes.size)
            return result
        }
    }
}
