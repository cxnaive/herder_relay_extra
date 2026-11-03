package dev.herdr.companion

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DirectFallbackTest {
    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    private fun get(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    @Test fun invalidDirectFragmentFallsBackWithoutRetiringAuthenticatedGatewayOrReplayingWrites() {
        val serial = Executors.newSingleThreadScheduledExecutor(); val client = OkHttpClient()
        val statuses = mutableListOf<Triple<String, String, Boolean>>(); val sends = mutableListOf<String>()
        var receiver: ((ByteArray) -> Unit)? = null; var directClosed = 0
        val relay = JSONObject().put("transport", "hybrid").put("gatewayUrl", "wss://127.0.0.1")
        val conn = NativeRelayConnection(relay, client, serial, { null }, { _, _ -> }, {}, { a, b, c -> statuses += Triple(a, b, c) },
            { _, _, onFrame, _, _ -> receiver = onFrame; object : NativeRelayConnection.DirectChannel {
                override fun signal(value: JSONObject) {}
                override fun send(bytes: ByteArray) = true
                override fun close() { directClosed++ }
            } })
        val socket = object : WebSocket {
            override fun request() = Request.Builder().url("https://127.0.0.1").build()
            override fun queueSize() = 0L
            override fun send(text: String): Boolean { sends += text; return true }
            override fun send(bytes: ByteString): Boolean { sends += "encrypted-fragment"; return true }
            override fun close(code: Int, reason: String?) = true
            override fun cancel() { error("Authenticated gateway was incorrectly cancelled") }
        }
        try {
            set(conn, "active", true); set(conn, "phase", "ready"); set(conn, "socket", socket); set(conn, "cipher", RelayCipher(ByteArray(32), ByteArray(32), true))
            conn.javaClass.getDeclaredMethod("startDirect").apply { isAccessible = true }.invoke(conn)
            set(conn, "directReady", true)
            receiver!!(byteArrayOf(99, 3)); serial.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals("ready", get(conn, "phase")); assertEquals(true, get(conn, "active")); assertSame(socket, get(conn, "socket"))
            assertEquals(1, directClosed); assertEquals(false, get(conn, "directReady")); assertTrue(statuses.none { it.third })
            assertEquals("gateway", statuses.last().second)
            val count = sends.size; receiver!!(byteArrayOf(99, 3)); serial.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(count, sends.size); assertEquals(1, directClosed)
            assertTrue(sends.isNotEmpty())
        } finally { serial.shutdownNow(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
