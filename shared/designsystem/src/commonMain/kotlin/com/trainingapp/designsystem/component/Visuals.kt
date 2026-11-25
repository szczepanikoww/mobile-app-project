package com.trainingapp.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trainingapp.designsystem.theme.AppTheme
import pl.trainer.ui.rememberProfilePhoto

/* Small visual primitives that give routine screens some imagery without stock photos. All are decorative. */

/** The brand fill for hero surfaces: strong brand into a slightly deeper shade. White text stays above 4.5:1. */
@Composable
fun brandHeroBrush(): Brush {
    val strong = AppTheme.colors.brandStrong
    return Brush.linearGradient(listOf(strong, lerp(strong, AppTheme.colors.text, 0.28f)))
}

/** Two soft circles in the corner of a hero surface. Purely decorative, drawn behind the content. */
fun Modifier.heroDecoration(tint: Color = Color.White): Modifier = clipToBounds().drawBehind {
    drawCircle(tint.copy(alpha = 0.16f), radius = size.height * 0.75f, center = Offset(size.width * 0.95f, size.height * 0.05f))
    drawCircle(tint.copy(alpha = 0.10f), radius = size.height * 0.45f, center = Offset(size.width * 0.80f, size.height * 1.0f))
}

/** An icon on a tinted circle, used as the leading image of rows and empty states. Decorative. */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    container: Color = AppTheme.colors.softAccent,
    content: Color = AppTheme.colors.brandStrong,
) {
    Box(
        modifier.size(size).background(container, CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * A round portrait of a person: their profile photo when [photoOf] has one (loaded through [LocalProfilePhotos]),
 * otherwise the initials on the brand fill. [photoOf] is a user id, or [pl.trainer.ui.MyPhoto] for the signed-in user.
 * Decorative: the name shown next to it is what screen readers announce.
 */
@Composable
fun PersonPortrait(
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    selected: Boolean = false,
    photoOf: String? = null,
) {
    val colors = AppTheme.colors
    val photo = rememberProfilePhoto(photoOf)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(brandHeroBrush(), CircleShape)
            .heroDecoration(colors.brandAccent)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Text(
                initials,
                color = colors.onBrandStrong,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.34f).sp,
                fontFamily = AppTheme.type.itemTitle.fontFamily,
            )
        }
        Box(
            Modifier.matchParentSize().border(
                BorderStroke(if (selected) 3.dp else 2.dp, if (selected) colors.brandAccent else colors.softAccent),
                CircleShape,
            ),
        )
    }
}
