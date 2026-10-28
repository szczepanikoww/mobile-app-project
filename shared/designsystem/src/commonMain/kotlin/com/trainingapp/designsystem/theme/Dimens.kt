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

/** The same radii as the trainer calendar/clients branch: card 16, panel 20, tile 22, feature 24, frame 28. */
object AppShapes {
    val card = RoundedCornerShape(16.dp)
    val panel = RoundedCornerShape(20.dp)
    /** List rows and action rows. */
    val tile = RoundedCornerShape(22.dp)
    val frame = RoundedCornerShape(28.dp)
    val navigation = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
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
    val buttonHeight = 52.dp
    val headerHeight = 64.dp
    val navigationHeight = 68.dp
    val navigationOverhang = 24.dp
    val navigationCreateSize = 56.dp
    val navigationCreateGap = 64.dp
    /** Floating controls only; cards and bars are flat. */
    val floatingElevation = 2.dp
    val activeFloatingElevation = 3.dp
    val contentWidth = 640.dp
}

internal val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = AppShapes.card,
    large = AppShapes.featureCard,
    extraLarge = RoundedCornerShape(28.dp),
)
