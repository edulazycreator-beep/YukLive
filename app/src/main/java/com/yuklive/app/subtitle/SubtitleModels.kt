package com.yuklive.app.subtitle

import com.yuklive.app.overlay.FontRegistry

enum class SubtitleAnimation { ComicSlam, Pop, FadeUp, Typewriter, Bounce, None }

enum class SubtitleLook { Minimal, Box, Karaoke }

enum class SubtitlePosition { Top, Center, Bottom }

enum class SubtitleAlign { Left, Center, Right }

enum class SpeechEngineType { Android, Vosk }

data class SubtitleStyle(
    val maxWordsPerLine: Int = 5,
    val maxLines: Int = 2,
    val clearAfterSec: Float = 1.5f,
    val animation: SubtitleAnimation = SubtitleAnimation.ComicSlam,
    val look: SubtitleLook = SubtitleLook.Minimal,
    val fontKey: String = FontRegistry.KEY_POPPINS,
    val fontWeight: Int = 900,
    /** Ukuran teks = pecahan dari sisi pendek frame. */
    val size: Float = 0.075f,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val accentColor: Int = 0xFFFFCC00.toInt(),
    val strokeColor: Int = 0xFF000000.toInt(),
    /** Tebal stroke = pecahan ukuran teks. */
    val strokeWidth: Float = 0.14f,
    val boxColor: Int = 0xCC000000.toInt(),
    val opacity: Float = 1f,
    val hollow: Boolean = false,
    val position: SubtitlePosition = SubtitlePosition.Bottom,
    val align: SubtitleAlign = SubtitleAlign.Center,
    /** Geser bebas dari posisi dasar, dalam fraksi lebar/tinggi layar (bisa digeser di Studio). */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val capacity: Int get() = maxWordsPerLine * maxLines
}

data class Correction(val wrong: String, val right: String)

data class SubtitleConfig(
    val enabled: Boolean = false,
    val engine: SpeechEngineType = SpeechEngineType.Android,
    val language: String = "id-ID",
    val voskModel: String? = null,
    val style: SubtitleStyle = SubtitleStyle(),
    val presets: List<SubtitleStyle?> = List(PRESET_SLOTS) { null },
    val corrections: List<Correction> = emptyList(),
) {
    companion object {
        const val PRESET_SLOTS = 5
        val Languages = listOf(
            "id-ID" to "Bahasa Indonesia",
            "en-US" to "English (US)",
            "en-GB" to "English (UK)",
            "ms-MY" to "Bahasa Melayu",
            "jv-ID" to "Basa Jawa",
            "su-ID" to "Basa Sunda",
        )
    }
}

/** Satu kata yang tampil. [bornAt] = waktu muncul (untuk animasi kata baru). */
data class DisplayWord(val text: String, val bornAt: Long)

/** Snapshot yang dibaca compositor setiap frame. */
data class SubtitleFrame(
    val words: List<DisplayWord> = emptyList(),
    val style: SubtitleStyle = SubtitleStyle(),
    val visible: Boolean = false,
    val version: Long = 0,
)

data class TranscriptEntry(val startMs: Long, val endMs: Long, val text: String)

enum class RecognizerStatus { Off, Starting, Listening, Error }

data class RecognizerState(
    val status: RecognizerStatus = RecognizerStatus.Off,
    val partial: String = "",
    val errorCode: String? = null,
)
