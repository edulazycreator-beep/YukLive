package com.yuklive.app.feature.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuklive.app.R
import com.yuklive.app.core.common.UiMessage
import com.yuklive.app.core.common.asString
import com.yuklive.app.core.settings.SourceKind
import com.yuklive.app.overlay.Scene
import com.yuklive.app.stream.StreamPhase
import com.yuklive.app.ui.components.HairlineDivider
import com.yuklive.app.ui.components.ListRow
import com.yuklive.app.ui.components.PillButton
import com.yuklive.app.ui.components.PillVariant
import com.yuklive.app.ui.components.StatusBanner
import com.yuklive.app.ui.components.YukTextField
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.SharpShape
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType
import com.yuklive.app.ui.util.ImmersiveMode
import com.yuklive.app.ui.util.KeepScreenOn
import com.yuklive.app.video.UvcStatus
import com.yuklive.app.video.network.NetworkStatus
import kotlinx.coroutines.delay

/**
 * Studio ala mini OBS.
 * Landscape: panggung (Preview/Program) di kiri, dock di kanan.
 * Portrait : panggung di atas, dock bertab di bawah.
 * Footer selalu ada: REC + GO LIVE.
 */
@Composable
fun StudioScreen(
    onExit: () -> Unit,
    onOpenDestinations: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSubtitle: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    PermissionGate {
        StudioContent(onExit, onOpenDestinations, onOpenSources, onOpenSubtitle, onOpenSettings)
    }
}

private sealed interface Sheet {
    data object AddSource : Sheet
    data class Properties(val key: String) : Sheet
}

