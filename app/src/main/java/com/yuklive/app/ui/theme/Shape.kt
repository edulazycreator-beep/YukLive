package com.yuklive.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Sudut tajam untuk kartu, panel, input, dropdown, bottom sheet, preview. */
val SharpShape = RoundedCornerShape(0.dp)

/** Pill (radius penuh) khusus tombol dan toggle. */
val PillShape = RoundedCornerShape(percent = 50)

val YukShapes = Shapes(
    extraSmall = SharpShape,
    small = SharpShape,
    medium = SharpShape,
    large = SharpShape,
    extraLarge = SharpShape,
)
