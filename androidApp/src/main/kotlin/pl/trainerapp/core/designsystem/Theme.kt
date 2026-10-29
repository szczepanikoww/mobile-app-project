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
import pl.trainer.ui.Layout
import pl.trainer.ui.AppColors as SharedColors
import pl.trainer.ui.TrainerTheme

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
    val sectionGap = Layout.section
}

object AppShapes {
    val card = Layout.card
    val panel = Layout.panel
    val tile = Layout.tile
    val media = Layout.feature
    val frame = Layout.frame
    val pill = RoundedCornerShape(percent = 50)
}

/** Semantic colors that Material's scheme has no slot for. */
@Immutable
data class AppColors(
    val brand: Color,
    val brandAccent: Color,
    val softBrand: Color,
    val brandBorder: Color,
    val textMuted: Color,
    val textStrong: Color,
    val border: Color,
    val chipSurface: Color,
    val success: Color,
    val successContainer: Color,
    val successBorder: Color,
    val completed: Color,
    val completedContainer: Color,
    val warningContainer: Color,
    val warning: Color,
    val warningBorder: Color,
    val danger: Color,
    val dangerText: Color,
)

internal val SurfaceOutline = SharedColors.outline

private val LightAppColors = AppColors(
    brand = SharedColors.brandStrong,
    brandAccent = SharedColors.accent,
    softBrand = SharedColors.softAccent,
    brandBorder = SharedColors.accent.copy(alpha = 0.2f),
    textMuted = SharedColors.textSecondary,
    textStrong = SharedColors.text,
    border = SurfaceOutline,
    chipSurface = SharedColors.subtleSurface,
    success = SharedColors.success,
    successContainer = SharedColors.successSurface,
    successBorder = SharedColors.success.copy(alpha = 0.25f),
    completed = SharedColors.completedText,
    completedContainer = SharedColors.completedSurface,
    warningContainer = SharedColors.warningSurface,
    warning = SharedColors.warningText,
    warningBorder = SharedColors.warningText.copy(alpha = 0.25f),
    danger = SharedColors.errorText,
    dangerText = SharedColors.errorText,
)

private val LocalAppColors = staticCompositionLocalOf { LightAppColors }

object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppColors provides LightAppColors) {
        TrainerTheme(content)
    }
}
