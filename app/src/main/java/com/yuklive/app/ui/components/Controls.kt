package com.yuklive.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yuklive.app.ui.theme.Motion
import com.yuklive.app.ui.theme.PillShape
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType
import androidx.compose.ui.unit.sp

/** Toggle pill kecil: abu saat off, putih saat on. */
@Composable
fun PillToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val track by animateColorAsState(
        targetValue = when {
            !enabled -> YukColors.Border
            checked -> YukColors.TextPrimary
            else -> YukColors.BorderStrong
        },
        animationSpec = tween(Motion.Fast),
        label = "toggle-track",
    )
    val thumb by animateColorAsState(
        targetValue = when {
            !enabled -> YukColors.TextDisabled
            checked -> YukColors.Black
            else -> YukColors.TextSecondary
        },
        animationSpec = tween(Motion.Fast),
        label = "toggle-thumb",
    )
    val offset by animateDpAsState(
        targetValue = if (checked) 23.dp else 3.dp,
        animationSpec = tween(Motion.Fast),
        label = "toggle-offset",
    )
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(PillShape)
            .background(track)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .size(18.dp)
                .clip(PillShape)
                .background(thumb),
        )
    }
}

/** Slider dengan track 2px dan thumb kotak putih. */
@Composable
fun YukSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
) {
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestRange by rememberUpdatedState(valueRange)
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val thumbSize = 14.dp
    val thumbPx = with(LocalDensity.current) { thumbSize.toPx() }

    BoxWithConstraints(modifier.fillMaxWidth().height(32.dp)) {
        val widthPx = constraints.maxWidth.toFloat()

        fun update(x: Float) {
            val usable = (widthPx - thumbPx).coerceAtLeast(1f)
            val f = ((x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
            val r = latestRange
            latestOnChange(r.start + f * (r.endInclusive - r.start))
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled, widthPx) {
                    if (enabled) detectTapGestures { update(it.x) }
                }
                .pointerInput(enabled, widthPx) {
                    if (enabled) {
                        detectHorizontalDragGestures(
                            onDragStart = { update(it.x) },
                        ) { change, _ ->
                            change.consume()
                            update(change.position.x)
                        }
                    }
                },
        ) {
            val cy = size.height / 2f
            val start = thumbPx / 2f
            val end = size.width - thumbPx / 2f
            val x = start + (end - start) * fraction
            val stroke = 2.dp.toPx()
            drawLine(YukColors.BorderStrong, Offset(start, cy), Offset(end, cy), stroke)
            drawLine(
                if (enabled) YukColors.TextPrimary else YukColors.TextDisabled,
                Offset(start, cy), Offset(x, cy), stroke,
            )
            drawRect(
                color = if (enabled) YukColors.TextPrimary else YukColors.TextDisabled,
                topLeft = Offset(x - thumbPx / 2f, cy - thumbPx / 2f),
                size = Size(thumbPx, thumbPx),
            )
        }
    }
}

/** Pilihan bersegmen berbentuk pill kecil (mis. KAMERA / USB / WI-FI). */
@Composable
fun SegmentedPills(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val bg by animateColorAsState(
                if (selected && enabled) YukColors.TextPrimary else Color.Transparent,
                tween(Motion.Fast), label = "seg-bg",
            )
            val fg = when {
                !enabled -> YukColors.TextDisabled
                selected -> YukColors.Black
                else -> YukColors.TextPrimary
            }
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(PillShape)
                    .background(bg)
                    .border(1.dp, if (selected && enabled) YukColors.TextPrimary else YukColors.BorderStrong, PillShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled,
                        role = Role.RadioButton,
                    ) { onSelect(index) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label.uppercase(), style = YukType.LabelSmall.copy(fontSize = 11.sp), color = fg)
            }
        }
    }
}
