package com.yuklive.app.feature.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuklive.app.R
import com.yuklive.app.core.settings.SourceKind
import com.yuklive.app.overlay.ClockMode
import com.yuklive.app.overlay.LayerAnim
import com.yuklive.app.overlay.LayerType
import com.yuklive.app.overlay.OverlayLayer
import com.yuklive.app.ui.components.ColorSwatches
import com.yuklive.app.ui.components.HairlineDivider
import com.yuklive.app.ui.components.LabeledSlider
import com.yuklive.app.ui.components.ListRow
import com.yuklive.app.ui.components.PillButton
import com.yuklive.app.ui.components.PillToggle
import com.yuklive.app.ui.components.PillVariant
import com.yuklive.app.ui.components.RowGroup
import com.yuklive.app.ui.components.ScrollPills
import com.yuklive.app.ui.components.SectionHeader
import com.yuklive.app.ui.components.StatusBanner
import com.yuklive.app.ui.components.ToggleRow
import com.yuklive.app.ui.components.YukLabel
import com.yuklive.app.ui.components.YukTextField
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.SharpShape
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType
import com.yuklive.app.video.CameraUiState
import com.yuklive.app.video.LensKind
import com.yuklive.app.video.UvcState
import com.yuklive.app.video.UvcStatus
import com.yuklive.app.video.WhiteBalance
import com.yuklive.app.video.network.NetworkInputState
import com.yuklive.app.video.network.NetworkStatus
import kotlin.math.roundToInt

/** Bottom sheet bersudut tajam untuk properti source / tambah source. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SharpShape,
        containerColor = YukColors.Surface,
        contentColor = YukColors.TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.5f),
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
            Text(title, style = YukType.Title)
            Spacer(Modifier.height(Dimens.S2))
            content()
        }
    }
}

/** Menu "+" di dock Sources: ganti sumber video scene atau tambah layer overlay. */
@Composable
fun AddSourceSheet(
    currentVideo: SourceKind,
    onPickVideo: (SourceKind) -> Unit,
    onAddLayer: (LayerType) -> Unit,
    onDismiss: () -> Unit,
    showVideo: Boolean = true,
) {
    StudioSheet(stringResource(R.string.dock_add_source), onDismiss) {
      if (showVideo) {
        YukLabel(stringResource(R.string.add_section_video))
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.add_video_hint), style = YukType.BodySmall, color = YukColors.TextSecondary)
        Spacer(Modifier.height(Dimens.S1))
        RowGroup {
            SourceKind.entries.forEachIndexed { i, k ->
                if (i > 0) HairlineDivider()
                ListRow(
                    title = stringResource(SceneEditorViewModel.sourceName(k)),
                    icon = videoIcon(k),
                    value = if (k == currentVideo) stringResource(R.string.add_current) else null,
                    onClick = { onPickVideo(k); onDismiss() },
                )
            }
        }
        Spacer(Modifier.height(Dimens.S2))
        YukLabel(stringResource(R.string.add_section_overlay))
        Spacer(Modifier.height(Dimens.S1))
      }
        RowGroup {
            LayerType.entries.forEachIndexed { i, t ->
                if (i > 0) HairlineDivider()
                ListRow(
                    title = stringResource(SceneEditorViewModel.layerTypeName(t)),
                    icon = layerIcon(t),
                    onClick = { onAddLayer(t); onDismiss() },
                )
            }
        }
    }
}

