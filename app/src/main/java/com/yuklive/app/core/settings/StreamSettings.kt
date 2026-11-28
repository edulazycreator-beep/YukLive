package com.yuklive.app.core.settings

enum class Resolution(val label: String, val width: Int, val height: Int) {
    HD720("720p", 1280, 720),
    FHD1080("1080p", 1920, 1080),
    UHD4K("4K", 3840, 2160),
}

enum class StreamOrientation { Landscape, Portrait }

enum class VideoCodecOption(val label: String) { H264("H.264"), H265("H.265") }

enum class TransitionType { Cut, Fade }

/** Sumber video yang bisa dipilih di Studio. */
enum class SourceKind { Camera, Usb, Wifi, ColorBar }

data class StreamSettings(
    val resolution: Resolution = Resolution.FHD1080,
    val orientation: StreamOrientation = StreamOrientation.Landscape,
    val fps: Int = 30,
    val videoBitrateKbps: Int = 4500,
    val adaptiveBitrate: Boolean = true,
    val audioBitrateKbps: Int = 128,
    val sampleRate: Int = 48_000,
    val stereo: Boolean = true,
    val keyframeSec: Int = 2,
    val codec: VideoCodecOption = VideoCodecOption.H264,
    val recordLocally: Boolean = false,
    val transition: TransitionType = TransitionType.Fade,
) {
    /** Ukuran frame keluaran (sudah memperhitungkan orientasi). */
    val outputWidth: Int get() = if (orientation == StreamOrientation.Portrait) resolution.height else resolution.width
    val outputHeight: Int get() = if (orientation == StreamOrientation.Portrait) resolution.width else resolution.height

    companion object {
        val FpsOptions = listOf(30, 60)
        val AudioBitrateOptions = listOf(96, 128, 160, 192)
        val SampleRateOptions = listOf(44_100, 48_000)
        val KeyframeOptions = listOf(1, 2, 3, 4)
        const val MinVideoBitrate = 800
        const val MaxVideoBitrate = 20_000

        /** Bitrate yang disarankan per resolusi & fps. */
        fun recommendedBitrate(resolution: Resolution, fps: Int): Int {
            val base = when (resolution) {
                Resolution.HD720 -> 2500
                Resolution.FHD1080 -> 4500
                Resolution.UHD4K -> 13000
            }
            return if (fps >= 60) (base * 1.5f).toInt() else base
        }
    }
}
