package com.yuklive.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yuklive.app.stream.StreamEngine
import com.yuklive.app.feature.destinations.DestinationsScreen
import com.yuklive.app.feature.home.HomeScreen
import com.yuklive.app.feature.settings.SettingsScreen
import com.yuklive.app.feature.sources.SourcesScreen
import com.yuklive.app.feature.studio.StudioScreen
import com.yuklive.app.feature.subtitle.SubtitleScreen
import com.yuklive.app.ui.theme.Motion
import com.yuklive.app.ui.theme.YukColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    engine: StreamEngine,
) : ViewModel() {
    /** Termasuk menyambung / reconnect: Studio tetap penuh layar. */
    val isLive: StateFlow<Boolean> = engine.state
        .map { it.isActive }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

@Composable
fun YukLiveRoot(viewModel: RootViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val isLive by viewModel.isLive.collectAsStateWithLifecycle()

    // Studio selalu penuh layar seperti OBS (navigasi bawah disembunyikan).
    val showBottomBar = route != null && route in TopTab.routes && route != Routes.STUDIO

    Scaffold(
        containerColor = YukColors.Black,
        contentColor = YukColors.TextPrimary,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = fadeIn(tween(Motion.Fast)),
                exit = fadeOut(tween(Motion.Fast)),
            ) {
                YukBottomBar(currentRoute = route, onSelect = { navController.navigateToTab(it) })
            }
        },
    ) { padding ->
        YukNavHost(navController, Modifier.padding(padding))
    }
}

private fun NavHostController.navigateToTab(tab: TopTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun YukNavHost(navController: NavHostController, modifier: Modifier) {
    val back: () -> Unit = { navController.popBackStack() }
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = { fadeIn(tween(Motion.Normal)) },
        exitTransition = { fadeOut(tween(Motion.Fast)) },
        popEnterTransition = { fadeIn(tween(Motion.Normal)) },
        popExitTransition = { fadeOut(tween(Motion.Fast)) },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenStudio = { navController.navigateToTab(TopTab.Studio) },
                onOpenDestinations = { navController.navigate(Routes.DESTINATIONS) },
                onOpenSources = { navController.navigate(Routes.SOURCES) },
                onOpenSubtitle = { navController.navigate(Routes.SUBTITLE) },
                onOpenOverlay = { navController.navigateToTab(TopTab.Studio) },
            )
        }
        composable(Routes.STUDIO) {
            StudioScreen(
                onExit = { navController.navigateToTab(TopTab.Home) },
                onOpenDestinations = { navController.navigate(Routes.DESTINATIONS) },
                onOpenSources = { navController.navigate(Routes.SOURCES) },
                onOpenSubtitle = { navController.navigate(Routes.SUBTITLE) },
                onOpenSettings = { navController.navigateToTab(TopTab.Settings) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenSources = { navController.navigate(Routes.SOURCES) },
                onOpenSubtitle = { navController.navigate(Routes.SUBTITLE) },
                onOpenDestinations = { navController.navigate(Routes.DESTINATIONS) },
            )
        }
        composable(Routes.SOURCES) { SourcesScreen(onBack = back) }
        composable(Routes.SUBTITLE) { SubtitleScreen(onBack = back) }
        composable(Routes.DESTINATIONS) { DestinationsScreen(onBack = back) }
    }
}
