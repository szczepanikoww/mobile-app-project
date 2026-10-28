package com.trainingapp.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** The shared spacing scale: 4, 8, 12, 16, 20, 24, 32 dp. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    /** Default screen gutter. */
    val gutter = xl
    val cardPadding = l
    val sectionGap = xxl
}

object AppShapes {
    val card = RoundedCornerShape(16.dp)
    val input = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val featureCard = RoundedCornerShape(24.dp)
    val pill = CircleShape

    /** Exercise thumbnails and small media. */
    val thumbnail = RoundedCornerShape(16.dp)
}

object Sizes {
    val icon = 24.dp
    val minTouchTarget = 48.dp
    val thumbnail = 56.dp
}

internal val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = AppShapes.card,
    large = AppShapes.featureCard,
    extraLarge = RoundedCornerShape(28.dp),
)
