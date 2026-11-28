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

import android.view.SurfaceHolder
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.PathEffect
import com.yuklive.app.core.settings.SourceKind
import com.yuklive.app.subtitle.SubtitleFrame
import com.yuklive.app.subtitle.SubtitleRenderer
import com.yuklive.app.subtitle.SubtitleStyle

/** Ukuran terbesar dengan rasio [ratio] yang muat di (maxW, maxH). */
private fun fit(maxW: Dp, maxH: Dp, ratio: Float): Pair<Dp, Dp> {
    val wByH = maxH * ratio
    return if (wByH <= maxW) wByH to maxH else maxW to (maxW / ratio)
}

const val SUBTITLE_KEY = "subtitle"

/** Semua yang dibutuhkan panggung untuk menggambar & menggeser subtitle. */
class StageSubtitle(
    val renderer: SubtitleRenderer,
    val frame: () -> SubtitleFrame,
    val style: () -> SubtitleStyle,
    val onMove: (Float, Float) -> Unit,
)

/**
 * Panggung Studio. Mode biasa: hanya PROGRAM (video asli dari pipeline, overlay dibakar GL).
 * Studio Mode: PREVIEW + PROGRAM sama-sama video langsung; overlay masing-masing
 * digambar di UI, sehingga isi Preview bisa disiapkan tanpa mengubah Program.
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
    subtitle: StageSubtitle,
    liveStartedAt: Long?,
    isLive: Boolean,
    onAttach: (SurfaceView) -> Unit,
    onDetach: (SurfaceView) -> Unit,
    onAttachPvw: (android.view.Surface, Int, Int) -> Unit,
    onDetachPvw: (android.view.Surface) -> Unit,
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
            outputRatio, programScene, selectedLayerId, renderer, subtitle, liveStartedAt,
            editable = true, drawOverlay = false, label = null, isLive = isLive,
            onAttach, onDetach, onSelectLayer, onTransformLayer, onFocus, onZoom, modifier,
        )
        return
    }
    @Suppress("UNUSED_VARIABLE") val unused = landscape
    Column(modifier) {
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PreviewPanel(
                outputRatio, previewScene, sameVideo = previewScene.source == programScene.source,
                selectedLayerId, renderer, subtitle, liveStartedAt,
                onAttachPvw, onDetachPvw, onSelectLayer, onTransformLayer, Modifier.weight(1f).fillMaxHeight(),
            )
            ProgramPanel(
                outputRatio, programScene, null, renderer, subtitle, liveStartedAt,
                editable = false, drawOverlay = true,
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

@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { now = SystemClock.elapsedRealtime() } }
    return now
}

/** Overlay scene + subtitle, digambar di UI (Studio Mode). */
private fun DrawScope.drawSceneOverlay(
    scene: Scene,
    renderer: OverlayRenderer,
    subtitle: StageSubtitle,
    liveStartedAt: Long?,
    now: Long,
) {
    drawIntoCanvas { c ->
        renderer.draw(c.nativeCanvas, size.width, size.height, scene.layers, liveStartedAt, now)
        if (scene.showSubtitle) {
            val f = subtitle.frame()
            if (f.visible) subtitle.renderer.draw(c.nativeCanvas, size.width, size.height, f, now)
        }
    }
}

/** Kotak seleksi layer / area subtitle. */
private fun DrawScope.drawSelection(
    scene: Scene,
    selected: String?,
    renderer: OverlayRenderer,
    subtitle: StageSubtitle,
) {
    if (selected == SUBTITLE_KEY) {
        val r = subtitle.renderer.area(size.width, size.height, subtitle.style())
        drawRect(
            color = YukColors.Live,
            topLeft = Offset(r.left, r.top),
            size = Size(r.width(), r.height()),
            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
        )
        return
    }
    scene.layers.firstOrNull { it.id == selected }?.let { l ->
        val r = renderer.bounds(l, size.width, size.height)
        drawRect(
            color = if (l.locked) YukColors.TextSecondary else YukColors.Live,
            topLeft = Offset(r.left, r.top),
            size = Size(r.width(), r.height()),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/** Layer yang tersentuh di titik (x, y); subtitle ikut kalau sedang tampil. */
private fun hitTest(
    scene: Scene, x: Float, y: Float, w: Float, h: Float,
    renderer: OverlayRenderer, subtitle: StageSubtitle,
): String? {
    scene.layers.lastOrNull { l -> l.visible && renderer.bounds(l, w, h).contains(x, y) }?.let { return it.id }
    if (scene.showSubtitle && subtitle.frame().visible && subtitle.renderer.area(w, h, subtitle.style()).contains(x, y)) {
        return SUBTITLE_KEY
    }
    return null
}

/** Video asli (SurfaceView) dengan lapisan edit: pilih / geser / cubit / putar layer & subtitle, atau fokus & zoom kamera. */
@Composable
private fun ProgramPanel(
    ratio: Float,
    scene: Scene,
    selectedLayerId: String?,
    renderer: OverlayRenderer,
    subtitle: StageSubtitle,
    liveStartedAt: Long?,
    editable: Boolean,
    drawOverlay: Boolean,
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
    val currentScene by rememberUpdatedState(scene)
    val selected by rememberUpdatedState(selectedLayerId)
    val select by rememberUpdatedState(onSelectLayer)
    val transform by rememberUpdatedState(onTransformLayer)
    val focus by rememberUpdatedState(onFocus)
    val zoom by rememberUpdatedState(onZoom)
    val detach by rememberUpdatedState(onDetach)
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    // Animasi selalu jalan untuk overlay UI; untuk mode biasa cukup saat subtitle dipilih.
    val now = if (drawOverlay || selectedLayerId == SUBTITLE_KEY) rememberNow() else 0L

    BoxWithConstraints(modifier.background(YukColors.Black), contentAlignment = Alignment.Center) {
        val (w, h) = fit(maxWidth, maxHeight, ratio)
        Box(
            Modifier
                .size(w, h)
                .clipToBounds()
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
                            val hit = if (editable) hitTest(currentScene, pos.x, pos.y, sizePx[0], sizePx[1], renderer, subtitle) else null
                            when {
                                hit != null -> select(hit)
                                selected != null -> select(null)
                                else -> surface?.let { focus(it, pos.x, pos.y) }
                            }
                        }
                    }
                    .pointerInput(editable) {
                        detectTransformGestures { _, pan, z, rot ->
                            val sel = selected
                            when {
                                editable && sel == SUBTITLE_KEY && sizePx[0] > 0f ->
                                    subtitle.onMove(pan.x / sizePx[0], pan.y / sizePx[1])
                                editable && sel != null && sizePx[0] > 0f &&
                                    currentScene.layers.any { it.id == sel && !it.locked } ->
                                    transform(pan.x / sizePx[0], pan.y / sizePx[1], z, rot)
                                z != 1f -> zoom(z)
                            }
                        }
                    },
            ) {
                sizePx[0] = size.width
                sizePx[1] = size.height
                @Suppress("UNUSED_VARIABLE") val tick = now
                if (drawOverlay) drawSceneOverlay(scene, renderer, subtitle, liveStartedAt, now)
                if (editable) drawSelection(scene, selectedLayerId, renderer, subtitle)
            }
            if (label != null) PanelLabel(label, live = isLive, modifier = Modifier.align(Alignment.TopStart))
        }
    }
}

