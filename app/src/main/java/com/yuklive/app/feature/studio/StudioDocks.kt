package com.yuklive.app.feature.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yuklive.app.R
import com.yuklive.app.audio.AudioChannel
import com.yuklive.app.audio.MixerState
import com.yuklive.app.core.destination.DestinationState
import com.yuklive.app.core.settings.Resolution
import com.yuklive.app.core.settings.SourceKind
import com.yuklive.app.core.settings.StreamOrientation
import com.yuklive.app.core.settings.StreamSettings
import com.yuklive.app.core.settings.TransitionType
import com.yuklive.app.core.settings.VideoCodecOption
import com.yuklive.app.overlay.LayerType
import com.yuklive.app.overlay.OverlayDocument
import com.yuklive.app.overlay.Scene
import com.yuklive.app.stream.EngineState
import com.yuklive.app.stream.NetworkQuality
import com.yuklive.app.stream.StreamPhase
import com.yuklive.app.stream.StreamStats
import com.yuklive.app.ui.components.BlinkingDot
import com.yuklive.app.ui.components.GoLiveButton
import com.yuklive.app.ui.components.HairlineDivider
import com.yuklive.app.ui.components.LabeledSlider
import com.yuklive.app.ui.components.ListRow
import com.yuklive.app.ui.components.PillButton
import com.yuklive.app.ui.components.PillToggle
import com.yuklive.app.ui.components.PillVariant
import com.yuklive.app.ui.components.ScrollPills
import com.yuklive.app.ui.components.SectionHeader
import com.yuklive.app.ui.components.StatusBadge
import com.yuklive.app.ui.components.ToggleRow
import com.yuklive.app.ui.components.VuMeter
import com.yuklive.app.ui.components.YukLabel
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.PillShape
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType
import com.yuklive.app.ui.util.formatDuration
import kotlin.math.roundToInt

enum class DockTab { Scenes, Sources, Audio, Controls }

fun channelLabel(ch: AudioChannel): Int = when (ch) {
    AudioChannel.Mic -> R.string.mixer_mic
    AudioChannel.Usb -> R.string.mixer_usb
    AudioChannel.Network -> R.string.mixer_network
}

// =============================================================== Status bar

@Composable
fun StudioStatusBar(
    engine: EngineState,
    stats: StreamStats,
    elapsedMs: Long,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(YukColors.Black),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clickable(onClick = onExit), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), tint = YukColors.TextPrimary, modifier = Modifier.size(20.dp))
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(end = Dimens.S2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val (badge, color, active) = when (engine.phase) {
                StreamPhase.Live -> Triple(stringResource(R.string.status_on_air), YukColors.Live, true)
                StreamPhase.Connecting -> Triple(stringResource(R.string.status_connecting), YukColors.Warning, true)
                is StreamPhase.Reconnecting -> Triple(stringResource(R.string.status_reconnecting), YukColors.Warning, true)
                StreamPhase.Idle -> Triple(stringResource(R.string.status_offline), YukColors.Live, false)
            }
            StatusBadge(badge, active = active, activeColor = color)
            Text(formatDuration(elapsedMs), style = YukType.Mono, color = YukColors.TextPrimary)
            Stat(stringResource(R.string.studio_bitrate_value, stats.bitrateKbps))
            if (engine.isActive) {
                Stat(stringResource(R.string.studio_fps_value, stats.fps))
                Stat(stringResource(R.string.studio_dropped_value, stats.droppedFrames), if (stats.droppedFrames > 0) YukColors.Warning else YukColors.TextSecondary)
                val (q, qc) = when (stats.quality) {
                    NetworkQuality.Good -> stringResource(R.string.quality_good) to YukColors.Success
                    NetworkQuality.Fair -> stringResource(R.string.quality_fair) to YukColors.Warning
                    NetworkQuality.Poor -> stringResource(R.string.quality_poor) to YukColors.Live
                    NetworkQuality.Unknown -> "—" to YukColors.TextSecondary
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(qc, PillShape))
                    Spacer(Modifier.width(6.dp))
                    Stat(q, qc)
                }
            }
            val d = engine.device
            d.temperatureC?.let { t ->
                val c = if (d.isHot) YukColors.Warning else YukColors.TextSecondary
                IconStat(Icons.Outlined.Thermostat, stringResource(R.string.studio_temp_value, t.roundToInt()), c)
            }
            d.batteryPercent?.let { p ->
                val c = if (d.isBatteryLow) YukColors.Warning else YukColors.TextSecondary
                IconStat(Icons.Outlined.BatteryStd, stringResource(R.string.studio_battery_value, p), c)
            }
        }
    }
}

