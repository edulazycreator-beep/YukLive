package com.yuklive.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.PillShape
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType

/** Input teks bersudut tajam: latar #0A0A0A, border #1F1F1F (putih saat fokus diganti #333). */
@Composable
fun YukTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: String? = null,
    minLines: Int = 1,
) {
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            YukLabel(label)
            Spacer(Modifier.height(6.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(YukColors.Surface)
                .border(Dimens.Hairline, if (error != null) YukColors.Live else YukColors.BorderStrong)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (value.isEmpty() && placeholder != null) {
                Text(placeholder, style = YukType.Body, color = YukColors.TextDisabled)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                minLines = minLines,
                textStyle = YukType.Body.copy(color = YukColors.TextPrimary),
                cursorBrush = SolidColor(YukColors.TextPrimary),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, style = YukType.BodySmall, color = YukColors.Live)
        }
    }
}

/** Baris berisi judul + toggle pill. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = { PillToggle(checked, onCheckedChange, enabled = enabled) },
    )
}

/** Slider dengan label & nilai di atasnya. */
@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = YukType.Body, color = if (enabled) YukColors.TextPrimary else YukColors.TextDisabled, modifier = Modifier.weight(1f))
            Text(valueText, style = YukType.Mono, color = YukColors.TextSecondary)
        }
        YukSlider(value = value, onValueChange = onValueChange, valueRange = valueRange, enabled = enabled)
    }
}

/** Pilihan pill yang bisa digeser horizontal (untuk daftar panjang). */
@Composable
fun ScrollPills(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        SegmentedPills(options, selectedIndex, onSelect, enabled = enabled)
    }
}

/** Kumpulan warna untuk teks/aksen di stream. */
val StreamColors = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFCC00, 0xFFFF3B30, 0xFF34C759,
    0xFF0A84FF, 0xFFFF2D92, 0xFFFF9500, 0xFFAF52DE, 0xFF00C7BE,
).map { it.toInt() }

@Composable
fun ColorSwatches(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    colors: List<Int> = StreamColors,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        colors.forEach { c ->
            val isSel = (c or 0xFF000000.toInt()) == (selected or 0xFF000000.toInt())
            Box(
                Modifier
                    .size(32.dp)
                    .border(if (isSel) 2.dp else 1.dp, if (isSel) YukColors.TextPrimary else YukColors.BorderStrong, PillShape)
                    .padding(4.dp)
                    .background(Color(c), PillShape)
                    .clickable { onSelect(c) },
            )
        }
    }
}

/** Meter VU horizontal (hijau normal, kuning tinggi, merah clipping). */
@Composable
fun VuMeter(level: Float, modifier: Modifier = Modifier) {
    val l = level.coerceIn(0f, 1f)
    val color = when {
        l > 0.95f -> YukColors.Live
        l > 0.75f -> YukColors.Warning
        else -> YukColors.Success
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(YukColors.Border),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(l)
                .background(color),
        )
    }
}

/** Banner status (warna hanya untuk status). */
@Composable
fun StatusBanner(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .background(YukColors.Surface)
            .border(Dimens.Hairline, color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, PillShape))
        Spacer(Modifier.width(10.dp))
        Text(text, style = YukType.BodySmall, color = YukColors.TextPrimary)
    }
}
