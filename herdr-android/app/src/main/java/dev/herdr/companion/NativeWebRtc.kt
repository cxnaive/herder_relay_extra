package dev.herdr.companion

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.net.URI
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class NativeWebRtc(context: Context, config: JSONObject, private val serial: ScheduledExecutorService,
    private val sendSignal: (JSONObject) -> Unit, private val onFrame: (ByteArray) -> Unit,
    private val onOpen: () -> Unit, private val onClose: () -> Unit) : NativeRelayConnection.DirectChannel {
    private val requestId = UUID.randomUUID().toString()
    @Volatile private var closed = false
    private var answered = false
    private val candidates = mutableListOf<IceCandidate>()
    private val outbound = ArrayDeque<ByteArray>()
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    init {
        initialize(context)
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val port = config.optInt("stun_port")
        val servers = if (port > 0) listOf(PeerConnection.IceServer.builder("stun:${URI(config.getString("gatewayUrl")).host}:$port").createIceServer()) else emptyList()
        peer = factory.createPeerConnection(PeerConnection.RTCConfiguration(servers), object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) { if (state in listOf(PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED, PeerConnection.IceConnectionState.DISCONNECTED)) dispatch { onClose() } }
            override fun onIceConnectionReceivingChange(value: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate) { sendSignal(JSONObject().put("type", "webrtc_ice").put("request_id", requestId).put("candidate", candidate.sdp).put("sdp_mid", candidate.sdpMid).put("sdp_mline_index", candidate.sdpMLineIndex)) }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })
        channel = peer?.createDataChannel("herdr-dc-v1", DataChannel.Init().apply { ordered = true })
        channel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) { dispatch { flush() } }
            override fun onStateChange() { val state = channel?.state(); if (state == DataChannel.State.OPEN) onOpen() else if (state == DataChannel.State.CLOSED && !closed) onClose() }
            override fun onMessage(buffer: DataChannel.Buffer) { if (!buffer.binary) { onClose(); return }; val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes); onFrame(bytes) }
        })
        peer?.createOffer(observer(onCreate = { offer -> peer?.setLocalDescription(observer(onSet = { sendSignal(JSONObject().put("type", "webrtc_offer").put("request_id", requestId).put("sdp", offer.description)) }), offer) }), MediaConstraints())
    }
    override fun signal(value: JSONObject) {
        if (closed || value.optString("request_id") != requestId) return
        when (value.optString("type")) {
            "webrtc_answer" -> peer?.setRemoteDescription(observer(onSet = { dispatch { answered = true; candidates.forEach { peer?.addIceCandidate(it) }; candidates.clear() } }), SessionDescription(SessionDescription.Type.ANSWER, value.getString("sdp")))
            "webrtc_ice" -> { val candidate = IceCandidate(value.optString("sdp_mid"), value.optInt("sdp_mline_index"), value.getString("candidate")); if (answered) peer?.addIceCandidate(candidate) else candidates += candidate }
            "webrtc_closed" -> onClose()
        }
    }
    override fun send(bytes: ByteArray): Boolean {
        if (closed || channel?.state() != DataChannel.State.OPEN || outbound.sumOf { it.size } > RelayChunks.MAX_BYTES) return false
        outbound.addLast(bytes); flush(); return true
    }
    private fun flush() {
        val dc = channel ?: return
        while (!closed && outbound.isNotEmpty() && dc.bufferedAmount() < 4 * 1024 * 1024) {
            if (!dc.send(DataChannel.Buffer(ByteBuffer.wrap(outbound.removeFirst()), true))) { onClose(); return }
        }
        if (!closed && outbound.isNotEmpty()) serial.schedule({ flush() }, 50, TimeUnit.MILLISECONDS)
    }
    private fun observer(onCreate: (SessionDescription) -> Unit = {}, onSet: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onCreate(description)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(reason: String?) = onClose()
        override fun onSetFailure(reason: String?) = onClose()
    }
    private fun dispatch(action: () -> Unit) {
        if (closed || serial.isShutdown) return
        try { serial.execute { if (!closed) action() } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
    override fun close() {
        if (closed) return; closed = true
        sendSignal(JSONObject().put("type", "webrtc_close").put("request_id", requestId))
        channel?.unregisterObserver(); channel?.close(); channel?.dispose(); channel = null; peer?.close(); peer?.dispose(); peer = null; factory.dispose(); outbound.clear()
    }
    companion object {
        private var initialized = false
        @Synchronized private fun initialize(context: Context) { if (!initialized) { PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()); initialized = true } }
    }
}