@Composable
private fun Stat(text: String, color: androidx.compose.ui.graphics.Color = YukColors.TextSecondary) {
    Text(text, style = YukType.Mono, color = color)
}

@Composable
private fun IconStat(icon: ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Text(text, style = YukType.Mono, color = color)
    }
}

// =============================================================== Dock

@Composable
fun DockTabs(tabs: List<DockTab>, selected: DockTab, onSelect: (DockTab) -> Unit, labels: Map<DockTab, String>) {
    Row(Modifier.fillMaxWidth().height(40.dp)) {
        tabs.forEach { t ->
            val sel = t == selected
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                YukLabel(labels.getValue(t), color = if (sel) YukColors.TextPrimary else YukColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.6f).height(2.dp).background(if (sel) YukColors.TextPrimary else YukColors.Black))
            }
        }
    }
    HairlineDivider()
}

/** Header panel dock dengan tombol ikon kecil di kanan (seperti toolbar dock OBS). */
@Composable
fun DockHeader(title: String, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(start = Dimens.S2, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        YukLabel(title, modifier = Modifier.weight(1f))
        actions()
    }
    HairlineDivider()
}

@Composable
fun DockIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) YukColors.TextPrimary else YukColors.TextDisabled, modifier = Modifier.size(18.dp))
    }
}

// =============================================================== Scenes

@Composable
fun ScenesPanel(
    doc: OverlayDocument,
    editingSceneId: String,
    studioMode: Boolean,
    isLive: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onRename: (Scene) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editing = doc.scenes.firstOrNull { it.id == editingSceneId }
    Column(modifier) {
        DockHeader(stringResource(R.string.dock_scenes, doc.scenes.size, OverlayDocument.MAX_SCENES)) {
            DockIcon(Icons.Outlined.Add, stringResource(R.string.overlay_add_scene), enabled = doc.scenes.size < OverlayDocument.MAX_SCENES, onClick = onAdd)
            DockIcon(Icons.Outlined.Remove, stringResource(R.string.action_delete), enabled = doc.scenes.size > 1 && editing != null) { editing?.let { onDelete(it.id) } }
            DockIcon(Icons.Outlined.Edit, stringResource(R.string.action_rename), enabled = editing != null) { editing?.let(onRename) }
            DockIcon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.action_move_up), enabled = editing != null) { editing?.let { onMove(it.id, true) } }
            DockIcon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.action_move_down), enabled = editing != null) { editing?.let { onMove(it.id, false) } }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(doc.scenes, key = { it.id }) { s ->
                val isProgram = s.id == doc.activeScene.id
                val isEditing = s.id == editingSceneId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (isEditing) YukColors.Border else YukColors.Black)
                        .clickable { onSelect(s.id) }
                        .padding(horizontal = Dimens.S2, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.name, style = YukType.Body, modifier = Modifier.weight(1f),
                        color = if (isEditing || isProgram) YukColors.TextPrimary else YukColors.TextSecondary)
                    if (studioMode && isEditing && !isProgram) SceneTag(stringResource(R.string.tag_preview), YukColors.TextSecondary)
                    if (isProgram) SceneTag(stringResource(R.string.tag_program), if (isLive) YukColors.Live else YukColors.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun SceneTag(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
        Box(Modifier.size(6.dp).background(color, PillShape))
        Spacer(Modifier.width(4.dp))
        YukLabel(text, color = color)
    }
}

