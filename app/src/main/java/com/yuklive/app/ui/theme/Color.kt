package com.yuklive.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palet gaya Kirimly: hitam monokrom.
 * Warna (merah/hijau/kuning) HANYA dipakai untuk status.
 */
object YukColors {
    // Dasar
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF0A0A0A)
    val Border = Color(0xFF1F1F1F)
    val BorderStrong = Color(0xFF333333)

    // Teks
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF8A8A8A)
    val TextDisabled = Color(0xFF4A4A4A)

    // Status
    val Live = Color(0xFFFF3B30)      // LIVE / REC / error
    val Success = Color(0xFF34C759)   // tersambung / sukses
    val Warning = Color(0xFFFFCC00)   // peringatan / reconnect

    // Lapisan semi-transparan di atas preview (bukan gradien)
    val Overlay = Color(0xB3000000)
}
