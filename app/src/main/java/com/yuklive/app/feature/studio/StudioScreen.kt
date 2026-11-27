package com.yuklive.app.feature.studio

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuklive.app.R
import com.yuklive.app.core.common.UiMessage
import com.yuklive.app.core.common.asString
import com.yuklive.app.core.settings.SourceKind
import com.yuklive.app.stream.EngineState
import com.yuklive.app.stream.NetworkQuality
import com.yuklive.app.stream.StreamPhase
import com.yuklive.app.stream.StreamStats
import com.yuklive.app.ui.components.BlinkingDot
import com.yuklive.app.ui.components.GoLiveButton
import com.yuklive.app.ui.components.HairlineDivider
import com.yuklive.app.ui.components.ListRow
import com.yuklive.app.ui.components.PillButton
import com.yuklive.app.ui.components.PillIconButton
import com.yuklive.app.ui.components.PillVariant
import com.yuklive.app.ui.components.SegmentedPills
import com.yuklive.app.ui.components.StatusBadge
import com.yuklive.app.ui.components.StatusBanner
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.PillShape
import com.yuklive.app.ui.theme.SharpShape
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType
import com.yuklive.app.ui.util.ImmersiveMode
import com.yuklive.app.ui.util.KeepScreenOn
import com.yuklive.app.ui.util.formatDuration
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun StudioScreen(
    onOpenDestinations: () -> Unit,
    viewModel: StudioViewModel = hiltViewModel(),
) {
    PermissionGate {
        StudioContent(viewModel, onOpenDestinations)
    }
}

