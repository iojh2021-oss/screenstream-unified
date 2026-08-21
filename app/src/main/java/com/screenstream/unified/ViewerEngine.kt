package com.screenstream.unified

import android.content.Context
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer

class ViewerEngine(
    context: Context,
    private val config: RoomConfig,
    private val onState: (String) -> Unit
) {
    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val renderer = SurfaceViewRenderer(context)
    private var signaling: SignalingClient? = null
    private var peer: PeerConnection? = null
    private var remoteDescriptionSet = false
    private val pendingCandidates = ArrayDeque<IceCandidate>()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(false)
    }

    fun view(): SurfaceViewRenderer = renderer

    fun start() {
        closePeer()
        remoteDescriptionSet = false
        pendingCandidates.clear()
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = factory.createPeerConnection(rtcConfig, observer)
        if (peer == null) {
            onState("Unable to create WebRTC peer")
            return
        }
        signaling = SignalingClient(config.normalizedServer(), config.normalizedRoom(), "viewer", signalingListener)
        onState("Connecting to signaling…")
        signaling?.connect()
    }

    fun stop() {
        signaling?.close()
        signaling = null
        closePeer()
        onState("Disconnected")
    }

    private val signalingListener = object : SignalingClient.Listener {
        override fun onJoined() { onState("Waiting for Stream device…") }
        override fun onPeerJoined() { onState("Stream device connected") }
        override fun onPeerLeft() {
            remoteDescriptionSet = false
            pendingCandidates.clear()
            onState("Stream device disconnected")
        }
        override fun onError(message: String) { onState(message) }
        override fun onMessage(message: JSONObject) {
            when (message.optString("type")) {
                "offer" -> handleOffer(message)
                "ice-candidate" -> handleCandidate(message)
            }
        }
    }

    private fun handleOffer(message: JSONObject) {
        val sdp = message.optString("sdp")
        if (sdp.isBlank()) return
        val description = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peer?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                pendingCandidates.forEach { peer?.addIceCandidate(it) }
                pendingCandidates.clear()
                peer?.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        peer?.setLocalDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                signaling?.send(JSONObject().apply {
                                    put("type", "answer")
                                    put("sdp", answer.description)
                                })
                                onState("Connecting video…")
                            }
                            override fun onSetFailure(error: String) { onState("Local SDP failed: $error") }
                        }, answer)
                    }
                    override fun onCreateFailure(error: String) { onState("Answer failed: $error") }
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String) { onState("Remote SDP failed: $error") }
        }, description)
    }

    private fun handleCandidate(message: JSONObject) {
        val candidateSdp = message.optString("candidate")
        if (candidateSdp.isBlank()) return
        val candidate = IceCandidate(
            message.optString("sdpMid", "0"),
            message.optInt("sdpMLineIndex", 0),
            candidateSdp
        )
        if (remoteDescriptionSet) peer?.addIceCandidate(candidate) else pendingCandidates.addLast(candidate)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            signaling?.send(JSONObject().apply {
                put("type", "ice-candidate")
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate", candidate.sdp)
            })
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is org.webrtc.VideoTrack) {
                track.setEnabled(true)
                track.addSink(renderer)
                onState("Streaming")
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            onState("WebRTC: ${state.name.lowercase().replace('_', ' ')}")
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> onState("Streaming")
                PeerConnection.PeerConnectionState.FAILED -> onState("WebRTC connection failed")
                PeerConnection.PeerConnectionState.DISCONNECTED -> onState("WebRTC disconnected")
                else -> Unit
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) {
            stream.videoTracks.firstOrNull()?.addSink(renderer)
        }
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(
            receiver: org.webrtc.RtpReceiver,
            mediaStreams: Array<org.webrtc.MediaStream>
        ) = Unit
    }

    private fun closePeer() {
        peer?.close()
        peer?.dispose()
        peer = null
    }

    fun dispose() {
        stop()
        renderer.release()
        factory.dispose()
        eglBase.release()
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
