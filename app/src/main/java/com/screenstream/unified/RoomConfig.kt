package com.screenstream.unified

import android.net.Uri
import java.security.SecureRandom

/** Connection settings shared by Viewer and Stream modes. */
data class RoomConfig(
    val serverUrl: String = DEFAULT_SERVER,
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

    fun normalizedRoom(): String = room.trim().uppercase()

    fun deepLink(): Uri = Uri.Builder()
        .scheme("screenstream")
        .authority("connect")
        .appendQueryParameter("server", normalizedServer())
        .appendQueryParameter("room", normalizedRoom())
        .build()

    companion object {
        const val DEFAULT_SERVER = "wss://screenstream-signaling.onrender.com"
        private const val ROOM_LENGTH = 10
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        private fun generateRoom(): String {
            val random = SecureRandom()
            return buildString(ROOM_LENGTH) {
                repeat(ROOM_LENGTH) {
                    append(ALPHABET[random.nextInt(ALPHABET.length)])
                }
            }
        }
    }
}
