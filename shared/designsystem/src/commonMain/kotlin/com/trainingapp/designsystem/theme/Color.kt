package com.trainingapp.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import pl.trainer.ui.AppColors as SharedColors

/**
 * Semantic colour roles from CLAUDE.md section 4. Feature screens use these roles (or the Material scheme built
 * from them), never hex values. Every foreground/container pair here was chosen for at least 4.5:1 contrast.
 */
@Immutable
data class AppColors(
    val background: Color,
    val surface: Color,
    val subtleSurface: Color,
    /** Fill of tonal buttons and the set-row background in the plan creator. */
    val tonalSurface: Color,
    val text: Color,
    val textSecondary: Color,
    /** Card and row borders. */
    val outline: Color,
    /** Text-field borders, stronger than [outline] so inputs read as inputs. */
    val fieldOutline: Color,
    /** Highlights and selected accents. Not a text colour on white, and never behind small white text. */
    val brandAccent: Color,
    /** Readable brand text and filled primary controls (white content). */
    val brandStrong: Color,
    val onBrandStrong: Color,
    val softAccent: Color,
    val onSoftAccent: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val errorAccent: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
)

val LightAppColors = AppColors(
    background = SharedColors.background,
    surface = SharedColors.surface,
    subtleSurface = SharedColors.subtleSurface,
    tonalSurface = SharedColors.subtleSurface,
    text = SharedColors.text,
    textSecondary = SharedColors.textSecondary,
    outline = SharedColors.outline,
    fieldOutline = SharedColors.fieldOutline,
    brandAccent = SharedColors.accent,
    brandStrong = SharedColors.brandStrong,
    onBrandStrong = SharedColors.surface,
    softAccent = SharedColors.softAccent,
    onSoftAccent = SharedColors.onSoftAccent,
    success = SharedColors.success,
    successContainer = SharedColors.successSurface,
    onSuccessContainer = SharedColors.success,
    warning = SharedColors.warningText,
    // Same readable warning pair as the trainer calendar/clients branch.
    warningContainer = SharedColors.warningSurface,
    onWarningContainer = SharedColors.warningText,
    errorAccent = SharedColors.errorAccent,
    errorContainer = SharedColors.errorSurface,
    onErrorContainer = SharedColors.errorText,
)

/** The client and guest palette (see [pl.trainer.ui.ClubColors]); semantic success, warning and error colours are shared. */
val ClubAppColors = LightAppColors.copy(
    background = pl.trainer.ui.ClubColors.background,
    subtleSurface = pl.trainer.ui.ClubColors.subtleSurface,
    tonalSurface = pl.trainer.ui.ClubColors.subtleSurface,
    text = pl.trainer.ui.ClubColors.text,
    textSecondary = pl.trainer.ui.ClubColors.textSecondary,
    outline = pl.trainer.ui.ClubColors.outline,
    fieldOutline = pl.trainer.ui.ClubColors.fieldOutline,
    brandAccent = pl.trainer.ui.ClubColors.accent,
    brandStrong = pl.trainer.ui.ClubColors.brandStrong,
    softAccent = pl.trainer.ui.ClubColors.softAccent,
    onSoftAccent = pl.trainer.ui.ClubColors.onSoftAccent,
)

internal fun AppColors.toMaterialScheme(): ColorScheme = lightColorScheme(
    primary = brandStrong,
    onPrimary = onBrandStrong,
    primaryContainer = softAccent,
    onPrimaryContainer = onSoftAccent,
    secondary = brandStrong,
    onSecondary = onBrandStrong,
    secondaryContainer = softAccent,
    onSecondaryContainer = onSoftAccent,
    tertiary = success,
    background = background,
    onBackground = text,
    surface = surface,
    onSurface = text,
    surfaceVariant = subtleSurface,
    onSurfaceVariant = textSecondary,
    surfaceContainerLowest = surface,
    surfaceContainerLow = surface,
    surfaceContainer = surface,
    surfaceContainerHigh = surface,
    surfaceContainerHighest = subtleSurface,
    surfaceTint = Color.Transparent,
    outline = fieldOutline,
    outlineVariant = outline,
    error = onErrorContainer,
    onError = Color.White,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
