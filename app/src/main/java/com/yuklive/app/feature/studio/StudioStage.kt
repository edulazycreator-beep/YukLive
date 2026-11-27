package com.yuklive.app.feature.studio

import android.os.SystemClock
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yuklive.app.R
import com.yuklive.app.overlay.OverlayLayer
import com.yuklive.app.overlay.OverlayRenderer
import com.yuklive.app.overlay.Scene
import com.yuklive.app.ui.components.PillButton
import com.yuklive.app.ui.components.YukLabel
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType

/** Ukuran terbesar dengan rasio [ratio] yang muat di (maxW, maxH). */
private fun fit(maxW: Dp, maxH: Dp, ratio: Float): Pair<Dp, Dp> {
    val wByH = maxH * ratio
    return if (wByH <= maxW) wByH to maxH else maxW to (maxW / ratio)
}

/**
 * Panggung Studio. Mode biasa: hanya PROGRAM (video asli dari pipeline).
 * Studio Mode: PREVIEW (scene yang disiapkan) + tombol TRANSISI + PROGRAM.
 */
@Composable
fun StudioStage(
    studioMode: Boolean,
    landscape: Boolean,
    outputRatio: Float,
    programScene: Scene,
    previewScene: Scene,
    selectedLayerId: String?,
    renderer: OverlayRenderer,
    liveStartedAt: Long?,
    isLive: Boolean,
    onAttach: (SurfaceView) -> Unit,
    onDetach: (SurfaceView) -> Unit,
    onSelectLayer: (String?) -> Unit,
    onTransformLayer: (Float, Float, Float, Float) -> Unit,
    onFocus: (SurfaceView, Float, Float) -> Unit,
    onZoom: (Float) -> Unit,
    onTransition: () -> Unit,
    transitionLabel: String,
    modifier: Modifier = Modifier,
) {
    if (!studioMode) {
        ProgramPanel(
            outputRatio, programScene.layers, selectedLayerId, renderer, editable = true, label = null, isLive = isLive,
            onAttach, onDetach, onSelectLayer, onTransformLayer, onFocus, onZoom, modifier,
        )
        return
    }
    // Studio Mode: PREVIEW dan PROGRAM selalu berdampingan (orientasi layar sudah
    // dikunci mengikuti stream, jadi berdampingan memberi panel terbesar).
    @Suppress("UNUSED_VARIABLE") val unused = landscape
    Column(modifier) {
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ScenePreviewPanel(
                outputRatio, previewScene, selectedLayerId, renderer, liveStartedAt,
                onSelectLayer, onTransformLayer, Modifier.weight(1f).fillMaxHeight(),
            )
            ProgramPanel(
                outputRatio, programScene.layers, null, renderer, editable = false,
                label = stringResource(R.string.studio_program_short), isLive = isLive,
                onAttach, onDetach, {}, { _, _, _, _ -> }, onFocus, onZoom,
                Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            PillButton(transitionLabel, onClick = onTransition, height = 36.dp)
        }
    }
}

