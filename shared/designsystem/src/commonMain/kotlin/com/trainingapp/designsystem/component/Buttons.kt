package com.trainingapp.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.resources.Res
import com.trainingapp.designsystem.resources.ds_loading
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import org.jetbrains.compose.resources.stringResource

private val ButtonPadding = PaddingValues(horizontal = Spacing.xl, vertical = Spacing.m)

/**
 * The dominant action of a screen: white text on the strong brand pill, at least 52 dp high (as on the trainer
 * calendar/clients branch). While [loading], it shows progress and ignores taps, so it cannot be submitted twice.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val loadingLabel = stringResource(Res.string.ds_loading)
    Button(
        onClick = { if (!loading) onClick() },
        modifier = modifier
            .defaultMinSize(minHeight = Sizes.buttonHeight)
            .semantics { if (loading) stateDescription = loadingLabel },
        enabled = enabled,
        shape = AppShapes.pill,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppTheme.colors.brandStrong,
            contentColor = AppTheme.colors.onBrandStrong,
        ),
    ) {
        ButtonContent(text, icon, loading, progressColor = AppTheme.colors.onBrandStrong)
    }
}

/**
 * The second action next to the primary one, e.g. "Zapisz szablon": an outlined pill of the same height.
 * While [loading], it shows progress and ignores taps.
 */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = { if (!loading) onClick() },
        modifier = modifier.defaultMinSize(minHeight = Sizes.buttonHeight),
        enabled = enabled,
        shape = AppShapes.pill,
        contentPadding = ButtonPadding,
        border = BorderStroke(1.dp, AppTheme.colors.fieldOutline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.colors.brandStrong),
    ) {
        ButtonContent(text, icon, loading, progressColor = AppTheme.colors.brandStrong)
    }
}

/** A secondary, outlined action. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = Sizes.minTouchTarget),
        enabled = enabled,
        shape = AppShapes.pill,
        contentPadding = ButtonPadding,
        border = BorderStroke(1.dp, AppTheme.colors.fieldOutline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.colors.brandStrong),
    ) {
        ButtonContent(text, icon, loading = false, progressColor = AppTheme.colors.brandStrong)
    }
}

/** A low-emphasis action, e.g. "+ Dodaj serię". Set [destructive] for remove/discard actions. */
@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = Sizes.minTouchTarget),
        enabled = enabled,
        shape = AppShapes.pill,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) AppTheme.colors.onErrorContainer else AppTheme.colors.brandStrong,
        ),
    ) {
        ButtonContent(text, icon, loading = false, progressColor = AppTheme.colors.brandStrong)
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?, loading: Boolean, progressColor: Color) {
    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = progressColor, strokeWidth = 2.dp)
            Spacer(Modifier.width(Spacing.s))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.s))
        }
        Text(text, style = AppTheme.type.button, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
    }
}

/**
 * The fixed bottom area for a screen's primary action. It stays above the keyboard and the system bars;
 * scrolling content above it should end clear of it.
 */
@Composable
fun BottomActionBar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surface)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        HorizontalDivider(color = AppTheme.colors.outline)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter, vertical = Spacing.m),
        ) { content() }
    }
}
