package com.screenstream.unified

import android.net.Uri
import java.security.SecureRandom

/** Connection settings shared by Viewer and Stream modes. */
data class RoomConfig(
    val serverUrl: String = "wss://screenstream-signaling.onrender.com",
    val room: String = generateRoom()
) {
    fun normalizedServer(): String = serverUrl.trim().let { value ->
        when {
            value.startsWith("ws://") || value.startsWith("wss://") -> value.removeSuffix("/")
            value.startsWith("https://") -> "wss://" + value.removePrefix("https://").removeSuffix("/")
            value.startsWith("http://") -> "ws://" + value.removePrefix("http://").removeSuffix("/")
            else -> "wss://" + value.removeSuffix("/")
        }
    }

    fun deepLink(): Uri = Uri.Builder().scheme("screenstream").authority("connect")
        .appendQueryParameter("server", normalizedServer())
        .appendQueryParameter("room", room.trim().uppercase())
        .build()

    companion object {
        private fun generateRoom(): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val random = SecureRandom()
            return buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }
    }
}
