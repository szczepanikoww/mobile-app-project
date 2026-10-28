package pl.trainerapp.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Shared spacing scale (dp): 4, 8, 12, 16, 20, 24, 32. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    val screenGutter = xl
    val sectionGap = xxxl
}

object AppShapes {
    val card = RoundedCornerShape(16.dp)
    val panel = RoundedCornerShape(20.dp)
    val tile = RoundedCornerShape(22.dp)
    val media = RoundedCornerShape(24.dp)
    val frame = RoundedCornerShape(28.dp)
    val pill = RoundedCornerShape(percent = 50)
}

/** Semantic colors that Material's scheme has no slot for. */
@Immutable
data class AppColors(
    val brand: Color,
    val softBrand: Color,
    val brandBorder: Color,
    val textMuted: Color,
    val textStrong: Color,
    val border: Color,
    val chipSurface: Color,
    val success: Color,
    val successContainer: Color,
    val successBorder: Color,
    val warning: Color,
    val warningBorder: Color,
    val danger: Color,
    val dangerText: Color,
)

private val LightAppColors = AppColors(
    brand = Color(0xFFFF6B00),
    softBrand = Color(0xFFFFF0E6),
    brandBorder = Color(0x33FF6B00),
    textMuted = Color(0xFF64748B),
    textStrong = Color(0xFF1E293B),
    border = Color(0xFFE2E8F0),
    chipSurface = Color(0xFFF1F5F9),
    success = Color(0xFF15803D),
    successContainer = Color(0xFFDCFCE7),
    successBorder = Color(0xFFBBF7D0),
    warning = Color(0xFFD97706),
    warningBorder = Color(0xFFFDE68A),
    danger = Color(0xFFEF4444),
    dangerText = Color(0xFFDC2626),
)

private val LocalAppColors = staticCompositionLocalOf { LightAppColors }

object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
}

private val LightScheme = lightColorScheme(
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF64748B),
    surfaceVariant = Color(0xFFF1F5F9),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFFE2E8F0),
    primary = Color(0xFFFF6B00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFF0E6),
    onPrimaryContainer = Color(0xFFFF6B00),
    secondaryContainer = Color(0xFFFFF0E6),
    onSecondaryContainer = Color(0xFFD97706),
    error = Color(0xFFEF4444),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
)

// IBM Plex Sans is not bundled yet: one shared system-sans fallback for the whole app.
private val AppFont = FontFamily.SansSerif

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = AppFont,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
)

/** Design-system scale: headline-md 20/26, headline-sm 17/22, body 16/24 · 14/20 · 12/16, label 14/18 · 12/16 · 10/12. */
private val AppTypography = Typography(
    titleLarge = style(20, 26, FontWeight.Bold, -0.015f),
    titleMedium = style(17, 22, FontWeight.Bold, -0.01f),
    bodyLarge = style(16, 24, FontWeight.Normal, -0.005f),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 16, FontWeight.Normal, 0.01f),
    labelLarge = style(14, 18, FontWeight.SemiBold, 0.02f),
    labelMedium = style(12, 16, FontWeight.SemiBold, 0.03f),
    labelSmall = style(10, 12, FontWeight.Bold, 0.05f),
)

private val AppMaterialShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = AppShapes.card,
    large = RoundedCornerShape(24.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppColors provides LightAppColors) {
        MaterialTheme(
            colorScheme = LightScheme,
            typography = AppTypography,
            shapes = AppMaterialShapes,
            content = content,
        )
    }
}