@Composable
private fun StudioContent(
    onExit: () -> Unit,
    onOpenDestinations: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSubtitle: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: StudioViewModel = hiltViewModel()
    val scenes: SceneEditorViewModel = hiltViewModel()

    val engine by vm.engineState.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val elapsed by vm.elapsedMillis.collectAsStateWithLifecycle()
    val recordElapsed by vm.recordElapsed.collectAsStateWithLifecycle()
    val mixer by vm.mixerState.collectAsStateWithLifecycle()
    val destinations by vm.destinationState.collectAsStateWithLifecycle()
    val subtitleOn by vm.subtitleEnabled.collectAsStateWithLifecycle()
    val cam by vm.cameraState.collectAsStateWithLifecycle()
    val uvc by vm.uvcState.collectAsStateWithLifecycle()
    val net by vm.networkState.collectAsStateWithLifecycle()

    val doc by scenes.document.collectAsStateWithLifecycle()
    val studioMode by scenes.studioMode.collectAsStateWithLifecycle()
    val editingScene by scenes.editingScene.collectAsStateWithLifecycle()
    val selectedKey by scenes.selected.collectAsStateWithLifecycle()
    val liveStartedAt by scenes.liveStartedAt.collectAsStateWithLifecycle()

    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    var pickDestination by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Scene?>(null) }
    var message by remember { mutableStateOf<UiMessage?>(null) }

    val isLive = engine.isActive
    KeepScreenOn(enabled = isLive || engine.recording)
    ImmersiveMode(enabled = isLive)

    DisposableEffect(Unit) {
        vm.startMonitoring()
        onDispose { vm.stopMonitoring() }
    }
    LaunchedEffect(Unit) { vm.messages.collect { message = it } }
    LaunchedEffect(message?.id) {
        if (message != null) {
            delay(4500)
            message = null
        }
    }

    val programScene = doc.activeScene
    val outputRatio = engine.settings.outputWidth.toFloat() / engine.settings.outputHeight
    val items = scenes.sourceItems(editingScene)
    val videoStatus = when (editingScene.source) {
        SourceKind.Camera -> null
        SourceKind.Usb -> stringResource(if (uvc.status == UvcStatus.Ready) R.string.status_ready else R.string.status_not_ready)
        SourceKind.Wifi -> stringResource(if (net.status == NetworkStatus.Playing) R.string.status_ready else R.string.status_not_ready)
    }

    val onGoLive: () -> Unit = {
        if (isLive) confirmEnd = true else if (!vm.goLive()) pickDestination = true
    }

    // ------------------------------------------------------------ Panel
    val stage: @Composable (Modifier, Boolean) -> Unit = { m, landscape ->
        Box(m) {
            StudioStage(
                studioMode = studioMode,
                landscape = landscape,
                outputRatio = outputRatio,
                programScene = programScene,
                previewScene = editingScene,
                selectedLayerId = selectedKey,
                renderer = scenes.renderer,
                liveStartedAt = liveStartedAt,
                isLive = isLive,
                onAttach = vm::attachPreview,
                onDetach = vm::detachPreview,
                onSelectLayer = scenes::select,
                onTransformLayer = scenes::transformSelected,
                onFocus = { v, x, y -> vm.focusAt(v, x, y) },
                onZoom = vm::zoomBy,
                onTransition = scenes::transition,
                modifier = Modifier.fillMaxSize(),
            )
            Banners(engine.phase, engine.online, isLive, engine.throttled, message, { message = null },
                Modifier.align(Alignment.TopCenter).padding(Dimens.S1))
        }
    }
    val scenesPanel: @Composable (Modifier) -> Unit = { m ->
        ScenesPanel(
            doc = doc, editingSceneId = editingScene.id, studioMode = studioMode, isLive = isLive,
            onSelect = scenes::selectScene, onAdd = scenes::addScene, onRename = { renaming = it },
            onDelete = scenes::deleteScene, onMove = scenes::moveScene, modifier = m,
        )
    }
    val sourcesPanel: @Composable (Modifier) -> Unit = { m ->
        SourcesPanel(
            scene = editingScene, items = items, selectedKey = selectedKey, subtitleOn = subtitleOn, videoStatus = videoStatus,
            onSelect = scenes::select,
            onAdd = { sheet = Sheet.AddSource },
            onDelete = scenes::deleteLayer,
            onProperties = { sheet = Sheet.Properties(it.key) },
            onMove = { id, up -> scenes.moveLayer(id, up) },
            onToggleVisible = { item ->
                when (item) {
                    SourceItem.Subtitle -> vm.toggleSubtitle()
                    is SourceItem.Layer -> scenes.update(item.layer.id) { it.copy(visible = !it.visible) }
                    is SourceItem.Video -> Unit
                }
            },
            onToggleLock = { id -> scenes.update(id) { it.copy(locked = !it.locked) } },
            modifier = m,
        )
    }
    val audioPanel: @Composable (Modifier) -> Unit = { m ->
        AudioPanel(mixer, vm::setMasterMuted, vm::setChannelMuted, vm::setGain, onOpenSources, m)
    }
    val controlsPanel: @Composable (Modifier) -> Unit = { m ->
        ControlsPanel(
            engine = engine, destinations = destinations, studioMode = studioMode,
            onStudioMode = scenes::setStudioMode,
            onSelectDestination = vm::selectDestination,
            onManageDestinations = onOpenDestinations,
            onUpdateSettings = vm::updateSettings,
            onBitrate = vm::setLiveBitrate,
            onRecordWithLive = vm::setRecordWithLive,
            onOpenSubtitle = onOpenSubtitle, onOpenSources = onOpenSources, onOpenSettings = onOpenSettings,
            modifier = m,
        )
    }
    val tabLabels = mapOf(
        DockTab.Scenes to stringResource(R.string.dock_tab_scenes),
        DockTab.Sources to stringResource(R.string.dock_tab_sources),
        DockTab.Audio to stringResource(R.string.dock_tab_audio),
        DockTab.Controls to stringResource(R.string.dock_tab_controls),
    )
    var tab by rememberSaveable { mutableStateOf(DockTab.Sources) }

    // ------------------------------------------------------------ Tata letak
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(YukColors.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val landscape = maxWidth > maxHeight
        val boxW = maxWidth
        val boxH = maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    StudioStatusBar(engine, stats, elapsed, onExit)
                    stage(Modifier.weight(1f).fillMaxWidth(), true)
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(YukColors.Border))
                Column(Modifier.width(min(360.dp, boxW * 0.4f)).fillMaxHeight()) {
                    // Landscape: Scene + Source digabung di satu tab (seperti dock OBS).
                    val landscapeTab = if (tab == DockTab.Scenes) DockTab.Sources else tab
                    DockTabs(
                        listOf(DockTab.Sources, DockTab.Audio, DockTab.Controls), landscapeTab, { tab = it },
                        tabLabels + (DockTab.Sources to stringResource(R.string.dock_tab_scene_source)),
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (landscapeTab) {
                            DockTab.Audio -> audioPanel(Modifier.fillMaxSize())
                            DockTab.Controls -> controlsPanel(Modifier.fillMaxSize())
                            else -> Column(Modifier.fillMaxSize()) {
                                scenesPanel(Modifier.weight(0.4f).fillMaxWidth())
                                HairlineDivider()
                                sourcesPanel(Modifier.weight(0.6f).fillMaxWidth())
                            }
                        }
                    }
                    StudioFooter(engine, recordElapsed, vm::toggleRecording, onGoLive)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                StudioStatusBar(engine, stats, elapsed, onExit)
                val stageHeight = if (studioMode) boxH * 0.5f else min(boxW / outputRatio, boxH * 0.5f)
                stage(Modifier.fillMaxWidth().height(stageHeight), false)
                HairlineDivider()
                DockTabs(DockTab.entries, tab, { tab = it }, tabLabels)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        DockTab.Scenes -> scenesPanel(Modifier.fillMaxSize())
                        DockTab.Sources -> sourcesPanel(Modifier.fillMaxSize())
                        DockTab.Audio -> audioPanel(Modifier.fillMaxSize())
                        DockTab.Controls -> controlsPanel(Modifier.fillMaxSize())
                    }
                }
                StudioFooter(engine, recordElapsed, vm::toggleRecording, onGoLive)
            }
        }
    }

    // ------------------------------------------------------------ Sheet & dialog
    when (val sh = sheet) {
        Sheet.AddSource -> AddSourceSheet(
            currentVideo = editingScene.source,
            onPickVideo = scenes::setVideoSource,
            onAddLayer = scenes::addLayer,
            onDismiss = { sheet = null },
        )
        is Sheet.Properties -> {
            val item = items.firstOrNull { it.key == sh.key }
            if (item == null) {
                LaunchedEffect(sh.key) { sheet = null }
            } else {
                val title = when (item) {
                    SourceItem.Subtitle -> stringResource(R.string.subtitle_enable)
                    is SourceItem.Layer -> stringResource(R.string.overlay_section_properties, item.layer.name)
                    is SourceItem.Video -> stringResource(SceneEditorViewModel.sourceName(item.kind))
                }
                StudioSheet(title, onDismiss = { sheet = null }) {
                    when (item) {
                        SourceItem.Subtitle -> SubtitleSourceProperties(subtitleOn, vm::toggleSubtitle) {
                            sheet = null
                            onOpenSubtitle()
                        }
                        is SourceItem.Layer -> LayerProperties(item.layer, scenes)
                        is SourceItem.Video -> VideoSourceProperties(
                            kind = item.kind, cam = cam, uvc = uvc, net = net,
                            isProgram = editingScene.id == programScene.id,
                            onChangeSource = scenes::setVideoSource,
                            viewModel = vm,
                            onOpenSources = {
                                sheet = null
                                onOpenSources()
                            },
                            onPickUvcFormat = vm::selectUvcFormat,
                        )
                    }
                }
            }
        }
        null -> Unit
    }

    renaming?.let { scene ->
        var name by remember(scene.id) { mutableStateOf(scene.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            shape = SharpShape, containerColor = YukColors.Surface, tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.action_rename), style = YukType.Title) },
            text = { YukTextField(name, { name = it.take(24) }) },
            confirmButton = {
                PillButton(stringResource(R.string.action_save), onClick = {
                    scenes.renameScene(scene.id, name)
                    renaming = null
                }, height = 40.dp)
            },
            dismissButton = {
                PillButton(stringResource(R.string.action_cancel), onClick = { renaming = null }, variant = PillVariant.Secondary, height = 40.dp)
            },
        )
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            shape = SharpShape, containerColor = YukColors.Surface, tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.end_live_title), style = YukType.Title) },
            text = { Text(stringResource(R.string.end_live_body), style = YukType.Body, color = YukColors.TextSecondary) },
            confirmButton = {
                PillButton(stringResource(R.string.action_end_live), onClick = {
                    confirmEnd = false
                    vm.endLive()
                }, height = 40.dp)
            },
            dismissButton = {
                PillButton(stringResource(R.string.action_cancel), onClick = { confirmEnd = false }, variant = PillVariant.Secondary, height = 40.dp)
            },
        )
    }

    if (pickDestination) {
        AlertDialog(
            onDismissRequest = { pickDestination = false },
            shape = SharpShape, containerColor = YukColors.Surface, tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.studio_pick_destination), style = YukType.Title) },
            text = {
                Column {
                    if (destinations.destinations.isEmpty()) {
                        Text(stringResource(R.string.home_destinations_empty_body), style = YukType.Body, color = YukColors.TextSecondary)
                    }
                    destinations.destinations.forEachIndexed { i, d ->
                        if (i > 0) HairlineDivider()
                        ListRow(title = d.name, subtitle = d.platform.displayName, showChevron = true, onClick = {
                            pickDestination = false
                            vm.selectDestinationAndGo(d.id)
                        })
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
                PillButton(stringResource(R.string.action_cancel), onClick = { pickDestination = false }, variant = PillVariant.Secondary, height = 40.dp)
            },
        )
    }
}

@Composable
private fun Banners(
    phase: StreamPhase,
    online: Boolean,
    isLive: Boolean,
    throttled: Boolean,
    message: UiMessage?,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.S1)) {
        if (phase is StreamPhase.Reconnecting) {
            StatusBanner(stringResource(R.string.studio_reconnecting, phase.secondsLeft, phase.attempt), YukColors.Warning)
        }
        if (!online && isLive) StatusBanner(stringResource(R.string.msg_network_lost), YukColors.Warning)
        if (throttled) StatusBanner(stringResource(R.string.studio_throttled), YukColors.Warning)
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
                    onClick = onDismissMessage,
                )
            }
        }
    }
}
