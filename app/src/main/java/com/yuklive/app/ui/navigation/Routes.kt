package com.yuklive.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import com.yuklive.app.R

object Routes {
    const val HOME = "home"
    const val STUDIO = "studio"
    const val OVERLAY = "overlay"
    const val SETTINGS = "settings"

    // Layar sekunder (dibuka dari Beranda / Pengaturan)
    const val SOURCES = "sources"
    const val SUBTITLE = "subtitle"
    const val DESTINATIONS = "destinations"
}

/** 4 tab navigasi bawah. */
enum class TopTab(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    Home(Routes.HOME, R.string.tab_home, Icons.Outlined.Home),
    Studio(Routes.STUDIO, R.string.tab_studio, Icons.Outlined.Videocam),
    Overlay(Routes.OVERLAY, R.string.tab_overlay, Icons.Outlined.Layers),
    Settings(Routes.SETTINGS, R.string.tab_settings, Icons.Outlined.Settings);

    companion object {
        val routes: Set<String> = entries.map { it.route }.toSet()
    }
}
