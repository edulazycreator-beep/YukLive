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
import com.yuklive.app.ui.components.PillVariant
import com.yuklive.app.ui.components.YukLabel
import com.yuklive.app.core.device.DeviceStatus
import com.yuklive.app.core.device.DeviceStatusMonitor
import com.yuklive.app.video.CameraController
import com.yuklive.app.video.UvcState
import com.yuklive.app.video.UvcStatus
import com.yuklive.app.video.UvcVideoSource
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import com.yuklive.app.ui.theme.PillShape
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
    device: DeviceStatusMonitor,
    camera: CameraController,
    uvc: UvcVideoSource,
) : ViewModel() {
    val deviceStatus: StateFlow<DeviceStatus> = device.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceStatus())
    val online: StateFlow<Boolean> = device.online
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val cameraCount: Int = camera.state.value.lenses.size
    val usbState: StateFlow<UvcState> = uvc.state

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
            } else if (dests.destinations.isEmpty()) {
                // Belum ada tujuan: langkah pertama yang benar adalah menambah tujuan.
                PillButton(
                    text = stringResource(R.string.home_destinations_add),
                    onClick = onOpenDestinations,
                    modifier = Modifier.fillMaxWidth(),
                    height = 64.dp,
                    icon = Icons.Outlined.Add,
                )
                Spacer(Modifier.height(Dimens.S1))
                PillButton(
                    text = stringResource(R.string.home_open_studio),
                    onClick = onOpenStudio,
                    modifier = Modifier.fillMaxWidth(),
                    variant = PillVariant.Secondary,
                    icon = Icons.Outlined.Videocam,
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
            SectionHeader(stringResource(R.string.home_section_ready))
            val d by viewModel.deviceStatus.collectAsStateWithLifecycle()
            val online by viewModel.online.collectAsStateWithLifecycle()
            val usb by viewModel.usbState.collectAsStateWithLifecycle()
            RowGroup {
                ReadyRow(
                    stringResource(R.string.home_ready_destination),
                    dests.selected?.name ?: stringResource(R.string.home_ready_destination_none),
                    if (dests.selected != null) YukColors.Success else YukColors.Warning,
                )
                HairlineDivider()
                ReadyRow(
                    stringResource(R.string.home_ready_internet),
                    stringResource(if (online) R.string.home_ready_online else R.string.home_ready_offline),
                    if (online) YukColors.Success else YukColors.Live,
                )
                HairlineDivider()
                ReadyRow(
                    stringResource(R.string.home_ready_camera),
                    stringResource(R.string.home_ready_camera_value, viewModel.cameraCount) +
                        if (usb.status == UvcStatus.Ready) " · USB" else "",
                    if (viewModel.cameraCount > 0 || usb.status == UvcStatus.Ready) YukColors.Success else YukColors.Warning,
                )
                HairlineDivider()
                val batt = d.batteryPercent?.let { "$it%" } ?: "—"
                val temp = d.temperatureC?.let { " · ${it.toInt()}°C" } ?: ""
                ReadyRow(
                    stringResource(R.string.home_ready_battery),
                    batt + temp,
                    when {
                        d.isHot || d.isBatteryLow -> YukColors.Warning
                        else -> YukColors.Success
                    },
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
                            trailing = {
                                if (d.id == dests.selected?.id) YukLabel(stringResource(R.string.home_selected), color = YukColors.Success)
                            },
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
                        trailing = {
                            if (s.id == doc.activeScene.id) YukLabel(stringResource(R.string.home_active), color = YukColors.TextPrimary)
                        },
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

@Composable
private fun ReadyRow(label: String, value: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.S2, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, PillShape))
        Spacer(Modifier.width(Dimens.S2))
        Text(label, style = YukType.Body, color = YukColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = YukType.Body, color = YukColors.TextPrimary, maxLines = 1)
    }
}
