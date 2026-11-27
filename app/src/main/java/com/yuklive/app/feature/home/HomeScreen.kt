package com.yuklive.app.feature.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yuklive.app.R
import com.yuklive.app.core.destination.DestinationRepository
import com.yuklive.app.core.destination.DestinationState
import com.yuklive.app.core.live.LiveSessionRepository
import com.yuklive.app.overlay.OverlayDocument
import com.yuklive.app.overlay.OverlayRepository
import com.yuklive.app.ui.components.BlinkingDot
import com.yuklive.app.ui.components.EmptyState
import com.yuklive.app.ui.components.HairlineDivider
import com.yuklive.app.ui.components.ListRow
import com.yuklive.app.ui.components.PillButton
import com.yuklive.app.ui.components.PillToggle
import com.yuklive.app.ui.components.RowGroup
import com.yuklive.app.ui.components.SectionHeader
import com.yuklive.app.ui.components.StatusBadge
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    liveSession: LiveSessionRepository,
    private val destinations: DestinationRepository,
    private val overlay: OverlayRepository,
) : ViewModel() {
    val isLive: StateFlow<Boolean> = liveSession.state
        .map { it.isLive }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val destinationState: StateFlow<DestinationState> = destinations.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DestinationState())

    val document: StateFlow<OverlayDocument> = overlay.document

    fun selectDestination(id: String) {
        viewModelScope.launch { destinations.select(id) }
    }

    fun activateScene(id: String) = overlay.setActiveScene(id)
}

@Composable
fun HomeScreen(
    onOpenStudio: () -> Unit,
    onOpenDestinations: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSubtitle: () -> Unit,
    onOpenOverlay: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val isLive by viewModel.isLive.collectAsStateWithLifecycle()
    val dests by viewModel.destinationState.collectAsStateWithLifecycle()
    val doc by viewModel.document.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = Dimens.S3),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.app_name).uppercase(),
                    style = YukType.Headline,
                    color = YukColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(
                    text = stringResource(if (isLive) R.string.status_on_air else R.string.status_offline),
                    active = isLive,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(if (isLive) R.string.home_subtitle_live else R.string.home_subtitle_idle),
                style = YukType.Body,
                color = YukColors.TextSecondary,
            )
            Spacer(Modifier.height(Dimens.S3))
            if (isLive) {
                PillButton(
                    text = stringResource(R.string.home_back_to_studio),
                    onClick = onOpenStudio,
                    modifier = Modifier.fillMaxWidth(),
                    height = 64.dp,
                    leading = { BlinkingDot(color = YukColors.Live, size = 10.dp) },
                )
            } else {
                PillButton(
                    text = stringResource(R.string.home_start_live),
                    onClick = onOpenStudio,
                    modifier = Modifier.fillMaxWidth(),
                    height = 64.dp,
                    icon = Icons.Outlined.Videocam,
                )
            }
        }

        item {
            SectionHeader(stringResource(R.string.home_section_destinations))
            if (dests.destinations.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.home_destinations_empty_title),
                    body = stringResource(R.string.home_destinations_empty_body),
                    actionText = stringResource(R.string.home_destinations_add),
                    onAction = onOpenDestinations,
                )
            } else {
                RowGroup {
                    dests.destinations.forEachIndexed { i, d ->
                        if (i > 0) HairlineDivider()
                        ListRow(
                            title = d.name,
                            subtitle = "${d.platform.displayName} · ${d.platform.protocolLabel}",
                            onClick = { viewModel.selectDestination(d.id) },
                            trailing = { PillToggle(d.id == dests.selected?.id, { viewModel.selectDestination(d.id) }) },
                        )
                    }
                    HairlineDivider()
                    ListRow(title = stringResource(R.string.home_manage_destinations), showChevron = true, onClick = onOpenDestinations)
                }
            }
        }

        item {
            SectionHeader(stringResource(R.string.home_section_scenes))
            RowGroup {
                doc.scenes.take(4).forEachIndexed { i, s ->
                    if (i > 0) HairlineDivider()
                    ListRow(
                        title = s.name,
                        subtitle = stringResource(R.string.home_scene_layers, s.layers.size),
                        icon = Icons.Outlined.Layers,
                        onClick = { viewModel.activateScene(s.id) },
                        trailing = { PillToggle(s.id == doc.activeScene.id, { viewModel.activateScene(s.id) }) },
                    )
                }
                HairlineDivider()
                ListRow(title = stringResource(R.string.home_scenes_open_overlay), showChevron = true, onClick = onOpenOverlay)
            }
        }

        item {
            SectionHeader(stringResource(R.string.home_section_tools))
            RowGroup {
                ListRow(
                    title = stringResource(R.string.screen_sources),
                    subtitle = stringResource(R.string.home_tools_sources_desc),
                    icon = Icons.Outlined.VideoSettings,
                    showChevron = true,
                    onClick = onOpenSources,
                )
                HairlineDivider()
                ListRow(
                    title = stringResource(R.string.screen_subtitle),
                    subtitle = stringResource(R.string.home_tools_subtitle_desc),
                    icon = Icons.Outlined.Subtitles,
                    showChevron = true,
                    onClick = onOpenSubtitle,
                )
            }
            Spacer(Modifier.height(Dimens.S3))
        }
    }
}
