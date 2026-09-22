package com.mediasaver.core.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Custom shape constants matching Clean Utility specifications
val CardShape = RoundedCornerShape(24.dp)
val BottomSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
val SheetCornerShape = RoundedCornerShape(28.dp)
val ButtonShape = CircleShape // Pill shape
val ChipShape = RoundedCornerShape(10.dp)
val ThumbShape = RoundedCornerShape(16.dp)
val MediaCardShape = RoundedCornerShape(20.dp)
val SnackbarShape = RoundedCornerShape(16.dp)

val MediaSaverShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = ChipShape, // 10.dp
    medium = ThumbShape, // 16.dp
    large = CardShape, // 24.dp
    extraLarge = SheetCornerShape // 28.dp
)