// =============================================================== Sources

@Composable
fun SourcesPanel(
    scene: Scene,
    items: List<SourceItem>,
    selectedKey: String?,
    subtitleOn: Boolean,
    videoStatus: String?,
    onSelect: (String?) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onProperties: (SourceItem) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onToggleVisible: (SourceItem) -> Unit,
    onToggleLock: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = items.firstOrNull { it.key == selectedKey }
    val layerSelected = selected as? SourceItem.Layer
    Column(modifier) {
        DockHeader(stringResource(R.string.dock_sources, scene.name)) {
            DockIcon(Icons.Outlined.Add, stringResource(R.string.dock_add_source), onClick = onAdd)
            DockIcon(Icons.Outlined.Remove, stringResource(R.string.action_delete), enabled = layerSelected != null) {
                layerSelected?.let { onDelete(it.layer.id) }
            }
            DockIcon(Icons.Outlined.Settings, stringResource(R.string.dock_properties), enabled = selected != null) {
                selected?.let(onProperties)
            }
            DockIcon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.action_move_up), enabled = layerSelected != null) {
                layerSelected?.let { onMove(it.layer.id, true) }
            }
            DockIcon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.action_move_down), enabled = layerSelected != null) {
                layerSelected?.let { onMove(it.layer.id, false) }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(items, key = { it.key }) { item ->
                val sel = item.key == selectedKey
                val (icon, title, subtitle) = when (item) {
                    SourceItem.Subtitle -> Triple(Icons.Outlined.Subtitles, stringResource(R.string.subtitle_enable), null)
                    is SourceItem.Layer -> Triple(layerIcon(item.layer.type), item.layer.name,
                        stringResource(SceneEditorViewModel.layerTypeName(item.layer.type)))
                    is SourceItem.Video -> Triple(videoIcon(item.kind), stringResource(SceneEditorViewModel.sourceName(item.kind)), videoStatus)
                }
                val visible = when (item) {
                    SourceItem.Subtitle -> subtitleOn
                    is SourceItem.Layer -> item.layer.visible
                    is SourceItem.Video -> true
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (sel) YukColors.Border else YukColors.Black)
                        .clickable { onSelect(if (sel) null else item.key) }
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item is SourceItem.Video) {
                        Box(Modifier.size(36.dp))
                    } else {
                        DockIcon(
                            if (visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            stringResource(R.string.dock_toggle_visible),
                        ) { onToggleVisible(item) }
                    }
                    Icon(icon, null, tint = if (visible) YukColors.TextPrimary else YukColors.TextDisabled, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = YukType.Body, color = if (visible) YukColors.TextPrimary else YukColors.TextDisabled, maxLines = 1)
                        if (subtitle != null) Text(subtitle, style = YukType.BodySmall, color = YukColors.TextSecondary, maxLines = 1)
                    }
                    if (item is SourceItem.Layer) {
                        DockIcon(
                            if (item.layer.locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                            stringResource(R.string.dock_toggle_lock),
                        ) { onToggleLock(item.layer.id) }
                    }
                    DockIcon(Icons.Outlined.Settings, stringResource(R.string.dock_properties)) { onProperties(item) }
                }
            }
        }
    }
}

fun layerIcon(type: LayerType): ImageVector = when (type) {
    LayerType.Text -> Icons.Outlined.TextFields
    LayerType.Image -> Icons.Outlined.Image
    LayerType.LowerThird -> Icons.Outlined.ViewDay
    LayerType.Clock -> Icons.Outlined.Schedule
    LayerType.LiveBadge -> Icons.Outlined.FiberManualRecord
    LayerType.Ticker -> Icons.AutoMirrored.Outlined.ShortText
}

