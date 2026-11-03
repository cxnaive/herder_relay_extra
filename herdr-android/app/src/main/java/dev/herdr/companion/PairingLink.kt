package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

data class PairingLink(val relay: JSONObject, val invitation: JSONObject) {
    companion object {
        fun parse(value: String, now: Long = System.currentTimeMillis()): PairingLink {
            require(value.length <= 16384)
            val uri = URI(value.trim())
            val nativeTCP = uri.scheme == "herdr-tcp"
            require((nativeTCP || uri.scheme == "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "请使用完整 HTTPS 或 herdr-tcp 配对链接" }
            if (nativeTCP) require(uri.port in 1..65535 && uri.rawQuery == null && uri.path in listOf("", "/")) { "TCP 配对需要明确的 IP/主机和端口" }
            val parameters = linkedMapOf<String, String>()
            uri.rawFragment.orEmpty().split('&').forEach {
                val p = it.split('=', limit = 2)
                val key = URLDecoder.decode(p[0], "UTF-8")
                require(!parameters.containsKey(key)) { "配对链接包含重复参数" }
                parameters[key] = URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
            }
            require(!parameters.containsKey("gateway")) { "网关链接不完整，请重新生成" }
            val setup = parameters["setup"].orEmpty()
            val invitation = JSONObject().put("kind", "invitation")
            val relay = JSONObject().put("id", UUID.randomUUID().toString()).put("label", parameters["label"].orEmpty().trim().take(48).ifBlank { uri.host })
                .put("token", "").put("paired", true)
            val gateways = if (nativeTCP) {
                require(!parameters.containsKey("relay") && !parameters.containsKey("gateways")) { "TCP 配对地址必须直接写在链接主机与端口中" }
                listOf("tcp://${uri.rawAuthority}")
            } else parameters["gateways"]?.split(',')?.filter { it.isNotBlank() }?.map(::socketOrigin)
            if (gateways != null) {
                require(gateways.isNotEmpty())
                relay.put("transport", "hybrid").put("url", "").put("gatewayUrl", gateways.first()).put("gatewayUrls", JSONArray(gateways))
            } else relay.put("url", socketOrigin(parameters["relay"] ?: "wss://${uri.rawAuthority}"))
            if (parameters.containsKey("invite")) {
                val id = parameters["invite"].orEmpty(); val expires = parameters["invite_expires"]?.toLongOrNull() ?: 0
                val version = parameters["invite_version"]?.toLongOrNull() ?: 0
                require(id.matches(Regex("[A-Za-z0-9_-]{16,128}")) && version > 0 && RelayCrypto.decode(setup).size == 32) { "设备邀请不完整" }
                require(expires > now) { "邀请已过期，请重新生成" }
                invitation.put("id", id).put("version", version).put("secret", setup).put("expiresAt", expires)
                if (gateways != null) {
                    val rendezvous = parameters["rendezvous"].orEmpty(); val relayId = parameters["relay_id"].orEmpty()
                    require(RelayCrypto.decode(rendezvous).size == 32 && RelayCrypto.decode(relayId).size == 16)
                    relay.put("rendezvousKey", rendezvous).put("gatewayRelayId", relayId)
                }
            } else {
                require(!nativeTCP) { "TCP 模式只接受限时、一次性的设备邀请，不接受主密钥配对" }
                val secret = setup.toByteArray(Charsets.UTF_8); require(secret.size == 32) { "配对密钥必须是 32 字节" }
                invitation.put("id", "bootstrap").put("version", 1).put("secret", RelayCrypto.encode(secret)).put("expiresAt", now + 600000)
                if (gateways != null) relay.put("gatewayRelayId", RelayCrypto.encode(RelayCrypto.hkdf(secret, "herdr-gateway-v1".toByteArray(), "herdr-gw-id", 16)))
                    .put("rendezvousKey", RelayCrypto.encode(RelayCrypto.hkdf(secret, "herdr-gateway-v1".toByteArray(), "herdr-gw-auth")))
            }
            return PairingLink(relay, invitation)
        }
        private fun socketOrigin(value: String): String {
            val uri = URI(value)
            require(uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path in listOf("", "/")) { "连接地址必须是有效 WSS origin" }
            require(uri.port == -1 || uri.port in 1..65535)
            return "wss://${uri.rawAuthority}"
        }
    }
}
