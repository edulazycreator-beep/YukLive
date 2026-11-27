package com.yuklive.app.feature.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuklive.app.R
import com.yuklive.app.audio.AudioChannel
import com.yuklive.app.core.settings.Resolution
import com.yuklive.app.core.settings.SourceKind
import com.yuklive.app.core.settings.StreamOrientation
import com.yuklive.app.core.settings.StreamSettings
import com.yuklive.app.core.settings.TransitionType
import com.yuklive.app.core.settings.VideoCodecOption
import com.yuklive.app.ui.components.HairlineDivider
import com.yuklive.app.ui.components.LabeledSlider
import com.yuklive.app.ui.components.ListRow
import com.yuklive.app.ui.components.PillToggle
import com.yuklive.app.ui.components.ScrollPills
import com.yuklive.app.ui.components.SectionHeader
import com.yuklive.app.ui.components.ToggleRow
import com.yuklive.app.ui.components.VuMeter
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.SharpShape
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType
import com.yuklive.app.video.LensKind
import com.yuklive.app.video.WhiteBalance
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioQuickSheet(
    viewModel: StudioViewModel,
    onDismiss: () -> Unit,
    onOpenDestinations: () -> Unit,
) {
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    val cam by viewModel.cameraState.collectAsStateWithLifecycle()
    val mixer by viewModel.mixerState.collectAsStateWithLifecycle()
    val dests by viewModel.destinationState.collectAsStateWithLifecycle()
    val settings = engine.settings
    val live = engine.isActive

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SharpShape,
        containerColor = YukColors.Surface,
        contentColor = YukColors.TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 4.dp).size(width = 32.dp, height = 2.dp).background(YukColors.BorderStrong))
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding)
                .padding(bottom = Dimens.S4),
        ) {
            Text(stringResource(R.string.studio_quick_settings), style = YukType.Title)

            // ---- Tujuan
            SectionHeader(stringResource(R.string.home_section_destinations))
            Group {
                if (dests.destinations.isEmpty()) {
                    ListRow(title = stringResource(R.string.home_destinations_add), showChevron = true, onClick = onOpenDestinations)
                }
                dests.destinations.forEachIndexed { i, d ->
                    if (i > 0) HairlineDivider()
                    ListRow(
                        title = d.name,
                        subtitle = d.platform.displayName,
                        enabled = !live,
                        onClick = { viewModel.selectDestination(d.id) },
                        trailing = { PillToggle(d.id == dests.selected?.id, { viewModel.selectDestination(d.id) }, enabled = !live) },
                    )
                }
            }

            // ---- Kamera
            if (engine.source == SourceKind.Camera && cam.lenses.isNotEmpty()) {
                SectionHeader(stringResource(R.string.quick_section_camera))
                val lensLabels = cam.lenses.map {
                    when (it.kind) {
                        LensKind.Back -> stringResource(R.string.lens_back)
                        LensKind.UltraWide -> stringResource(R.string.lens_ultrawide)
                        LensKind.Tele -> stringResource(R.string.lens_tele)
                        LensKind.Front -> stringResource(R.string.lens_front)
                    }
                }
                ScrollPills(lensLabels, cam.lenses.indexOf(cam.selected).coerceAtLeast(0), { viewModel.selectLens(cam.lenses[it]) })
                Spacer(Modifier.height(Dimens.S1))
                if (cam.zoomRange.endInclusive > cam.zoomRange.start) {
                    LabeledSlider(
                        label = stringResource(R.string.quick_zoom),
                        valueText = String.format(java.util.Locale.US, "%.1fx", cam.zoom),
                        value = cam.zoom,
                        onValueChange = viewModel::setZoom,
                        valueRange = cam.zoomRange,
                    )
                }
                if (cam.exposureRange.last > cam.exposureRange.first) {
                    LabeledSlider(
                        label = stringResource(R.string.quick_exposure),
                        valueText = cam.exposure.toString(),
                        value = cam.exposure.toFloat(),
                        onValueChange = { viewModel.setExposure(it.roundToInt()) },
                        valueRange = cam.exposureRange.first.toFloat()..cam.exposureRange.last.toFloat(),
                    )
                }
                Spacer(Modifier.height(Dimens.S1))
                Text(stringResource(R.string.quick_white_balance), style = YukType.Body)
                Spacer(Modifier.height(6.dp))
                val wbLabels = listOf(
                    R.string.wb_auto, R.string.wb_daylight, R.string.wb_cloudy, R.string.wb_indoor, R.string.wb_fluorescent, R.string.wb_locked,
                ).map { stringResource(it) }
                ScrollPills(wbLabels, WhiteBalance.entries.indexOf(cam.whiteBalance), { viewModel.setWhiteBalance(WhiteBalance.entries[it]) })
                Spacer(Modifier.height(Dimens.S1))
                Group {
                    ToggleRow(stringResource(R.string.quick_torch), cam.torch, viewModel::setTorch, enabled = cam.torchAvailable)
                    HairlineDivider()
                    ToggleRow(stringResource(R.string.quick_focus_lock), cam.focusLocked, viewModel::setFocusLocked,
                        subtitle = stringResource(R.string.quick_focus_hint))
                    HairlineDivider()
                    ToggleRow(stringResource(R.string.quick_stabilization), cam.stabilization, viewModel::setStabilization)
                }
            }

            // ---- Video
            SectionHeader(stringResource(R.string.quick_section_video), trailing = if (live) stringResource(R.string.quick_locked_live) else null)
            val resolutions = Resolution.entries
            ScrollPills(resolutions.map { it.label }, resolutions.indexOf(settings.resolution), { i ->
                val r = resolutions[i]
                viewModel.updateSettings { it.copy(resolution = r, videoBitrateKbps = StreamSettings.recommendedBitrate(r, it.fps)) }
            }, enabled = !live)
            Spacer(Modifier.height(Dimens.S1))
            ScrollPills(StreamSettings.FpsOptions.map { "$it fps" }, StreamSettings.FpsOptions.indexOf(settings.fps), { i ->
                viewModel.updateSettings { it.copy(fps = StreamSettings.FpsOptions[i]) }
            }, enabled = !live)
            Spacer(Modifier.height(Dimens.S1))
            ScrollPills(
                listOf(stringResource(R.string.orientation_landscape), stringResource(R.string.orientation_portrait)),
                settings.orientation.ordinal,
                { i -> viewModel.updateSettings { it.copy(orientation = StreamOrientation.entries[i]) } },
                enabled = !live,
            )
            Spacer(Modifier.height(Dimens.S1))
            ScrollPills(VideoCodecOption.entries.map { it.label }, settings.codec.ordinal, { i ->
                viewModel.updateSettings { it.copy(codec = VideoCodecOption.entries[i]) }
            }, enabled = !live)
            Spacer(Modifier.height(Dimens.S1))
            var bitrate by remember(settings.videoBitrateKbps) { mutableFloatStateOf(settings.videoBitrateKbps.toFloat()) }
            LabeledSlider(
                label = stringResource(R.string.settings_video_bitrate),
                valueText = stringResource(R.string.studio_bitrate_value, bitrate.roundToInt()),
                value = bitrate,
                onValueChange = { bitrate = (it / 100f).roundToInt() * 100f },
                valueRange = StreamSettings.MinVideoBitrate.toFloat()..StreamSettings.MaxVideoBitrate.toFloat(),
            )
            if (bitrate.roundToInt() != settings.videoBitrateKbps) {
                androidx.compose.runtime.LaunchedEffect(bitrate) {
                    kotlinx.coroutines.delay(400)
                    viewModel.setLiveBitrate(bitrate.roundToInt())
                }
            }
            Group {
                ToggleRow(stringResource(R.string.quick_adaptive), settings.adaptiveBitrate,
                    { on -> viewModel.updateSettings { it.copy(adaptiveBitrate = on) } },
                    subtitle = stringResource(R.string.quick_adaptive_hint))
                HairlineDivider()
                ToggleRow(stringResource(R.string.quick_record), engine.recording || (settings.recordLocally && !live),
                    viewModel::setRecording, subtitle = stringResource(R.string.quick_record_hint))
            }

            // ---- Transisi
            SectionHeader(stringResource(R.string.quick_section_transition))
            ScrollPills(
                listOf(stringResource(R.string.transition_cut), stringResource(R.string.transition_fade)),
                settings.transition.ordinal,
                { i -> viewModel.updateSettings { it.copy(transition = TransitionType.entries[i]) } },
            )

            // ---- Audio mixer
            SectionHeader(stringResource(R.string.quick_section_audio_mixer))
            Group {
                AudioChannel.entries.forEachIndexed { i, ch ->
                    if (i > 0) HairlineDivider()
                    val cs = mixer.channels.getValue(ch)
                    Column(Modifier.padding(horizontal = Dimens.S2, vertical = 10.dp)) {
                        ListRow(
                            title = stringResource(channelLabel(ch)),
                            subtitle = if (cs.active) null else stringResource(R.string.mixer_inactive),
                            modifier = Modifier.padding(0.dp),
                            trailing = { PillToggle(!cs.muted, { on -> viewModel.setChannelMuted(ch, !on) }) },
                        )
                        VuMeter(cs.level)
                        LabeledSlider(
                            label = stringResource(R.string.mixer_level),
                            valueText = "${(cs.gain * 100).roundToInt()}%",
                            value = cs.gain,
                            onValueChange = { viewModel.setGain(ch, it) },
                            valueRange = 0f..2f,
                        )
                    }
                }
            }
        }
    }
}

fun channelLabel(ch: AudioChannel): Int = when (ch) {
    AudioChannel.Mic -> R.string.mixer_mic
    AudioChannel.Usb -> R.string.mixer_usb
    AudioChannel.Network -> R.string.mixer_network
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(Dimens.Hairline, YukColors.Border),
        content = content,
    )
}
