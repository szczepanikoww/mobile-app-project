package com.trainingapp.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember

/**
 * The one theme for trainer, client and guest views. Only the light reference exists so far; a dark theme must be
 * added here, never per feature.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val family = androidx.compose.ui.text.font.FontFamily.SansSerif
    val typography = remember(family) { appTypography(family) }
    val colors = LightAppColors
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalAppTypography provides typography,
    ) {
        pl.trainer.ui.TrainerTheme(content)
    }
}

/** Shortcuts: `AppTheme.colors.textSecondary`, `AppTheme.type.itemTitle`. */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current

    val type: AppTypography
        @Composable @ReadOnlyComposable get() = LocalAppTypography.current
}
