package com.yuklive.app.feature.overlay

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.SystemClock
import com.yuklive.app.R
import com.yuklive.app.core.settings.SourceKind
import com.yuklive.app.overlay.ClockMode
import com.yuklive.app.overlay.LayerAnim
import com.yuklive.app.overlay.LayerType
import com.yuklive.app.overlay.OverlayDocument
import com.yuklive.app.overlay.OverlayLayer
import com.yuklive.app.ui.components.ColorSwatches
import com.yuklive.app.ui.components.HairlineDivider
import com.yuklive.app.ui.components.LabeledSlider
import com.yuklive.app.ui.components.ListRow
import com.yuklive.app.ui.components.PillButton
import com.yuklive.app.ui.components.PillVariant
import com.yuklive.app.ui.components.RowGroup
import com.yuklive.app.ui.components.ScreenHeader
import com.yuklive.app.ui.components.ScrollPills
import com.yuklive.app.ui.components.SectionHeader
import com.yuklive.app.ui.components.ToggleRow
import com.yuklive.app.ui.components.YukCard
import com.yuklive.app.ui.components.YukLabel
import com.yuklive.app.ui.components.YukTextField
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.PillShape
import com.yuklive.app.ui.theme.SharpShape
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType
import kotlin.math.roundToInt

@Composable
fun OverlayScreen(viewModel: OverlayViewModel = hiltViewModel()) {
    val doc by viewModel.document.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val portrait by viewModel.portrait.collectAsStateWithLifecycle()
    val liveStartedAt by viewModel.liveStartedAt.collectAsStateWithLifecycle()
    val scene = doc.activeScene
    val layers = scene.layers
    val selected = layers.firstOrNull { it.id == selectedId }
    var renaming by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.screen_overlay))
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding),
        ) {
            // ---------------- Scene
            SectionHeader(
                stringResource(R.string.overlay_section_scenes),
                trailing = stringResource(R.string.overlay_scene_count, doc.scenes.size, OverlayDocument.MAX_SCENES),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Dimens.S1),
            ) {
                doc.scenes.forEach { s ->
                    val sel = s.id == scene.id
                    Box(
                        Modifier
                            .height(36.dp)
                            .background(if (sel) YukColors.TextPrimary else YukColors.Black, PillShape)
                            .border(1.dp, if (sel) YukColors.TextPrimary else YukColors.BorderStrong, PillShape)
                            .clickable { viewModel.activateScene(s.id) }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(s.name.uppercase(), style = YukType.Label, color = if (sel) YukColors.Black else YukColors.TextPrimary)
                    }
                }
                if (doc.scenes.size < OverlayDocument.MAX_SCENES) {
                    Box(
                        Modifier.size(36.dp).border(1.dp, YukColors.BorderStrong, PillShape).clickable { viewModel.addScene() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Add, stringResource(R.string.overlay_add_scene), tint = YukColors.TextPrimary, modifier = Modifier.size(18.dp)) }
                }
            }
            Spacer(Modifier.height(Dimens.S1))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.S1)) {
                YukLabel(stringResource(R.string.overlay_scene_source))
                ScrollPills(
                    listOf(stringResource(R.string.source_camera), stringResource(R.string.source_usb), stringResource(R.string.source_wifi)),
                    scene.source.ordinal,
                    { viewModel.setSceneSource(scene.id, SourceKind.entries[it]) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(Dimens.S1))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.S1)) {
                PillButton(stringResource(R.string.action_rename), onClick = { renaming = true }, variant = PillVariant.Secondary, height = 36.dp)
                if (doc.scenes.size > 1) {
                    PillButton(stringResource(R.string.action_delete), onClick = { viewModel.deleteScene(scene.id) }, variant = PillVariant.Secondary, height = 36.dp)
                }
            }

            // ---------------- Editor
            SectionHeader(stringResource(R.string.overlay_section_preview))
            EditorCanvas(
                layers = layers,
                selectedId = selectedId,
                portrait = portrait,
                liveStartedAt = liveStartedAt,
                renderer = viewModel.renderer,
                onSelect = viewModel::select,
                onTransform = viewModel::transformSelected,
            )
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.overlay_editor_hint), style = YukType.BodySmall, color = YukColors.TextSecondary)

            // ---------------- Tambah layer
            SectionHeader(stringResource(R.string.overlay_add_layer))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Dimens.S1),
            ) {
                LayerType.entries.forEach { t ->
                    PillButton(stringResource(OverlayViewModel.layerTypeName(t)), onClick = { viewModel.addLayer(t) },
                        variant = PillVariant.Secondary, icon = Icons.Outlined.Add, height = 36.dp)
                }
            }

            // ---------------- Daftar layer
            SectionHeader(stringResource(R.string.overlay_section_layers))
            if (layers.isEmpty()) {
                YukCard {
                    Text(stringResource(R.string.overlay_layers_empty_title), style = YukType.TitleSmall)
                    Text(stringResource(R.string.overlay_layers_empty_body), style = YukType.BodySmall, color = YukColors.TextSecondary)
                }
            } else {
                RowGroup {
                    layers.reversed().forEachIndexed { i, l ->
                        if (i > 0) HairlineDivider()
                        LayerRow(
                            layer = l,
                            selected = l.id == selectedId,
                            onClick = { viewModel.select(if (l.id == selectedId) null else l.id) },
                            onToggleVisible = { viewModel.update(l.id) { it.copy(visible = !it.visible) } },
                            onToggleLock = { viewModel.update(l.id) { it.copy(locked = !it.locked) } },
                            onUp = { viewModel.move(l.id, up = true) },
                            onDown = { viewModel.move(l.id, up = false) },
                        )
                    }
                }
            }

            // ---------------- Properti
            if (selected != null) {
                SectionHeader(stringResource(R.string.overlay_section_properties, selected.name))
                LayerProperties(selected, viewModel)
            }
            Spacer(Modifier.height(Dimens.S4))
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(scene.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            shape = SharpShape,
            containerColor = YukColors.Surface,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.action_rename), style = YukType.Title) },
            text = { YukTextField(name, { name = it.take(24) }) },
            confirmButton = {
                PillButton(stringResource(R.string.action_save), onClick = {
                    viewModel.renameScene(scene.id, name)
                    renaming = false
                }, height = 40.dp)
            },
            dismissButton = {
                PillButton(stringResource(R.string.action_cancel), onClick = { renaming = false }, variant = PillVariant.Secondary, height = 40.dp)
            },
        )
    }
}