@Composable
private fun StudioContent(viewModel: StudioViewModel, onOpenDestinations: () -> Unit) {
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val elapsed by viewModel.elapsedMillis.collectAsStateWithLifecycle()
    val overlayDoc by viewModel.overlay.collectAsStateWithLifecycle()
    val subtitleOn by viewModel.subtitleEnabled.collectAsStateWithLifecycle()
    val overlayOn by viewModel.overlayOn.collectAsStateWithLifecycle()
    val mixer by viewModel.mixerState.collectAsStateWithLifecycle()
    val destinations by viewModel.destinationState.collectAsStateWithLifecycle()

    var showQuick by rememberSaveable { mutableStateOf(false) }
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    var pickDestination by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<UiMessage?>(null) }

    val isLive = engine.isActive
    KeepScreenOn(enabled = isLive)
    ImmersiveMode(enabled = isLive)

    // Mixer hidup selama Studio terbuka supaya meter VU & audio siap.
    DisposableEffect(Unit) {
        viewModel.startMonitoring()
        onDispose { viewModel.stopMonitoring() }
    }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message = it }
    }
    LaunchedEffect(message?.id) {
        if (message != null) {
            delay(4500)
            message = null
        }
    }

    Box(Modifier.fillMaxSize().background(YukColors.Black)) {
        CameraPreview(
            onAttach = viewModel::attachPreview,
            onDetach = viewModel::detachPreview,
            onTap = viewModel::onPreviewTap,
            onPinch = viewModel::onPreviewPinch,
            modifier = Modifier.fillMaxSize(),
        )

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            StudioTopBar(engine, stats, elapsed)
            Column(
                Modifier.padding(horizontal = Dimens.S2, vertical = Dimens.S1),
                verticalArrangement = Arrangement.spacedBy(Dimens.S1),
            ) {
                val phase = engine.phase
                if (phase is StreamPhase.Reconnecting) {
                    StatusBanner(
                        stringResource(R.string.studio_reconnecting, phase.secondsLeft, phase.attempt),
                        YukColors.Warning,
                    )
                }
                if (!engine.online && isLive) {
                    StatusBanner(stringResource(R.string.msg_network_lost), YukColors.Warning)
                }
                if (engine.throttled) {
                    StatusBanner(stringResource(R.string.studio_throttled), YukColors.Warning)
                }
                AnimatedVisibility(message != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                    message?.let { m ->
                        StatusBanner(
                            text = m.text.asString(),
                            color = when (m.level) {
                                UiMessage.Level.Error -> YukColors.Live
                                UiMessage.Level.Warning -> YukColors.Warning
                                UiMessage.Level.Success -> YukColors.Success
                                UiMessage.Level.Info -> YukColors.TextSecondary
                            },
                            onClick = { message = null },
                        )
                    }
                }
            }
        }

        StudioBottomBar(
            engine = engine,
            scenes = overlayDoc.scenes.map { it.id to it.name },
            activeSceneId = overlayDoc.activeScene.id,
            subtitleOn = subtitleOn,
            muted = mixer.masterMuted,
            overlayOn = overlayOn,
            onSelectSource = viewModel::switchSource,
            onSelectScene = viewModel::applyScene,
            onToggleSubtitle = viewModel::toggleSubtitle,
            onToggleMute = viewModel::toggleMute,
            onToggleOverlay = viewModel::toggleOverlay,
            onOpenQuick = { showQuick = true },
            onGoLive = {
                if (isLive) confirmEnd = true
                else if (!viewModel.goLive()) pickDestination = true
            },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
    }

    if (showQuick) {
        StudioQuickSheet(viewModel = viewModel, onDismiss = { showQuick = false }, onOpenDestinations = onOpenDestinations)
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            shape = SharpShape,
            containerColor = YukColors.Surface,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.end_live_title), style = YukType.Title) },
            text = { Text(stringResource(R.string.end_live_body), style = YukType.Body, color = YukColors.TextSecondary) },
            confirmButton = {
                PillButton(stringResource(R.string.action_end_live), onClick = {
                    confirmEnd = false
                    viewModel.endLive()
                }, height = 40.dp)
            },
            dismissButton = {
                PillButton(stringResource(R.string.action_cancel), onClick = { confirmEnd = false },
                    variant = PillVariant.Secondary, height = 40.dp)
            },
        )
    }

    if (pickDestination) {
        AlertDialog(
            onDismissRequest = { pickDestination = false },
            shape = SharpShape,
            containerColor = YukColors.Surface,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.studio_pick_destination), style = YukType.Title) },
            text = {
                Column {
                    if (destinations.destinations.isEmpty()) {
                        Text(stringResource(R.string.home_destinations_empty_body), style = YukType.Body, color = YukColors.TextSecondary)
                    }
                    destinations.destinations.forEachIndexed { i, d ->
                        if (i > 0) HairlineDivider()
                        ListRow(
                            title = d.name,
                            subtitle = d.platform.displayName,
                            showChevron = true,
                            onClick = {
                                pickDestination = false
                                viewModel.selectDestinationAndGo(d.id)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                PillButton(stringResource(R.string.home_destinations_add), onClick = {
                    pickDestination = false
                    onOpenDestinations()
                }, height = 40.dp)
            },
            dismissButton = {
                PillButton(stringResource(R.string.action_cancel), onClick = { pickDestination = false },
                    variant = PillVariant.Secondary, height = 40.dp)
            },
        )
    }
}

/** SurfaceView preview dari pipeline OpenGL. Tap = fokus, cubit = zoom. */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun CameraPreview(
    onAttach: (SurfaceView) -> Unit,
    onDetach: (SurfaceView) -> Unit,
    onTap: (android.view.View, MotionEvent) -> Unit,
    onPinch: (MotionEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tap by rememberUpdatedState(onTap)
    val pinch by rememberUpdatedState(onPinch)
    val detach by rememberUpdatedState(onDetach)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).also { view ->
                view.setOnTouchListener { v, e ->
                    if (e.pointerCount > 1) {
                        pinch(e)
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        tap(v, e)
                    }
                    true
                }
                onAttach(view)
            }
        },
        onRelease = { detach(it) },
    )
}

