package com.yuklive.app.core.device

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** IP lokal IPv4 (prioritas Wi-Fi / hotspot). */
    fun localIpv4(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
            .filter { !it.second.isLoopbackAddress }
        (candidates.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("ap") || it.first.startsWith("swlan") }
            ?: candidates.firstOrNull())?.second?.hostAddress
    }.getOrNull()

    /** QR code hitam-putih (latar putih supaya mudah dipindai). */
    fun qrBitmap(content: String, size: Int = 512): Bitmap? = runCatching {
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(size * size) { i ->
            if (matrix.get(i % size, i / size)) Color.BLACK else Color.WHITE
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
