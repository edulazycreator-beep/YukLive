package com.yuklive.app.core.device

import android.media.MediaCodecList
import android.media.MediaFormat
import com.yuklive.app.core.settings.Resolution

/** Cek kemampuan encoder hardware HP (untuk menyembunyikan opsi yang tidak didukung). */
object EncoderSupport {

    private val cache = HashMap<Resolution, Boolean>()

    /**
     * 720p & 1080p selalu dianggap didukung (semua HP modern bisa; sebagian encoder
     * melaporkan 1080 "tidak didukung" hanya karena perataan 16 piksel → 1088).
     * 4K dicek ke encoder H.264/H.265, dengan toleransi perataan & orientasi.
     */
    fun supports(resolution: Resolution, fps: Int = 30): Boolean {
        if (resolution != Resolution.UHD4K) return true
        return cache.getOrPut(resolution) {
            runCatching {
                val mimes = listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { it.isEncoder }
                    .any { info ->
                        info.supportedTypes.any { t -> mimes.any { it.equals(t, true) } } &&
                            mimes.filter { m -> info.supportedTypes.any { it.equals(m, true) } }.any { m ->
                                val caps = info.getCapabilitiesForType(m).videoCapabilities ?: return@any false
                                fits(caps, resolution.width, resolution.height) ||
                                    fits(caps, resolution.height, resolution.width)
                            }
                    }
            }.getOrDefault(false)
        }
    }

    private fun fits(caps: android.media.MediaCodecInfo.VideoCapabilities, w: Int, h: Int): Boolean {
        val aw = align(w, caps.widthAlignment)
        val ah = align(h, caps.heightAlignment)
        return caps.supportedWidths.contains(aw) && caps.supportedHeights.contains(ah) &&
            runCatching { caps.getSupportedHeightsFor(aw).contains(ah) }.getOrDefault(true)
    }

    private fun align(v: Int, a: Int): Int = if (a <= 1) v else (v + a - 1) / a * a

    fun supportedResolutions(): List<Resolution> = Resolution.entries.filter { supports(it) }

    /** Perkiraan kuota per jam, mis. "2,0 GB". */
    fun dataPerHour(videoKbps: Int, audioKbps: Int): String {
        val gb = (videoKbps + audioKbps) * 3600.0 / 8 / 1_000_000
        return if (gb >= 1) String.format(java.util.Locale.forLanguageTag("id"), "%.1f GB", gb)
        else String.format(java.util.Locale.forLanguageTag("id"), "%.0f MB", gb * 1000)
    }
}
