package com.screenstream.unified

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val serverUrl: String,
    private val room: String,
    private val role: String,
    private val listener: Listener
) {
    interface Listener {
        fun onJoined()
        fun onPeerJoined()
        fun onPeerLeft()
        fun onMessage(message: JSONObject)
        fun onError(message: String)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var socket: WebSocket? = null

    fun connect() {
        if (!(serverUrl.startsWith("wss://") || serverUrl.startsWith("ws://"))) {
            listener.onError("Server must use ws:// or wss://")
            return
        }
        socket = client.newWebSocket(Request.Builder().url(serverUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send(JSONObject().apply {
                    put("type", "join")
                    put("room", room.trim().uppercase())
                    put("role", role)
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        "joined" -> listener.onJoined()
                        "peer-joined" -> listener.onPeerJoined()
                        "peer-left" -> listener.onPeerLeft()
                        "error" -> listener.onError(message.optString("message", "Signaling error"))
                        else -> listener.onMessage(message)
                    }
                } catch (_: Exception) {
                    listener.onError("Invalid signaling message")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                listener.onError(t.message ?: "Signaling connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onPeerLeft()
            }
        })
    }

    fun send(message: JSONObject) { socket?.send(message.toString()) }
    fun close() { socket?.close(1000, "closed"); socket = null; client.dispatcher.executorService.shutdown() }
}
