package com.yuklive.app.core.device

import android.media.MediaCodecList
import android.media.MediaFormat
import com.yuklive.app.core.settings.Resolution

/** Cek kemampuan encoder hardware HP (untuk menyembunyikan opsi yang tidak didukung). */
object EncoderSupport {

    private val cache = HashMap<Resolution, Boolean>()

    fun supports(resolution: Resolution, fps: Int = 30): Boolean = cache.getOrPut(resolution) {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                .any { info ->
                    val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                    caps.isSizeSupported(resolution.width, resolution.height) &&
                        caps.areSizeAndRateSupported(resolution.width, resolution.height, fps.toDouble())
                }
        }.getOrDefault(resolution != Resolution.UHD4K)
    }

    fun supportedResolutions(): List<Resolution> = Resolution.entries.filter { supports(it) }

    /** Perkiraan kuota per jam, mis. "2,0 GB". */
    fun dataPerHour(videoKbps: Int, audioKbps: Int): String {
        val gb = (videoKbps + audioKbps) * 3600.0 / 8 / 1_000_000
        return if (gb >= 1) String.format(java.util.Locale.forLanguageTag("id"), "%.1f GB", gb)
        else String.format(java.util.Locale.forLanguageTag("id"), "%.0f MB", gb * 1000)
    }
}
