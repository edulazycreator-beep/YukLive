package com.yuklive.app.core.destination

import java.util.UUID

/** Preset platform. Server default bisa diubah pengguna. */
enum class Platform(
    val displayName: String,
    val defaultServer: String,
    val protocolLabel: String,
) {
    YouTube("YouTube", "rtmps://a.rtmps.youtube.com:443/live2", "RTMPS"),
    Facebook("Facebook", "rtmps://live-api-s.facebook.com:443/rtmp", "RTMPS"),
    TikTok("TikTok", "", "RTMP"),
    Twitch("Twitch", "rtmp://live.twitch.tv/app", "RTMP"),
    Instagram("Instagram", "", "RTMPS"),
    CustomRtmp("Custom RTMP", "rtmp://", "RTMP"),
    CustomSrt("Custom SRT", "srt://", "SRT"),
}

data class Destination(
    val id: String = UUID.randomUUID().toString(),
    val platform: Platform,
    val name: String,
    val server: String,
    val streamKey: String,
) {
    /** URL lengkap yang dikirim ke encoder. */
    fun endpoint(): String {
        val base = server.trim().trimEnd('/')
        val key = streamKey.trim()
        if (key.isEmpty()) return base
        return if (base.startsWith("srt://", ignoreCase = true)) {
            val sep = if (base.contains('?')) "&" else "?"
            "$base${sep}streamid=$key"
        } else {
            "$base/$key"
        }
    }

    fun validate(): DestinationError? {
        val s = server.trim()
        return when {
            name.isBlank() -> DestinationError.EmptyName
            s.isBlank() -> DestinationError.EmptyServer
            !SupportedSchemes.any { s.startsWith(it, ignoreCase = true) } -> DestinationError.BadScheme
            s.substringAfter("://").isBlank() -> DestinationError.EmptyServer
            platform != Platform.CustomSrt && platform != Platform.CustomRtmp && streamKey.isBlank() -> DestinationError.EmptyKey
            else -> null
        }
    }

    companion object {
        val SupportedSchemes = listOf("rtmp://", "rtmps://", "srt://", "rtsp://")
    }
}

enum class DestinationError { EmptyName, EmptyServer, BadScheme, EmptyKey }