/** PREVIEW Studio Mode: video langsung (kalau sumbernya sama dengan Program) + overlay scene yang disiapkan. */
@Composable
private fun PreviewPanel(
    ratio: Float,
    scene: Scene,
    sameVideo: Boolean,
    selectedLayerId: String?,
    renderer: OverlayRenderer,
    subtitle: StageSubtitle,
    liveStartedAt: Long?,
    onAttachPvw: (android.view.Surface, Int, Int) -> Unit,
    onDetachPvw: (android.view.Surface) -> Unit,
    onSelectLayer: (String?) -> Unit,
    onTransformLayer: (Float, Float, Float, Float) -> Unit,
    modifier: Modifier,
) {
    val now = rememberNow()
    val currentScene by rememberUpdatedState(scene)
    val selected by rememberUpdatedState(selectedLayerId)
    val select by rememberUpdatedState(onSelectLayer)
    val transform by rememberUpdatedState(onTransformLayer)
    val attach by rememberUpdatedState(onAttachPvw)
    val detach by rememberUpdatedState(onDetachPvw)
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
            when {
                sameVideo -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceView(ctx).also { v ->
                            v.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {}
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    attach(holder.surface, width, height)
                                }
                                override fun surfaceDestroyed(holder: SurfaceHolder) = detach(holder.surface)
                            })
                        }
                    },
                    onRelease = { v -> detach(v.holder.surface) },
                )
                scene.source == SourceKind.ColorBar -> Canvas(Modifier.fillMaxSize()) { drawColorBars() }
                else -> Text(
                    sourceLabel.uppercase(),
                    style = YukType.Label,
                    color = YukColors.TextDisabled,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            val sizePx = remember { floatArrayOf(0f, 0f) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { pos ->
                            select(hitTest(currentScene, pos.x, pos.y, sizePx[0], sizePx[1], renderer, subtitle))
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, z, rot ->
                            if (sizePx[0] <= 0f) return@detectTransformGestures
                            if (selected == SUBTITLE_KEY) subtitle.onMove(pan.x / sizePx[0], pan.y / sizePx[1])
                            else transform(pan.x / sizePx[0], pan.y / sizePx[1], z, rot)
                        }
                    },
            ) {
                sizePx[0] = size.width
                sizePx[1] = size.height
                drawSceneOverlay(scene, renderer, subtitle, liveStartedAt, now)
                drawSelection(scene, selectedLayerId, renderer, subtitle)
            }
            PanelLabel(stringResource(R.string.studio_preview_label, scene.name), live = false, modifier = Modifier.align(Alignment.TopStart))
        }
    }
}

/** Color bar versi UI (sama dengan input Color bar). */
private fun DrawScope.drawColorBars() {
    val top = longArrayOf(0xFFBFBFBF, 0xFFBFBF00, 0xFF00BFBF, 0xFF00BF00, 0xFFBF00BF, 0xFFBF0000, 0xFF0000BF)
    val mid = longArrayOf(0xFF0000BF, 0xFF131313, 0xFFBF00BF, 0xFF131313, 0xFF00BFBF, 0xFF131313, 0xFFBFBFBF)
    val bw = size.width / 7f
    val topH = size.height * 0.67f
    val midH = size.height * 0.08f
    top.forEachIndexed { i, c -> drawRect(Color(c), Offset(i * bw, 0f), Size(bw + 1f, topH)) }
    mid.forEachIndexed { i, c -> drawRect(Color(c), Offset(i * bw, topH), Size(bw + 1f, midH)) }
    val y0 = topH + midH
    val q = bw * 5f / 4f
    longArrayOf(0xFF00214C, 0xFFFFFFFF, 0xFF32006A, 0xFF131313).forEachIndexed { i, c ->
        drawRect(Color(c), Offset(i * q, y0), Size(q + 1f, size.height - y0))
    }
    drawRect(Color(0xFF131313), Offset(bw * 5f, y0), Size(bw * 2f, size.height - y0))
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
