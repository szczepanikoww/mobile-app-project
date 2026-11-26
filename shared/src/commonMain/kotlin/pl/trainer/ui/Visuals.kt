package pl.trainer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The trainer's visual primitives (docs/plans/trainer-redesign.md, "Visual direction"): one teal hero card per screen,
 * white borderless cards on the light background, coloured icon tiles and portraits with real profile photos.
 * The designsystem module has the same primitives for the client views; both draw the hero the same way.
 */

/** The brand fill of hero surfaces: strong brand into a slightly deeper shade. White text stays above 4.5:1. */
fun heroBrush(): Brush = Brush.linearGradient(listOf(AppColors.brandStrong, lerp(AppColors.brandStrong, AppColors.text, 0.28f)))

/** Two soft circles in the corner of a hero surface. Purely decorative, drawn behind the content. */
fun Modifier.heroCircles(tint: Color = Color.White): Modifier = clipToBounds().drawBehind {
    drawCircle(tint.copy(alpha = 0.16f), radius = size.height * 0.75f, center = Offset(size.width * 0.95f, size.height * 0.05f))
    drawCircle(tint.copy(alpha = 0.10f), radius = size.height * 0.45f, center = Offset(size.width * 0.80f, size.height * 1.0f))
}

/** The one dominant card of a screen: white content on the brand fill. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Layout.feature)
            .background(heroBrush())
            .heroCircles()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(Layout.gutter),
        content = content,
    )
}

/** A white, borderless card on the screen background: widgets, list rows and grouped content. */
@Composable
fun WhiteCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = Layout.padding,
    color: Color = AppColors.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Layout.tile)
            .background(color)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Colour pairs of the icon tiles; each is at least 4.5:1 for the icon on its container. */
enum class TileTone(val container: Color, val content: Color) {
    Brand(AppColors.softAccent, AppColors.brandStrong),
    Attention(AppColors.warningSurface, AppColors.warningText),
    Money(AppColors.successSurface, AppColors.success),
    Neutral(AppColors.completedSurface, AppColors.completedText),
    Change(AppColors.changeSurface, AppColors.changeText),
}

/** An icon on a rounded, tinted square: the leading image of widgets. Decorative. */
@Composable
fun IconTile(icon: ImageVector, tone: TileTone = TileTone.Brand, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(tone.container, RoundedCornerShape(12.dp)).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tone.content, modifier = Modifier.size(size * 0.55f))
    }
}

/** A small count on the attention colour, e.g. the number of open requests. Announced with the widget's text. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.widthIn(min = 24.dp).background(AppColors.warningText, Layout.pill).padding(horizontal = 8.dp, vertical = 2.dp)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * A round portrait: the profile photo of [photoOf] (a user id or [MyPhoto]) when there is one, otherwise the initials
 * on the soft accent. [ring] draws a white ring, for portraits on the hero fill. Decorative: the name is announced.
 */
@Composable
fun Portrait(initials: String, photoOf: String?, size: Dp = 48.dp, ring: Boolean = false, modifier: Modifier = Modifier) {
    val photo = rememberProfilePhoto(photoOf)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(AppColors.softAccent, CircleShape)
            .then(if (ring) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text(initials, color = AppColors.onSoftAccent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp)
        }
    }
}