/** Properti sumber video: kontrol kamera, format capture card, atau status Wi-Fi. */
@Composable
fun VideoSourceProperties(
    kind: SourceKind,
    cam: CameraUiState,
    uvc: UvcState,
    net: NetworkInputState,
    isProgram: Boolean,
    onChangeSource: (SourceKind) -> Unit,
    viewModel: StudioViewModel,
    onOpenSources: () -> Unit,
    onPickUvcFormat: (com.yuklive.app.video.UvcFormat) -> Unit,
) {
    YukLabel(stringResource(R.string.add_section_video))
    Spacer(Modifier.height(6.dp))
    ScrollPills(SourceKind.entries.map { stringResource(SceneEditorViewModel.sourceName(it)) }, kind.ordinal, { onChangeSource(SourceKind.entries[it]) })
    Spacer(Modifier.height(Dimens.S2))
    if (!isProgram) {
        Text(stringResource(R.string.props_not_program), style = YukType.BodySmall, color = YukColors.TextSecondary)
        Spacer(Modifier.height(Dimens.S1))
    }
    when (kind) {
        SourceKind.Camera -> {
            val lensLabels = cam.lenses.map {
                stringResource(
                    when (it.kind) {
                        LensKind.Back -> R.string.lens_back
                        LensKind.UltraWide -> R.string.lens_ultrawide
                        LensKind.Tele -> R.string.lens_tele
                        LensKind.Front -> R.string.lens_front
                    },
                )
            }
            if (lensLabels.isNotEmpty()) {
                YukLabel(stringResource(R.string.quick_section_camera))
                Spacer(Modifier.height(6.dp))
                ScrollPills(lensLabels, cam.lenses.indexOf(cam.selected).coerceAtLeast(0), { viewModel.selectLens(cam.lenses[it]) })
            }
            if (cam.isOpen && cam.zoomRange.endInclusive > cam.zoomRange.start) {
                LabeledSlider(stringResource(R.string.quick_zoom), String.format(java.util.Locale.US, "%.1fx", cam.zoom),
                    cam.zoom, viewModel::setZoom, valueRange = cam.zoomRange)
            }
            if (cam.isOpen && cam.exposureRange.last > cam.exposureRange.first) {
                LabeledSlider(stringResource(R.string.quick_exposure), cam.exposure.toString(), cam.exposure.toFloat(),
                    { viewModel.setExposure(it.roundToInt()) },
                    valueRange = cam.exposureRange.first.toFloat()..cam.exposureRange.last.toFloat())
            }
            Spacer(Modifier.height(Dimens.S1))
            YukLabel(stringResource(R.string.quick_white_balance))
            Spacer(Modifier.height(6.dp))
            val wb = listOf(R.string.wb_auto, R.string.wb_daylight, R.string.wb_cloudy, R.string.wb_indoor, R.string.wb_fluorescent, R.string.wb_locked)
                .map { stringResource(it) }
            ScrollPills(wb, WhiteBalance.entries.indexOf(cam.whiteBalance), { viewModel.setWhiteBalance(WhiteBalance.entries[it]) })
            Spacer(Modifier.height(Dimens.S1))
            RowGroup {
                ToggleRow(stringResource(R.string.quick_torch), cam.torch, viewModel::setTorch, enabled = cam.torchAvailable)
                HairlineDivider()
                ToggleRow(stringResource(R.string.quick_focus_lock), cam.focusLocked, viewModel::setFocusLocked,
                    subtitle = stringResource(R.string.quick_focus_hint))
                HairlineDivider()
                ToggleRow(stringResource(R.string.quick_stabilization), cam.stabilization, viewModel::setStabilization)
            }
        }
        SourceKind.Usb -> {
            when (uvc.status) {
                UvcStatus.NoOtg -> StatusBanner(stringResource(R.string.usb_no_otg), YukColors.Live)
                UvcStatus.Idle -> StatusBanner(stringResource(R.string.usb_idle), YukColors.TextSecondary)
                UvcStatus.WaitingPermission -> StatusBanner(stringResource(R.string.usb_waiting_permission, uvc.deviceName.orEmpty()), YukColors.Warning)
                UvcStatus.Opening -> StatusBanner(stringResource(R.string.usb_opening), YukColors.Warning)
                UvcStatus.Ready -> StatusBanner(stringResource(R.string.usb_ready, uvc.deviceName.orEmpty()), YukColors.Success)
                UvcStatus.Error -> StatusBanner(stringResource(R.string.usb_error), YukColors.Live)
            }
            if (uvc.formats.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.S1))
                RowGroup {
                    uvc.formats.take(12).forEachIndexed { i, f ->
                        if (i > 0) HairlineDivider()
                        ListRow(title = f.label, onClick = { onPickUvcFormat(f) },
                            trailing = { PillToggle(f == uvc.selected, { onPickUvcFormat(f) }) })
                    }
                }
            }
        }
        SourceKind.Wifi -> {
            val (label, color) = when (net.status) {
                NetworkStatus.Idle -> stringResource(R.string.net_status_idle) to YukColors.TextSecondary
                NetworkStatus.Connecting -> stringResource(R.string.net_status_connecting) to YukColors.Warning
                NetworkStatus.WaitingSender -> stringResource(R.string.net_status_waiting) to YukColors.Warning
                NetworkStatus.Playing -> stringResource(R.string.net_status_playing) to YukColors.Success
                NetworkStatus.Reconnecting -> stringResource(R.string.net_status_reconnecting, net.attempt) to YukColors.Warning
                NetworkStatus.Error -> stringResource(R.string.net_status_error) to YukColors.Live
            }
            StatusBanner(label, color)
            Spacer(Modifier.height(Dimens.S1))
            Text(
                stringResource(R.string.props_wifi_summary, net.config.type.name, net.bitrateKbps, net.bufferMs),
                style = YukType.BodySmall, color = YukColors.TextSecondary,
            )
        }
    }
    Spacer(Modifier.height(Dimens.S2))
    PillButton(stringResource(R.string.props_open_sources), onClick = onOpenSources, variant = PillVariant.Secondary, modifier = Modifier.fillMaxWidth())
}

