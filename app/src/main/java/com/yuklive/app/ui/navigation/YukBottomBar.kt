package com.yuklive.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yuklive.app.ui.components.HairlineDivider
import com.yuklive.app.ui.theme.Motion
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType

/** Navigasi bawah monokrom: tanpa indikator pill, hanya putih vs abu. */
@Composable
fun YukBottomBar(
    currentRoute: String?,
    onSelect: (TopTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(YukColors.Black),
    ) {
        HairlineDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
        ) {
            TopTab.entries.forEach { tab ->
                val selected = currentRoute == tab.route
                val color by animateColorAsState(
                    targetValue = if (selected) YukColors.TextPrimary else YukColors.TextSecondary,
                    animationSpec = tween(Motion.Fast),
                    label = "tab-color",
                )
                val label = stringResource(tab.label)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(tab.icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(label.uppercase(), style = YukType.LabelSmall, color = color)
                }
            }
        }
    }
}
