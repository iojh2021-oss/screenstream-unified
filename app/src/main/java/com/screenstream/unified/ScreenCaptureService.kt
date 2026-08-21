package com.screenstream.unified

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver
import org.webrtc.SurfaceTextureHelper

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_ROOM_CODE = "room_code"
        private const val NOTIFICATION_ID = 1001
    }

    private var signaling: SignalingClient? = null
    private var peer: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: org.webrtc.VideoSource? = null
    private var videoTrack: org.webrtc.VideoTrack? = null
    private var remoteDescriptionSet = false
    private val pendingCandidates = ArrayDeque<IceCandidate>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (peer == null && intent != null) startCapture(intent)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val server = intent.getStringExtra(EXTRA_SERVER_URL).orEmpty()
        val room = intent.getStringExtra(EXTRA_ROOM_CODE).orEmpty().trim().uppercase()
        if (resultCode < 0 || data == null || server.isBlank() || room.isBlank()) {
            stopSelf()
            return
        }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )
        eglBase = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()

        val rtc = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = factory?.createPeerConnection(rtc, observer)
        if (peer == null) {
            stopSelf()
            return
        }

        capturer = ScreenCapturerAndroid(
            Intent(data),
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }
        )
        textureHelper = SurfaceTextureHelper.create(
            "ScreenStreamCapture",
            eglBase!!.eglBaseContext
        )
        videoSource = factory!!.createVideoSource(true)
        capturer!!.initialize(textureHelper, this, videoSource!!.capturerObserver)
        capturer!!.startCapture(1280, 720, 30)
        videoTrack = factory!!.createVideoTrack("screen", videoSource)
        videoTrack!!.setEnabled(true)
        peer!!.addTrack(videoTrack, listOf("screen-stream"))

        signaling = SignalingClient(server, room, "phone", signalingListener)
        signaling!!.connect()
        update("Waiting for Viewer…")
    }

    private val signalingListener = object : SignalingClient.Listener {
        override fun onJoined() = update("Waiting for Viewer…")
        override fun onPeerJoined() {
            update("Viewer connected")
            createOffer()
        }
        override fun onPeerLeft() {
            remoteDescriptionSet = false
            pendingCandidates.clear()
            update("Viewer disconnected")
        }
        override fun onError(message: String) = update(message)
        override fun onMessage(message: JSONObject) {
            when (message.optString("type")) {
                "answer" -> setAnswer(message)
                "ice-candidate" -> addCandidate(message)
            }
        }
    }

    private fun createOffer() {
        peer?.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                peer?.setLocalDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        signaling?.send(JSONObject().apply {
                            put("type", "offer")
                            put("sdp", description.description)
                        })
                    }
                    override fun onSetFailure(error: String) {
                        update("Local SDP failed: $error")
                    }
                }, description)
            }
            override fun onCreateFailure(error: String) {
                update("Offer failed: $error")
            }
        }, MediaConstraints())
    }

    private fun setAnswer(message: JSONObject) {
        val sdp = message.optString("sdp")
        if (sdp.isBlank()) return
        peer?.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                pendingCandidates.forEach { peer?.addIceCandidate(it) }
                pendingCandidates.clear()
                update("Streaming")
            }
            override fun onSetFailure(error: String) {
                update("Remote SDP failed: $error")
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun addCandidate(message: JSONObject) {
        val sdpMid = message.optString("sdpMid", "0")
        val sdp = message.optString("candidate")
        if (sdp.isBlank()) return
        val candidate = IceCandidate(
            sdpMid,
            message.optInt("sdpMLineIndex", 0),
            sdp
        )
        if (remoteDescriptionSet) peer?.addIceCandidate(candidate)
        else pendingCandidates.addLast(candidate)
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

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> update("Streaming")
                PeerConnection.PeerConnectionState.FAILED -> update("WebRTC connection failed")
                PeerConnection.PeerConnectionState.DISCONNECTED -> update("WebRTC disconnected")
                else -> Unit
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            update("WebRTC: ${state.name.lowercase().replace('_', ' ')}")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) = Unit
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) = Unit
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(
            NOTIFICATION_ID,
            notification(text)
        )
    }

    private fun notification(text: String = "Screen sharing is active"): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    "screenstream",
                    "ScreenStream",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return NotificationCompat.Builder(this, "screenstream")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("ScreenStream")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        try {
            capturer?.stopCapture()
        } catch (_: Exception) {
        }
        capturer?.dispose()
        textureHelper?.dispose()
        videoSource?.dispose()
        videoTrack?.dispose()
        peer?.close()
        peer?.dispose()
        peer = null
        factory?.dispose()
        factory = null
        eglBase?.release()
        eglBase = null
        signaling?.close()
        signaling = null
        super.onDestroy()
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
