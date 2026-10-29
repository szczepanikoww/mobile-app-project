package com.trainingapp.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import pl.trainer.ui.Layout

/** The shared spacing scale: 4, 8, 12, 16, 20, 24, 32 dp. */
object Spacing {
    val xs = Layout.tiny
    val s = Layout.small
    val m = Layout.medium
    val l = Layout.padding
    val xl = Layout.gutter
    val xxl = Layout.section
    val xxxl = Layout.large

    /** Default screen gutter. */
    val gutter = xl
    val cardPadding = l
    val sectionGap = xxl
}

/** The same radii as the trainer calendar/clients branch: card 16, panel 20, tile 22, feature 24, frame 28. */
object AppShapes {
    val card = Layout.card
    val panel = Layout.panel
    /** List rows and action rows. */
    val tile = Layout.tile
    val frame = Layout.frame
    val navigation = Layout.navigation
    val input = Layout.card
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val featureCard = Layout.feature
    val pill = CircleShape

    /** Exercise thumbnails and small media. */
    val thumbnail = Layout.card
}

object Sizes {
    val icon = 24.dp
    val minTouchTarget = Layout.target
    val thumbnail = 56.dp
    val buttonHeight = Layout.buttonHeight
    val headerHeight = Layout.headerHeight
    val navigationHeight = Layout.navigationHeight
    val navigationOverhang = Layout.navigationOverhang
    val navigationCreateSize = Layout.navigationCreateSize
    val navigationCreateGap = Layout.navigationCreateGap
    /** Floating controls only; cards and bars are flat. */
    val floatingElevation = Layout.floatingElevation
    val activeFloatingElevation = Layout.activeFloatingElevation
    val contentWidth = Layout.contentWidth
}

internal val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = AppShapes.card,
    large = AppShapes.featureCard,
    extraLarge = RoundedCornerShape(28.dp),
)
