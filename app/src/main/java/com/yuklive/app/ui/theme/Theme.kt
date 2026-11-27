package com.yuklive.app.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val YukColorScheme = darkColorScheme(
    primary = YukColors.TextPrimary,
    onPrimary = YukColors.Black,
    primaryContainer = YukColors.Surface,
    onPrimaryContainer = YukColors.TextPrimary,
    secondary = YukColors.TextSecondary,
    onSecondary = YukColors.Black,
    secondaryContainer = YukColors.Surface,
    onSecondaryContainer = YukColors.TextPrimary,
    tertiary = YukColors.TextSecondary,
    onTertiary = YukColors.Black,
    background = YukColors.Black,
    onBackground = YukColors.TextPrimary,
    surface = YukColors.Black,
    onSurface = YukColors.TextPrimary,
    surfaceVariant = YukColors.Surface,
    onSurfaceVariant = YukColors.TextSecondary,
    surfaceTint = Color.Transparent,
    surfaceBright = YukColors.Surface,
    surfaceDim = YukColors.Black,
    surfaceContainerLowest = YukColors.Black,
    surfaceContainerLow = YukColors.Surface,
    surfaceContainer = YukColors.Surface,
    surfaceContainerHigh = YukColors.Surface,
    surfaceContainerHighest = YukColors.Surface,
    inverseSurface = YukColors.TextPrimary,
    inverseOnSurface = YukColors.Black,
    outline = YukColors.BorderStrong,
    outlineVariant = YukColors.Border,
    error = YukColors.Live,
    onError = YukColors.TextPrimary,
    scrim = YukColors.Black,
)

/** Grid 8dp. Padding layar 16–24dp. */
object Dimens {
    val Grid = 8.dp
    val S1 = 8.dp
    val S2 = 16.dp
    val S3 = 24.dp
    val S4 = 32.dp
    val Hairline = 1.dp
    val ScreenPadding = 24.dp
}

/** Durasi animasi UI: cepat dan halus. */
object Motion {
    const val Fast = 150
    const val Normal = 200
}

@Composable
fun YukLiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = YukColorScheme,
        typography = YukTypography,
        shapes = YukShapes,
    ) {
        CompositionLocalProvider(LocalContentColor provides YukColors.TextPrimary) {
            content()
        }
    }
}
