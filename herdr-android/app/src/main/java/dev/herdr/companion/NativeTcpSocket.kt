package dev.herdr.companion

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Record carrier only: authentication and encryption remain in RelayHandshake/RelayCipher. */
internal class NativeTcpSocket(
    endpoint: String,
    private val receive: (ByteArray, Boolean) -> Unit,
    private val failure: () -> Unit
) : WebSocket {
    private data class Record(val type: Int, val data: ByteArray)
    private val uri = URI(endpoint)
    private val closed = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val queuedBytes = AtomicInteger()
    private val queueLock = Any()
    private val outbound = LinkedBlockingQueue<Record>(64)
    private val socket = Socket()
    private val deadlines = ScheduledThreadPoolExecutor(1) { work ->
        Thread(work, "herdr-tcp-write-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    @Volatile private var writer: Thread? = null
    private val reader = Thread({ readLoop() }, "herdr-tcp-reader").apply { isDaemon = true }

    init {
        require(uri.scheme == "tcp" && !uri.host.isNullOrBlank() && uri.port in 1..65535 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path in listOf("", "/"))
        reader.start()
    }
    private fun readLoop() {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(uri.host.removePrefix("[").removeSuffix("]"), uri.port), 10000)
            val output = DataOutputStream(socket.getOutputStream())
            output.write("HRDRTCP1".toByteArray(Charsets.US_ASCII))
            output.writeByte(2)
            output.flush()
            writer = Thread({ writeLoop(output) }, "herdr-tcp-writer").apply { isDaemon = true; start() }
            val input = DataInputStream(socket.getInputStream())
            while (!closed.get()) {
                socket.soTimeout = 0
                val type = input.readUnsignedByte()
                socket.soTimeout = 30000
                val length = input.readInt()
                require(type in 1..4 && length >= 0 && length <= if (type >= 3) 8 else MAX_RECORD)
                if (type >= 3) require(length == 8)
                val data = ByteArray(length)
                input.readFully(data)
                when (type) {
                    1, 2 -> receive(data, type == 2)
                    3 -> check(enqueue(4, data))
                    4 -> Unit
                }
            }
        } catch (_: Exception) { fail() }
        finally { cancel() }
    }
    private fun writeLoop(output: DataOutputStream) {
        try {
            while (!closed.get()) {
                val record = outbound.poll(1, TimeUnit.SECONDS) ?: continue
                try {
                    val timeout = deadlines.schedule({ fail() }, 20, TimeUnit.SECONDS)
                    try {
                        output.writeByte(record.type)
                        output.writeInt(record.data.size)
                        output.write(record.data)
                        output.flush()
                    } finally { timeout.cancel(false) }
                } finally { queuedBytes.addAndGet(-record.data.size) }
            }
        } catch (_: Exception) { fail() }
    }
    private fun enqueue(type: Int, bytes: ByteArray): Boolean = synchronized(queueLock) {
        if (closed.get() || bytes.size > MAX_RECORD || queuedBytes.get() + bytes.size > MAX_QUEUED) return@synchronized false
        queuedBytes.addAndGet(bytes.size)
        if (!outbound.offer(Record(type, bytes))) {
            queuedBytes.addAndGet(-bytes.size)
            return@synchronized false
        }
        true
    }
    private fun fail() {
        if (!closed.get() && failed.compareAndSet(false, true)) { cancel(); failure() }
    }
    // Metadata for WebSocket's interface only; this request is never executed as HTTP.
    override fun request(): Request = Request.Builder().url("http://${uri.rawAuthority}/").build()
    override fun queueSize(): Long = queuedBytes.get().coerceAtLeast(0).toLong()
    override fun send(text: String): Boolean = enqueue(1, text.toByteArray(Charsets.UTF_8))
    override fun send(bytes: ByteString): Boolean = enqueue(2, bytes.toByteArray())
    override fun close(code: Int, reason: String?): Boolean { cancel(); return true }
    override fun cancel() {
        synchronized(queueLock) {
            if (!closed.compareAndSet(false, true)) return
            val pending = mutableListOf<Record>()
            outbound.drainTo(pending)
            queuedBytes.addAndGet(-pending.sumOf { it.data.size })
        }
        runCatching { socket.close() }
        writer?.interrupt()
        reader.interrupt()
        deadlines.shutdownNow()
    }
    companion object {
        const val MAX_RECORD = 1024 * 1024 + 6
        private const val MAX_QUEUED = 4 * 1024 * 1024
    }
}