/** Properti subtitle (global, tampil di semua scene). */
@Composable
fun SubtitleSourceProperties(enabled: Boolean, onToggle: () -> Unit, onOpenSubtitle: () -> Unit) {
    RowGroup {
        ToggleRow(stringResource(R.string.subtitle_enable), enabled, { onToggle() })
    }
    Spacer(Modifier.height(Dimens.S1))
    Text(stringResource(R.string.props_subtitle_hint), style = YukType.BodySmall, color = YukColors.TextSecondary)
    Spacer(Modifier.height(Dimens.S2))
    PillButton(stringResource(R.string.props_open_subtitle), onClick = onOpenSubtitle, variant = PillVariant.Secondary, modifier = Modifier.fillMaxWidth())
}

@Composable
fun LayerProperties(layer: OverlayLayer, viewModel: SceneEditorViewModel) {
    val fonts by viewModel.fontOptions.collectAsStateWithLifecycle()
    val id = layer.id
    fun upd(t: (OverlayLayer) -> OverlayLayer) = viewModel.update(id, t)

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importImage(id, it) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.S1)) {
        YukTextField(layer.name, { v -> upd { it.copy(name = v) } }, label = stringResource(R.string.prop_name))

        when (layer.type) {
            LayerType.Text, LayerType.Ticker ->
                YukTextField(layer.text, { v -> upd { it.copy(text = v) } }, label = stringResource(R.string.prop_text),
                    singleLine = layer.type == LayerType.Ticker, minLines = if (layer.type == LayerType.Text) 2 else 1)
            LayerType.LowerThird -> {
                YukTextField(layer.text, { v -> upd { it.copy(text = v) } }, label = stringResource(R.string.prop_lt_name))
                YukTextField(layer.subText, { v -> upd { it.copy(subText = v) } }, label = stringResource(R.string.prop_lt_title))
            }
            LayerType.LiveBadge ->
                YukTextField(layer.text, { v -> upd { it.copy(text = v) } }, label = stringResource(R.string.prop_badge_text), placeholder = "LIVE")
            LayerType.Image -> PillButton(
                stringResource(if (layer.imagePath == null) R.string.prop_pick_image else R.string.prop_change_image),
                onClick = { imagePicker.launch("image/*") }, variant = PillVariant.Secondary,
            )
            LayerType.Clock -> {
                ScrollPills(
                    listOf(stringResource(R.string.clock_clock), stringResource(R.string.clock_timer), stringResource(R.string.clock_countdown)),
                    layer.clockMode.ordinal,
                    { i -> upd { it.copy(clockMode = ClockMode.entries[i], timerStartedAt = System.currentTimeMillis()) } },
                )
                if (layer.clockMode == ClockMode.Countdown) {
                    LabeledSlider(
                        label = stringResource(R.string.prop_countdown),
                        valueText = stringResource(R.string.minutes_value, layer.countdownSeconds / 60),
                        value = (layer.countdownSeconds / 60).toFloat(),
                        onValueChange = { v -> upd { it.copy(countdownSeconds = v.roundToInt().coerceAtLeast(1) * 60) } },
                        valueRange = 1f..120f,
                    )
                    PillButton(stringResource(R.string.prop_countdown_restart), onClick = { upd { it.copy(timerStartedAt = System.currentTimeMillis()) } },
                        variant = PillVariant.Secondary, height = 36.dp)
                }
                if (layer.clockMode == ClockMode.Timer) {
                    Text(stringResource(R.string.prop_timer_hint), style = YukType.BodySmall, color = YukColors.TextSecondary)
                }
            }
        }

        if (layer.type != LayerType.Image) {
            YukLabel(stringResource(R.string.prop_font))
            ScrollPills(fonts.map { it.label }, fonts.indexOfFirst { it.key == layer.fontKey }.coerceAtLeast(0), { i ->
                upd { it.copy(fontKey = fonts[i].key) }
            })
            ScrollPills(listOf("400", "500", "600", "700", "800", "900"),
                listOf(400, 500, 600, 700, 800, 900).indexOf(layer.fontWeight).coerceAtLeast(0), { i ->
                    upd { it.copy(fontWeight = 400 + i * 100) }
                })
            LabeledSlider(
                stringResource(R.string.prop_size), "${(layer.textSize * 1000).roundToInt()}",
                layer.textSize, { v -> upd { it.copy(textSize = v) } }, valueRange = 0.02f..0.2f,
            )
            YukLabel(stringResource(R.string.prop_text_color))
            ColorSwatches(layer.textColor, { c -> upd { it.copy(textColor = c) } })
            LabeledSlider(
                stringResource(R.string.prop_stroke), "${(layer.strokeWidth * 100).roundToInt()}%",
                layer.strokeWidth, { v -> upd { it.copy(strokeWidth = v) } }, valueRange = 0f..0.3f,
            )
            if (layer.strokeWidth > 0f) {
                YukLabel(stringResource(R.string.prop_stroke_color))
                ColorSwatches(layer.strokeColor, { c -> upd { it.copy(strokeColor = c) } })
            }
            RowGroup {
                ToggleRow(stringResource(R.string.prop_background), layer.bgEnabled, { on -> upd { it.copy(bgEnabled = on) } })
            }
            if (layer.bgEnabled) {
                YukLabel(stringResource(R.string.prop_background_color))
                ColorSwatches(layer.bgColor or 0xFF000000.toInt(), { c ->
                    upd { it.copy(bgColor = (c and 0x00FFFFFF) or 0xCC000000.toInt()) }
                })
            }
        }
        if (layer.type == LayerType.Ticker) {
            LabeledSlider(stringResource(R.string.prop_ticker_speed), "${(layer.tickerSpeed * 100).roundToInt()}",
                layer.tickerSpeed, { v -> upd { it.copy(tickerSpeed = v) } }, valueRange = 0.03f..0.5f)
        }

        LabeledSlider(stringResource(R.string.prop_opacity), "${(layer.opacity * 100).roundToInt()}%",
            layer.opacity, { v -> upd { it.copy(opacity = v) } })
        if (layer.type != LayerType.Ticker) {
            LabeledSlider(stringResource(R.string.prop_scale), String.format(java.util.Locale.US, "%.2fx", layer.scale),
                layer.scale, { v -> upd { it.copy(scale = v) } }, valueRange = 0.1f..4f)
            LabeledSlider(stringResource(R.string.prop_rotation), "${layer.rotation.roundToInt()}°",
                layer.rotation, { v -> upd { it.copy(rotation = v) } }, valueRange = -180f..180f)
        }

        val animLabels = listOf(R.string.anim_none, R.string.anim_fade, R.string.anim_slide, R.string.anim_pop).map { stringResource(it) }
        YukLabel(stringResource(R.string.prop_anim_in))
        ScrollPills(animLabels, layer.animIn.ordinal, { i -> upd { it.copy(animIn = LayerAnim.entries[i]) } })
        YukLabel(stringResource(R.string.prop_anim_out))
        ScrollPills(animLabels, layer.animOut.ordinal, { i -> upd { it.copy(animOut = LayerAnim.entries[i]) } })

        Spacer(Modifier.height(Dimens.S1))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.S1)) {
            PillButton(stringResource(R.string.action_duplicate), onClick = { viewModel.duplicate(id) },
                variant = PillVariant.Secondary, modifier = Modifier.weight(1f))
            PillButton(stringResource(R.string.action_delete), onClick = { viewModel.deleteLayer(id) },
                variant = PillVariant.Secondary, modifier = Modifier.weight(1f))
        }
    }
}
