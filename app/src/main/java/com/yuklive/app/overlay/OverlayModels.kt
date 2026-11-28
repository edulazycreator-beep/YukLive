package com.yuklive.app.overlay

import com.yuklive.app.core.settings.SourceKind
import java.util.UUID

enum class LayerType { Text, Image, LowerThird, Clock, LiveBadge, Ticker }

enum class ClockMode { Clock, Timer, Countdown }

enum class LayerAnim { None, Fade, Slide, Pop }

/**
 * Satu layer overlay. Posisi (x, y) = titik tengah dalam koordinat 0..1 dari frame stream.
 * Ukuran teks = pecahan dari tinggi frame supaya hasilnya sama di 720p, 1080p, maupun 4K.
 */
data class OverlayLayer(
    val id: String = UUID.randomUUID().toString(),
    val type: LayerType,
    val name: String,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val visible: Boolean = true,
    val locked: Boolean = false,
    // Teks
    val text: String = "",
    val subText: String = "",
    val fontKey: String = FontRegistry.KEY_HOST,
    val fontWeight: Int = 700,
    val textSize: Float = 0.06f,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val strokeColor: Int = 0xFF000000.toInt(),
    val strokeWidth: Float = 0f,
    val bgColor: Int = 0xCC000000.toInt(),
    val bgEnabled: Boolean = false,
    // Gambar
    val imagePath: String? = null,
    // Jam / timer / countdown
    val clockMode: ClockMode = ClockMode.Clock,
    val countdownSeconds: Int = 300,
    val timerStartedAt: Long = System.currentTimeMillis(),
    // Running text
    val tickerSpeed: Float = 0.12f,
    // Animasi
    val animIn: LayerAnim = LayerAnim.Fade,
    val animOut: LayerAnim = LayerAnim.Fade,
)

data class Scene(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val source: SourceKind = SourceKind.Camera,
    val layers: List<OverlayLayer> = emptyList(),
    /** Subtitle live tampil di scene ini (mesin subtitle tetap global). */
    val showSubtitle: Boolean = true,
)

data class OverlayDocument(
    val scenes: List<Scene> = listOf(Scene(name = "Scene 1")),
    val activeSceneId: String? = null,
) {
    val activeScene: Scene get() = scenes.firstOrNull { it.id == activeSceneId } ?: scenes.first()

    companion object {
        const val MAX_SCENES = 8
    }
}

object LayerDefaults {
    fun create(type: LayerType, name: String): OverlayLayer = when (type) {
        LayerType.Text -> OverlayLayer(type = type, name = name, text = "YukLive", y = 0.2f)
        LayerType.Image -> OverlayLayer(type = type, name = name, x = 0.88f, y = 0.14f, scale = 0.6f)
        LayerType.LowerThird -> OverlayLayer(
            type = type, name = name, text = "Nama Pembicara", subText = "Jabatan",
            x = 0.28f, y = 0.8f, textSize = 0.05f, bgEnabled = true,
            animIn = LayerAnim.Slide, animOut = LayerAnim.Slide,
        )
        LayerType.Clock -> OverlayLayer(type = type, name = name, x = 0.9f, y = 0.08f, textSize = 0.045f, bgEnabled = true)
        LayerType.LiveBadge -> OverlayLayer(type = type, name = name, x = 0.08f, y = 0.08f, textSize = 0.04f, animIn = LayerAnim.Pop)
        LayerType.Ticker -> OverlayLayer(
            type = type, name = name, text = "Selamat datang di siaran langsung kami",
            y = 0.95f, textSize = 0.04f, bgEnabled = true,
        )
    }
}