fun videoIcon(kind: SourceKind): ImageVector = when (kind) {
    SourceKind.Camera -> Icons.Outlined.CameraAlt
    SourceKind.Usb -> Icons.Outlined.Usb
    SourceKind.Wifi -> Icons.Outlined.Wifi
}

// =============================================================== Audio

@Composable
fun AudioPanel(
    mixer: MixerState,
    onMasterMute: (Boolean) -> Unit,
    onChannelMute: (AudioChannel, Boolean) -> Unit,
    onGain: (AudioChannel, Float) -> Unit,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        DockHeader(stringResource(R.string.dock_audio)) {
            DockIcon(Icons.Outlined.Settings, stringResource(R.string.screen_sources), onClick = onOpenSources)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            ToggleRow(stringResource(R.string.dock_master_mute), mixer.masterMuted, onMasterMute)
            HairlineDivider()
            AudioChannel.entries.forEach { ch ->
                val cs = mixer.channels.getValue(ch)
                Column(Modifier.padding(horizontal = Dimens.S2, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(channelLabel(ch)), style = YukType.Body, modifier = Modifier.weight(1f),
                            color = if (cs.active) YukColors.TextPrimary else YukColors.TextSecondary)
                        Text("${(cs.gain * 100).roundToInt()}%", style = YukType.Mono, color = YukColors.TextSecondary)
                        Spacer(Modifier.width(Dimens.S1))
                        PillToggle(!cs.muted, { on -> onChannelMute(ch, !on) })
                    }
                    Spacer(Modifier.height(6.dp))
                    VuMeter(if (cs.muted || mixer.masterMuted) 0f else cs.level)
                    com.yuklive.app.ui.components.YukSlider(value = cs.gain, onValueChange = { onGain(ch, it) }, valueRange = 0f..2f)
                }
                HairlineDivider()
            }
        }
    }
}

// =============================================================== Kontrol

