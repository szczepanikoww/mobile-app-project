package pl.trainer.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Layout {
    val tiny = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val padding = 16.dp
    val gutter = 20.dp
    val section = 24.dp
    val large = 32.dp
    val target = 48.dp
    val buttonHeight = 52.dp
    val headerHeight = 64.dp
    val navigationHeight = 68.dp
    val navigationOverhang = 24.dp
    val navigationCreateSize = 56.dp
    val navigationCreateGap = 64.dp
    val floatingElevation = 2.dp
    val activeFloatingElevation = 3.dp
    val contentWidth = 640.dp
    val card = RoundedCornerShape(16.dp)
    val panel = RoundedCornerShape(20.dp)
    val tile = RoundedCornerShape(22.dp)
    val feature = RoundedCornerShape(24.dp)
    val frame = RoundedCornerShape(28.dp)
    val navigation = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val pill = RoundedCornerShape(50)
}

object AppColors {
    val brandStrong = Color(0xFFA04100)
    val background = Color(0xFFF8F9FA)
    val surface = Color.White
    val text = Color(0xFF0F172A)
    val textSecondary = Color(0xFF565E74)
    val outline = Color(0xFFE2E8F0)
    val fieldOutline = Color(0xFF737B8C)
    val errorText = Color(0xFF93000A)
    val errorSurface = Color(0xFFFFDAD6)
    val errorAccent = Color(0xFFEF4444)

    val accent = Color(0xFFFF6B00)
    val softAccent = Color(0xFFFFF0E6)
    val onSoftAccent = Color(0xFF572000)
    val success = Color(0xFF15803D)
    val successSurface = Color(0xFFDCFCE7)
    val warningText = Color(0xFF783B00)
    val warningSurface = Color(0xFFFFF0DA)
    val subtleSurface = Color(0xFFF8FAFC)
    val changeSurface = Color(0xFFEAF0FF)
    val changeText = Color(0xFF244690)
    val waitingSurface = Color(0xFFF1EBFA)
    val waitingText = Color(0xFF603B8E)
    val completedSurface = Color(0xFFE9EEF2)
    val completedText = Color(0xFF3D5368)
}

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
)

@Composable
fun TrainerTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = lightColorScheme(
        primary = AppColors.brandStrong, onPrimary = AppColors.surface,
        primaryContainer = AppColors.softAccent, onPrimaryContainer = AppColors.onSoftAccent,
        background = AppColors.background, onBackground = AppColors.text,
        surface = AppColors.surface, onSurface = AppColors.text,
        surfaceVariant = AppColors.subtleSurface, onSurfaceVariant = AppColors.textSecondary,
        outline = AppColors.fieldOutline, outlineVariant = AppColors.outline,
        error = AppColors.errorText, errorContainer = AppColors.errorSurface, onErrorContainer = AppColors.errorText,
        secondaryContainer = AppColors.softAccent, onSecondaryContainer = AppColors.onSoftAccent,
    ),
    typography = Typography(
        headlineMedium = style(26, 32, FontWeight.Bold),
        titleLarge = style(20, 26, FontWeight.Bold),
        titleMedium = style(17, 22, FontWeight.Bold),
        bodyLarge = style(16, 24), bodyMedium = style(14, 20), bodySmall = style(12, 16),
        labelSmall = style(12, 16, FontWeight.SemiBold),
        labelLarge = style(14, 20, FontWeight.SemiBold), labelMedium = style(12, 16, FontWeight.SemiBold),
    ),
    shapes = Shapes(small = RoundedCornerShape(12.dp), medium = Layout.card, large = Layout.feature, extraLarge = Layout.frame),
    content = content,
)
