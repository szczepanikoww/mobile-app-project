package com.trainingapp.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
    background = Color(0xFFF8F9FA),
    surface = Color(0xFFFFFFFF),
    subtleSurface = Color(0xFFF8FAFC),
    tonalSurface = Color(0xFFF1F4F9),
    text = Color(0xFF0F172A),
    textSecondary = Color(0xFF565E74),
    outline = Color(0xFFE2E8F0),
    fieldOutline = Color(0xFF737B8C),
    brandAccent = Color(0xFFFF6B00),
    brandStrong = Color(0xFFA04100),
    onBrandStrong = Color(0xFFFFFFFF),
    softAccent = Color(0xFFFFF0E6),
    onSoftAccent = Color(0xFF572000),
    success = Color(0xFF15803D),
    successContainer = Color(0xFFDCFCE7),
    onSuccessContainer = Color(0xFF14532D),
    warning = Color(0xFFD97706),
    // Same readable warning pair as the trainer calendar/clients branch.
    warningContainer = Color(0xFFFFF0DA),
    onWarningContainer = Color(0xFF783B00),
    errorAccent = Color(0xFFEF4444),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
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
