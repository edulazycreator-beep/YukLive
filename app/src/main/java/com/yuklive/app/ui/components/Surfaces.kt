package com.yuklive.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yuklive.app.R
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.PillShape
import com.yuklive.app.ui.theme.SharpShape
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType

/** Kartu/panel: #0A0A0A, border 1px #1F1F1F, sudut tajam. */
@Composable
fun YukCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = Dimens.S2,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SharpShape)
            .background(YukColors.Surface)
            .border(Dimens.Hairline, YukColors.Border, SharpShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** Label kapital 500 + letter-spacing 0.08em. */
@Composable
fun YukLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = YukColors.TextSecondary,
) {
    Text(
        text = text.uppercase(), style = YukType.Label, color = color, modifier = modifier,
        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Dimens.S3, bottom = Dimens.S1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        YukLabel(text, Modifier.weight(1f))
        if (trailing != null) YukLabel(trailing, color = YukColors.TextDisabled)
    }
}

/** Header layar dengan tombol kembali opsional. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = if (onBack != null) Dimens.S1 else Dimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(PillShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = YukColors.TextPrimary,
                )
            }
            Spacer(Modifier.width(Dimens.S1))
        }
        Text(title, style = YukType.Title, color = YukColors.TextPrimary)
    }
}

/** Baris daftar di dalam panel: ikon, judul, subjudul, nilai/chevron. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    showChevron: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val titleColor = if (enabled) YukColors.TextPrimary else YukColors.TextDisabled
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Dimens.S2, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = titleColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Dimens.S2))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = YukType.Body, color = titleColor)
            if (subtitle != null) {
                Text(subtitle, style = YukType.BodySmall, color = YukColors.TextSecondary)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(Dimens.S1))
            Text(value, style = YukType.BodySmall, color = YukColors.TextSecondary)
        }
        if (trailing != null) {
            Spacer(Modifier.width(Dimens.S1))
            trailing()
        }
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = YukColors.TextSecondary,
            )
        }
    }
}

/** Panel tanpa padding untuk menampung beberapa ListRow (pisahkan dengan HairlineDivider). */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    YukCard(modifier = modifier, contentPadding = 0.dp, content = content)
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(Dimens.Hairline)
            .background(YukColors.Border),
    )
}

/** Titik status yang berkedip (dipakai untuk ON-AIR / AKHIRI). */
@Composable
fun BlinkingDot(color: Color, size: Dp = 8.dp, blinking: Boolean = true) {
    val alpha = if (blinking) {
        val transition = rememberInfiniteTransition(label = "blink")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
            label = "blink-alpha",
        )
        a
    } else 1f
    Box(
        Modifier
            .size(size)
            .alpha(alpha)
            .clip(PillShape)
            .background(color),
    )
}

/** Badge status persegi (sudut tajam). */
@Composable
fun StatusBadge(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = YukColors.Live,
) {
    Row(
        modifier = modifier
            .background(if (active) activeColor else Color.Transparent)
            .border(Dimens.Hairline, if (active) activeColor else YukColors.BorderStrong)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BlinkingDot(
            color = if (active) YukColors.TextPrimary else YukColors.TextDisabled,
            size = 6.dp,
            blinking = active,
        )
        Text(
            text.uppercase(),
            style = YukType.LabelSmall,
            color = if (active) YukColors.TextPrimary else YukColors.TextSecondary,
        )
    }
}

/** Keadaan kosong di dalam kartu. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    YukCard(modifier) {
        Text(title, style = YukType.TitleSmall, color = YukColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(body, style = YukType.BodySmall, color = YukColors.TextSecondary)
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(Dimens.S2))
            PillButton(text = actionText, onClick = onAction, variant = PillVariant.Secondary, height = 40.dp)
        }
    }
}

/** Penanda fitur yang dibangun di tahap berikutnya. */
@Composable
fun StagePlaceholder(stage: Int, description: String, modifier: Modifier = Modifier) {
    YukCard(modifier) {
        YukLabel(stringResource(R.string.stage_coming, stage), color = YukColors.TextDisabled)
        Spacer(Modifier.height(6.dp))
        Text(description, style = YukType.BodySmall, color = YukColors.TextSecondary)
    }
}