@Composable
fun ControlsPanel(
    engine: EngineState,
    destinations: DestinationState,
    studioMode: Boolean,
    onStudioMode: (Boolean) -> Unit,
    onSelectDestination: (String) -> Unit,
    onManageDestinations: () -> Unit,
    onUpdateSettings: ((StreamSettings) -> StreamSettings) -> Unit,
    onBitrate: (Int) -> Unit,
    onRecordWithLive: (Boolean) -> Unit,
    onOpenSubtitle: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = engine.settings
    val locked = engine.isActive || engine.recording
    Column(modifier) {
        DockHeader(stringResource(R.string.dock_controls))
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.S2),
        ) {
            Group {
                ToggleRow(stringResource(R.string.studio_mode), studioMode, onStudioMode, subtitle = stringResource(R.string.studio_mode_hint))
            }
            SectionHeader(stringResource(R.string.quick_section_transition))
            ScrollPills(
                listOf(stringResource(R.string.transition_cut), stringResource(R.string.transition_fade)),
                s.transition.ordinal, { i -> onUpdateSettings { it.copy(transition = TransitionType.entries[i]) } },
            )

            SectionHeader(stringResource(R.string.home_section_destinations))
            Group {
                destinations.destinations.forEachIndexed { i, d ->
                    if (i > 0) HairlineDivider()
                    ListRow(
                        title = d.name, subtitle = d.platform.displayName, enabled = !engine.isActive,
                        onClick = { onSelectDestination(d.id) },
                        trailing = { PillToggle(d.id == destinations.selected?.id, { onSelectDestination(d.id) }, enabled = !engine.isActive) },
                    )
                }
                if (destinations.destinations.isNotEmpty()) HairlineDivider()
                ListRow(stringResource(R.string.home_manage_destinations), showChevron = true, onClick = onManageDestinations)
            }

            SectionHeader(stringResource(R.string.quick_section_video), trailing = if (locked) stringResource(R.string.quick_locked_live) else null)
            ScrollPills(Resolution.entries.map { it.label }, s.resolution.ordinal, { i ->
                val r = Resolution.entries[i]
                onUpdateSettings { it.copy(resolution = r, videoBitrateKbps = StreamSettings.recommendedBitrate(r, it.fps)) }
            }, enabled = !locked)
            Spacer(Modifier.height(Dimens.S1))
            ScrollPills(StreamSettings.FpsOptions.map { "$it fps" }, StreamSettings.FpsOptions.indexOf(s.fps).coerceAtLeast(0), { i ->
                onUpdateSettings { it.copy(fps = StreamSettings.FpsOptions[i]) }
            }, enabled = !locked)
            Spacer(Modifier.height(Dimens.S1))
            ScrollPills(
                listOf(stringResource(R.string.orientation_landscape), stringResource(R.string.orientation_portrait)),
                s.orientation.ordinal, { i -> onUpdateSettings { it.copy(orientation = StreamOrientation.entries[i]) } }, enabled = !locked,
            )
            Spacer(Modifier.height(Dimens.S1))
            ScrollPills(VideoCodecOption.entries.map { it.label }, s.codec.ordinal, { i ->
                onUpdateSettings { it.copy(codec = VideoCodecOption.entries[i]) }
            }, enabled = !locked)
            var bitrate by remember(s.videoBitrateKbps) { mutableFloatStateOf(s.videoBitrateKbps.toFloat()) }
            LabeledSlider(
                stringResource(R.string.settings_video_bitrate),
                stringResource(R.string.studio_bitrate_value, bitrate.roundToInt()),
                bitrate, { bitrate = (it / 100f).roundToInt() * 100f },
                valueRange = StreamSettings.MinVideoBitrate.toFloat()..StreamSettings.MaxVideoBitrate.toFloat(),
            )
            LaunchedEffect(bitrate) {
                kotlinx.coroutines.delay(400)
                if (bitrate.roundToInt() != s.videoBitrateKbps) onBitrate(bitrate.roundToInt())
            }
            Group {
                ToggleRow(stringResource(R.string.quick_adaptive), s.adaptiveBitrate,
                    { on -> onUpdateSettings { it.copy(adaptiveBitrate = on) } }, subtitle = stringResource(R.string.quick_adaptive_hint))
                HairlineDivider()
                ToggleRow(stringResource(R.string.dock_record_with_live), s.recordLocally, onRecordWithLive,
                    subtitle = stringResource(R.string.quick_record_hint))
            }

            SectionHeader(stringResource(R.string.settings_section_manage))
            Group {
                ListRow(stringResource(R.string.screen_subtitle), icon = Icons.Outlined.Subtitles, showChevron = true, onClick = onOpenSubtitle)
                HairlineDivider()
                ListRow(stringResource(R.string.screen_sources), icon = Icons.Outlined.Usb, showChevron = true, onClick = onOpenSources)
                HairlineDivider()
                ListRow(stringResource(R.string.screen_settings), icon = Icons.Outlined.Settings, showChevron = true, onClick = onOpenSettings)
            }
            Spacer(Modifier.height(Dimens.S3))
        }
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(YukColors.Surface), content = content)
}

// =============================================================== Footer

@Composable
fun StudioFooter(
    engine: EngineState,
    recordElapsed: Long,
    onRecord: () -> Unit,
    onGoLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(YukColors.Black)) {
        HairlineDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.S2, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.S1),
        ) {
            PillButton(
                text = if (engine.recording) formatDuration(recordElapsed) else stringResource(R.string.action_record),
                onClick = onRecord,
                variant = PillVariant.Secondary,
                height = 52.dp,
                leading = {
                    if (engine.recording) BlinkingDot(YukColors.Live, 10.dp)
                    else Box(Modifier.size(10.dp).background(YukColors.Live, PillShape))
                },
            )
            GoLiveButton(
                isLive = engine.isActive,
                goLiveText = stringResource(R.string.action_go_live),
                endText = stringResource(R.string.action_end_live),
                onClick = onGoLive,
                height = 52.dp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
