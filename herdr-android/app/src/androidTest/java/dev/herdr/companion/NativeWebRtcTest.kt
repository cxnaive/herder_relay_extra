package dev.herdr.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeWebRtcTest {
    @Test fun nativeDataChannelNegotiatesAndRoundTripsBinaryFrames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val serial = Executors.newSingleThreadScheduledExecutor()
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val completed = CountDownLatch(1); val connected = CountDownLatch(1)
        val payload = ByteArray(12000) { (it % 251).toByte() }
        var result: ByteArray? = null
        var native: NativeWebRtc? = null
        var remote: PeerConnection? = null
        var remoteChannel: DataChannel? = null
        var requestId = ""
        var remoteDescription = false
        val waiting = mutableListOf<IceCandidate>()
        fun observer(onCreated: (SessionDescription) -> Unit = {}, onSet: () -> Unit = {}) = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = onCreated(description)
            override fun onSetSuccess() = onSet()
            override fun onCreateFailure(reason: String?) { completed.countDown() }
            override fun onSetFailure(reason: String?) { completed.countDown() }
        }
        remote = factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(value: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onIceCandidate(candidate: IceCandidate) { serial.execute { native?.signal(JSONObject().put("type", "webrtc_ice").put("request_id", requestId).put("candidate", candidate.sdp).put("sdp_mid", candidate.sdpMid).put("sdp_mline_index", candidate.sdpMLineIndex)) } }
            override fun onDataChannel(channel: DataChannel) {
                remoteChannel = channel
                channel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer) { val data = ByteArray(buffer.data.remaining()); buffer.data.get(data); channel.send(DataChannel.Buffer(ByteBuffer.wrap(data), true)) }
                })
            }
        })
        try {
            serial.submit {
                native = NativeWebRtc(context, JSONObject().put("gatewayUrl", "wss://localhost").put("stun_port", 0), serial, { signal -> serial.execute {
                    requestId = signal.getString("request_id")
                    when (signal.getString("type")) {
                        "webrtc_offer" -> remote!!.setRemoteDescription(observer(onSet = {
                            serial.execute {
                                remoteDescription = true; waiting.forEach { remote!!.addIceCandidate(it) }; waiting.clear()
                                remote!!.createAnswer(observer(onCreated = { answer ->
                                    remote!!.setLocalDescription(observer(onSet = { serial.execute { native?.signal(JSONObject().put("type", "webrtc_answer").put("request_id", requestId).put("sdp", answer.description)) } }), answer)
                                }), MediaConstraints())
                            }
                        }), SessionDescription(SessionDescription.Type.OFFER, signal.getString("sdp")))
                        "webrtc_ice" -> { val candidate = IceCandidate(signal.optString("sdp_mid"), signal.optInt("sdp_mline_index"), signal.getString("candidate")); if (remoteDescription) remote!!.addIceCandidate(candidate) else waiting += candidate }
                    }
                } }, { bytes -> result = bytes; completed.countDown() }, { connected.countDown() }, {})
            }.get(10, TimeUnit.SECONDS)
            assertTrue("Native DataChannel did not open", connected.await(20, TimeUnit.SECONDS))
            assertTrue(serial.submit<Boolean> { native!!.send(payload) }.get())
            assertTrue("DataChannel binary response missing", completed.await(10, TimeUnit.SECONDS))
            assertArrayEquals(payload, result)
        } finally {
            serial.submit { native?.close(); remoteChannel?.unregisterObserver(); remoteChannel?.close(); remoteChannel?.dispose(); remote?.close(); remote?.dispose(); factory.dispose() }.get(10, TimeUnit.SECONDS)
            serial.shutdown()
        }
    }
}
