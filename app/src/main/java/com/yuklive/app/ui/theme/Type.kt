package com.yuklive.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.yuklive.app.R

/**
 * Host Grotesk (variable font, sumbu wght 300–800) dari res/font/host_grotesk.ttf.
 * Satu file dipakai untuk semua ketebalan lewat FontVariation.
 */
@OptIn(ExperimentalTextApi::class)
private fun hostGrotesk(weight: FontWeight) = Font(
    resId = R.font.host_grotesk,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val HostGrotesk = FontFamily(
    hostGrotesk(FontWeight.Normal),   // 400 isi
    hostGrotesk(FontWeight.Medium),   // 500 label
    hostGrotesk(FontWeight.SemiBold), // 600 judul
    hostGrotesk(FontWeight.Bold),     // 700 judul besar
)

/** Letter-spacing label: 0.08em, selalu huruf kapital. */
private val LabelSpacing = 0.08.em

object YukType {
    val Display = TextStyle(fontFamily = HostGrotesk, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp)
    val Headline = TextStyle(fontFamily = HostGrotesk, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp)
    val Title = TextStyle(fontFamily = HostGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
    val TitleSmall = TextStyle(fontFamily = HostGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    val Body = TextStyle(fontFamily = HostGrotesk, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val BodySmall = TextStyle(fontFamily = HostGrotesk, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val Label = TextStyle(fontFamily = HostGrotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = LabelSpacing)
    val LabelSmall = TextStyle(fontFamily = HostGrotesk, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = LabelSpacing)
    val Button = TextStyle(fontFamily = HostGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = LabelSpacing)

    /** Angka berlebar tetap untuk durasi/bitrate supaya tidak "goyang". */
    val Mono = TextStyle(fontFamily = HostGrotesk, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum")
}

val YukTypography = Typography(
    displayLarge = YukType.Display,
    displayMedium = YukType.Display,
    displaySmall = YukType.Display,
    headlineLarge = YukType.Headline,
    headlineMedium = YukType.Headline,
    headlineSmall = YukType.Title,
    titleLarge = YukType.Title,
    titleMedium = YukType.TitleSmall,
    titleSmall = YukType.TitleSmall,
    bodyLarge = YukType.Body,
    bodyMedium = YukType.Body,
    bodySmall = YukType.BodySmall,
    labelLarge = YukType.Button,
    labelMedium = YukType.Label,
    labelSmall = YukType.LabelSmall,
)