@Composable
private fun EditorCanvas(
    layers: List<OverlayLayer>,
    selectedId: String?,
    portrait: Boolean,
    liveStartedAt: Long?,
    renderer: com.yuklive.app.overlay.OverlayRenderer,
    onSelect: (String?) -> Unit,
    onTransform: (Float, Float, Float, Float) -> Unit,
) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { now = SystemClock.elapsedRealtime() }
    }
    val currentLayers by rememberUpdatedState(layers)
    val transform by rememberUpdatedState(onTransform)
    val select by rememberUpdatedState(onSelect)

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val ratio = if (portrait) 9f / 16f else 16f / 9f
        val w = if (portrait) maxWidth * 0.6f else maxWidth
        val h = w / ratio
        Box(
            Modifier
                .size(w, h)
                .background(YukColors.Surface)
                .border(Dimens.Hairline, YukColors.Border),
        ) {
            val canvasSize = remember { floatArrayOf(0f, 0f) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { pos ->
                            val hit = currentLayers.lastOrNull { l ->
                                renderer.bounds(l, canvasSize[0], canvasSize[1]).contains(pos.x, pos.y)
                            }
                            select(hit?.id)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            if (canvasSize[0] > 0f) transform(pan.x / canvasSize[0], pan.y / canvasSize[1], zoom, rotation)
                        }
                    },
            ) {
                canvasSize[0] = size.width
                canvasSize[1] = size.height
                // Garis bantu tengah (tidak ikut stream)
                drawLine(YukColors.Border, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1f)
                drawLine(YukColors.Border, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
                drawIntoCanvas { c ->
                    renderer.draw(c.nativeCanvas, size.width, size.height, layers, liveStartedAt, now)
                }
                layers.firstOrNull { it.id == selectedId }?.let { l ->
                    val r = renderer.bounds(l, size.width, size.height)
                    drawRect(
                        color = if (l.locked) YukColors.TextSecondary else YukColors.TextPrimary,
                        topLeft = Offset(r.left, r.top),
                        size = Size(r.width(), r.height()),
                        style = Stroke(width = 2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun LayerRow(
    layer: OverlayLayer,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleVisible: () -> Unit,
    onToggleLock: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) YukColors.Border else YukColors.Surface)
            .clickable(onClick = onClick)
            .padding(start = Dimens.S2, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(layer.name, style = YukType.Body, color = if (layer.visible) YukColors.TextPrimary else YukColors.TextDisabled)
            YukLabel(stringResource(OverlayViewModel.layerTypeName(layer.type)), color = YukColors.TextDisabled)
        }
        SmallIcon(if (layer.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, onToggleVisible)
        SmallIcon(if (layer.locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, onToggleLock)
        SmallIcon(Icons.Outlined.KeyboardArrowUp, onUp)
        SmallIcon(Icons.Outlined.KeyboardArrowDown, onDown)
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = YukColors.TextSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LayerProperties(layer: OverlayLayer, viewModel: OverlayViewModel) {
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
            PillButton(stringResource(R.string.action_delete), onClick = { viewModel.delete(id) },
                variant = PillVariant.Secondary, modifier = Modifier.weight(1f))
        }
    }
}
