package com.yuklive.app.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Ring buffer sampel 16-bit (thread-safe). Kalau penuh, sampel tertua dibuang. */
class PcmRing(capacity: Int) {
    private val data = ShortArray(capacity)
    private var readPos = 0
    private var writePos = 0
    private var count = 0

    @Synchronized
    fun write(src: ShortArray, length: Int = src.size) {
        for (i in 0 until length) {
            data[writePos] = src[i]
            writePos = (writePos + 1) % data.size
            if (count == data.size) readPos = (readPos + 1) % data.size else count++
        }
    }

    /** Membaca tepat [length] sampel; kekurangan diisi nol. Mengembalikan jumlah sampel asli. */
    @Synchronized
    fun read(dst: ShortArray, length: Int): Int {
        val n = min(length, count)
        for (i in 0 until n) {
            dst[i] = data[readPos]
            readPos = (readPos + 1) % data.size
        }
        for (i in n until length) dst[i] = 0
        count -= n
        return n
    }

    @Synchronized
    fun clear() {
        readPos = 0; writePos = 0; count = 0
    }
}

object PcmUtils {

    /**
     * Ubah format PCM: jumlah kanal dan sample rate (interpolasi linear).
     * Cukup untuk audio siaran & speech-to-text.
     */
    fun convert(
        input: ShortArray,
        inputLength: Int,
        srcRate: Int,
        srcChannels: Int,
        dstRate: Int,
        dstChannels: Int,
    ): ShortArray {
        if (srcChannels <= 0 || srcRate <= 0 || inputLength <= 0) return ShortArray(0)
        val srcFrames = inputLength / srcChannels
        // 1. Kanal -> mono/stereo
        val chOut = dstChannels.coerceIn(1, 2)
        val mid = ShortArray(srcFrames * chOut)
        for (f in 0 until srcFrames) {
            val base = f * srcChannels
            if (chOut == 1) {
                val v = if (srcChannels == 1) input[base].toInt()
                else (input[base].toInt() + input[base + 1].toInt()) / 2
                mid[f] = v.toShort()
            } else {
                val l = input[base]
                val r = if (srcChannels > 1) input[base + 1] else l
                mid[f * 2] = l
                mid[f * 2 + 1] = r
            }
        }
        if (srcRate == dstRate) return mid
        // 2. Resample linear
        val dstFrames = max(1, (srcFrames.toLong() * dstRate / srcRate).toInt())
        val out = ShortArray(dstFrames * chOut)
        val ratio = srcRate.toDouble() / dstRate
        for (f in 0 until dstFrames) {
            val pos = f * ratio
            val i0 = pos.toInt().coerceAtMost(srcFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(srcFrames - 1)
            val t = pos - i0
            for (c in 0 until chOut) {
                val a = mid[i0 * chOut + c]
                val b = mid[i1 * chOut + c]
                out[f * chOut + c] = (a + (b - a) * t).toInt().toShort()
            }
        }
        return out
    }

    /** Level puncak 0..1 untuk meter VU. */
    fun peak(samples: ShortArray, length: Int = samples.size): Float {
        var p = 0
        for (i in 0 until length) p = max(p, abs(samples[i].toInt()))
        return (p / 32768f).coerceIn(0f, 1f)
    }

    fun toBytes(samples: ShortArray, length: Int = samples.size): ByteArray {
        val out = ByteArray(length * 2)
        for (i in 0 until length) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun clamp(v: Int): Short = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}