/** Video asli (SurfaceView) dengan lapisan edit: pilih / geser / cubit / putar layer, atau fokus & zoom kamera. */
@Composable
private fun ProgramPanel(
    ratio: Float,
    layers: List<OverlayLayer>,
    selectedLayerId: String?,
    renderer: OverlayRenderer,
    editable: Boolean,
    label: String?,
    isLive: Boolean,
    onAttach: (SurfaceView) -> Unit,
    onDetach: (SurfaceView) -> Unit,
    onSelectLayer: (String?) -> Unit,
    onTransformLayer: (Float, Float, Float, Float) -> Unit,
    onFocus: (SurfaceView, Float, Float) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier,
) {
    val currentLayers by rememberUpdatedState(layers)
    val selected by rememberUpdatedState(selectedLayerId)
    val select by rememberUpdatedState(onSelectLayer)
    val transform by rememberUpdatedState(onTransformLayer)
    val focus by rememberUpdatedState(onFocus)
    val zoom by rememberUpdatedState(onZoom)
    val detach by rememberUpdatedState(onDetach)
    var surface by remember { mutableStateOf<SurfaceView?>(null) }

    BoxWithConstraints(modifier.background(YukColors.Black), contentAlignment = Alignment.Center) {
        val (w, h) = fit(maxWidth, maxHeight, ratio)
        Box(
            Modifier
                .size(w, h)
                .then(
                    if (label != null) Modifier.border(2.dp, if (isLive) YukColors.Live else YukColors.BorderStrong)
                    else Modifier,
                ),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> SurfaceView(ctx).also { surface = it; onAttach(it) } },
                onRelease = { detach(it) },
            )
            val sizePx = remember { floatArrayOf(0f, 0f) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(editable) {
                        detectTapGestures { pos ->
                            val hit = if (editable) currentLayers.lastOrNull { l ->
                                l.visible && renderer.bounds(l, sizePx[0], sizePx[1]).contains(pos.x, pos.y)
                            } else null
                            when {
                                hit != null -> select(hit.id)
                                selected != null -> select(null)
                                else -> surface?.let { focus(it, pos.x, pos.y) }
                            }
                        }
                    }
                    .pointerInput(editable) {
                        detectTransformGestures { _, pan, z, rot ->
                            val layerSelected = editable && selected != null &&
                                currentLayers.any { it.id == selected && !it.locked }
                            if (layerSelected && sizePx[0] > 0f) {
                                transform(pan.x / sizePx[0], pan.y / sizePx[1], z, rot)
                            } else if (z != 1f) {
                                zoom(z)
                            }
                        }
                    },
            ) {
                sizePx[0] = size.width
                sizePx[1] = size.height
                currentLayers.firstOrNull { it.id == selected }?.let { l ->
                    val r = renderer.bounds(l, size.width, size.height)
                    drawRect(
                        color = if (l.locked) YukColors.TextSecondary else YukColors.Live,
                        topLeft = Offset(r.left, r.top),
                        size = Size(r.width(), r.height()),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            if (label != null) PanelLabel(label, live = isLive, modifier = Modifier.align(Alignment.TopStart))
        }
    }
}

/** PREVIEW Studio Mode: tata letak overlay scene yang disiapkan (digambar dengan renderer yang sama). */
@Composable
private fun ScenePreviewPanel(
    ratio: Float,
    scene: Scene,
    selectedLayerId: String?,
    renderer: OverlayRenderer,
    liveStartedAt: Long?,
    onSelectLayer: (String?) -> Unit,
    onTransformLayer: (Float, Float, Float, Float) -> Unit,
    modifier: Modifier,
) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { now = SystemClock.elapsedRealtime() } }
    val currentLayers by rememberUpdatedState(scene.layers)
    val select by rememberUpdatedState(onSelectLayer)
    val transform by rememberUpdatedState(onTransformLayer)
    val sourceLabel = stringResource(SceneEditorViewModel.sourceName(scene.source))

    BoxWithConstraints(modifier.background(YukColors.Black), contentAlignment = Alignment.Center) {
        val (w, h) = fit(maxWidth, maxHeight, ratio)
        Box(
            Modifier
                .size(w, h)
                .clipToBounds()
                .background(YukColors.Surface)
                .border(2.dp, YukColors.Success),
        ) {
            Text(
                sourceLabel.uppercase(),
                style = YukType.Label,
                color = YukColors.TextDisabled,
                modifier = Modifier.align(Alignment.Center),
            )
            val sizePx = remember { floatArrayOf(0f, 0f) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { pos ->
                            val hit = currentLayers.lastOrNull { l ->
                                l.visible && renderer.bounds(l, sizePx[0], sizePx[1]).contains(pos.x, pos.y)
                            }
                            select(hit?.id)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, z, rot ->
                            if (sizePx[0] > 0f) transform(pan.x / sizePx[0], pan.y / sizePx[1], z, rot)
                        }
                    },
            ) {
                sizePx[0] = size.width
                sizePx[1] = size.height
                val t = now
                drawIntoCanvas { c -> renderer.draw(c.nativeCanvas, size.width, size.height, scene.layers, liveStartedAt, t) }
                scene.layers.firstOrNull { it.id == selectedLayerId }?.let { l ->
                    val r = renderer.bounds(l, size.width, size.height)
                    drawRect(
                        color = if (l.locked) YukColors.TextSecondary else YukColors.Live,
                        topLeft = Offset(r.left, r.top),
                        size = Size(r.width(), r.height()),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            PanelLabel(stringResource(R.string.studio_preview_label, scene.name), live = false, modifier = Modifier.align(Alignment.TopStart))
        }
    }
}

@Composable
private fun PanelLabel(text: String, live: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(6.dp)
            .background(if (live) YukColors.Live else YukColors.Overlay)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text.uppercase(),
            style = YukType.LabelSmall,
            color = YukColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