@Composable
private fun StudioTopBar(engine: EngineState, stats: StreamStats, elapsedMs: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(YukColors.Overlay)
            .statusBarsPadding()
            .padding(horizontal = Dimens.S2, vertical = 10.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val (badgeText, badgeColor, active) = when (engine.phase) {
            StreamPhase.Live -> Triple(stringResource(R.string.status_on_air), YukColors.Live, true)
            StreamPhase.Connecting -> Triple(stringResource(R.string.status_connecting), YukColors.Warning, true)
            is StreamPhase.Reconnecting -> Triple(stringResource(R.string.status_reconnecting), YukColors.Warning, true)
            StreamPhase.Idle -> Triple(stringResource(R.string.status_offline), YukColors.Live, false)
        }
        StatusBadge(text = badgeText, active = active, activeColor = badgeColor)
        if (engine.recording) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BlinkingDot(YukColors.Live, 8.dp)
                Spacer(Modifier.width(6.dp))
                Text("REC", style = YukType.LabelSmall, color = YukColors.Live)
            }
        }
        Text(formatDuration(elapsedMs), style = YukType.Mono, color = YukColors.TextPrimary)
        Text(stringResource(R.string.studio_bitrate_value, stats.bitrateKbps), style = YukType.Mono, color = YukColors.TextSecondary)
        if (engine.isActive) {
            Text(stringResource(R.string.studio_fps_value, stats.fps), style = YukType.Mono, color = YukColors.TextSecondary)
            Text(
                stringResource(R.string.studio_dropped_value, stats.droppedFrames),
                style = YukType.Mono,
                color = if (stats.droppedFrames > 0) YukColors.Warning else YukColors.TextSecondary,
            )
            val (qText, qColor) = when (stats.quality) {
                NetworkQuality.Good -> stringResource(R.string.quality_good) to YukColors.Success
                NetworkQuality.Fair -> stringResource(R.string.quality_fair) to YukColors.Warning
                NetworkQuality.Poor -> stringResource(R.string.quality_poor) to YukColors.Live
                NetworkQuality.Unknown -> "—" to YukColors.TextSecondary
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(qColor, PillShape))
                Spacer(Modifier.width(6.dp))
                Text(qText, style = YukType.Mono, color = qColor)
            }
        }
        Spacer(Modifier.width(Dimens.S1))
        val d = engine.device
        d.temperatureC?.let { t ->
            val c = if (d.isHot) YukColors.Warning else YukColors.TextSecondary
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Thermostat, null, tint = c, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.studio_temp_value, t.roundToInt()), style = YukType.Mono, color = c)
            }
        }
        d.batteryPercent?.let { p ->
            val c = if (d.isBatteryLow) YukColors.Warning else YukColors.TextSecondary
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BatteryStd, null, tint = c, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.studio_battery_value, p), style = YukType.Mono, color = c)
            }
        }
    }
}

@Composable
private fun StudioBottomBar(
    engine: EngineState,
    scenes: List<Pair<String, String>>,
    activeSceneId: String,
    subtitleOn: Boolean,
    muted: Boolean,
    overlayOn: Boolean,
    onSelectSource: (SourceKind) -> Unit,
    onSelectScene: (String) -> Unit,
    onToggleSubtitle: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleOverlay: () -> Unit,
    onOpenQuick: () -> Unit,
    onGoLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sources = SourceKind.entries
    val labels = listOf(
        stringResource(R.string.source_camera),
        stringResource(R.string.source_usb),
        stringResource(R.string.source_wifi),
    )
    Column(
        modifier
            .background(YukColors.Overlay)
            .navigationBarsPadding()
            .padding(horizontal = Dimens.S2, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.S1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentedPills(
                options = labels,
                selectedIndex = sources.indexOf(engine.source),
                onSelect = { onSelectSource(sources[it]) },
                enabled = !engine.switching,
            )
            Box(Modifier.size(width = 1.dp, height = 24.dp).background(YukColors.BorderStrong))
            scenes.forEach { (id, name) ->
                val sel = id == activeSceneId
                Box(
                    Modifier
                        .height(36.dp)
                        .background(if (sel) Color.White.copy(alpha = 0.12f) else Color.Transparent, PillShape)
                        .clickable { onSelectScene(id) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.uppercase(),
                        style = YukType.Label,
                        color = if (sel) YukColors.TextPrimary else YukColors.TextSecondary,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.S1),
        ) {
            PillIconButton(Icons.Outlined.Subtitles, stringResource(R.string.studio_toggle_subtitle), onToggleSubtitle, active = subtitleOn)
            PillIconButton(
                if (muted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                stringResource(if (muted) R.string.studio_unmute else R.string.studio_mute),
                onToggleMute,
                active = muted,
            )
            PillIconButton(Icons.Outlined.Layers, stringResource(R.string.studio_toggle_overlay), onToggleOverlay, active = overlayOn)
            PillIconButton(Icons.Outlined.Tune, stringResource(R.string.studio_quick_settings), onOpenQuick)
            Spacer(Modifier.width(Dimens.S1))
            GoLiveButton(
                isLive = engine.isActive,
                goLiveText = stringResource(R.string.action_go_live),
                endText = stringResource(R.string.action_end_live),
                onClick = onGoLive,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
