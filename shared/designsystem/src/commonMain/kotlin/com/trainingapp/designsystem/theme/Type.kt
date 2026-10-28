package com.trainingapp.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.trainingapp.designsystem.resources.Res
import com.trainingapp.designsystem.resources.ibm_plex_sans_bold
import com.trainingapp.designsystem.resources.ibm_plex_sans_regular
import com.trainingapp.designsystem.resources.ibm_plex_sans_semibold
import org.jetbrains.compose.resources.Font

/** The named text styles from CLAUDE.md section 4 (size/line height in sp). */
@Immutable
data class AppTypography(
    val screenTitle: TextStyle,
    val sectionTitle: TextStyle,
    val itemTitle: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val compactBody: TextStyle,
    val label: TextStyle,
    val button: TextStyle,
    /** A set value or a prominent number. */
    val value: TextStyle,
)

@Composable
internal fun plexSans(): FontFamily = FontFamily(
    Font(Res.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(Res.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    Font(Res.font.ibm_plex_sans_bold, FontWeight.Bold),
)

internal fun appTypography(family: FontFamily) = AppTypography(
    screenTitle = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    sectionTitle = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    itemTitle = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyStrong = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    compactBody = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    label = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    button = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    value = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
)

internal fun AppTypography.toMaterial() = Typography(
    headlineSmall = screenTitle,
    titleLarge = sectionTitle,
    titleMedium = itemTitle,
    titleSmall = bodyStrong,
    bodyLarge = body,
    bodyMedium = compactBody,
    bodySmall = label.copy(fontWeight = FontWeight.Normal),
    labelLarge = button,
    labelMedium = label,
    labelSmall = label,
)

val LocalAppTypography = staticCompositionLocalOf { appTypography(FontFamily.SansSerif) }
