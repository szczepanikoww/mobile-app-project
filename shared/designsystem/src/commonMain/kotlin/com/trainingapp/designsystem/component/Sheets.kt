package com.trainingapp.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.resources.Res
import com.trainingapp.designsystem.resources.ds_done
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** The shared bottom sheet for short contextual decisions. Longer editing belongs on a full screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AppShapes.sheet,
        containerColor = AppTheme.colors.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            if (title != null) {
                Text(title, style = AppTheme.type.sectionTitle, modifier = Modifier.semantics { heading() })
            }
            content()
        }
    }
}

data class SheetOption<T>(val value: T, val label: String)

/** Pick one option. Selecting closes the sheet. */
@Composable
fun <T> SingleChoiceSheet(
    title: String,
    options: List<SheetOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(onDismiss = onDismiss, title = title) {
        Column(Modifier.fillMaxWidth()) {
            options.forEach { option ->
                val isSelected = option.value == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.minTouchTarget)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option.value) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = AppTheme.colors.brandStrong))
                    Text(option.label, style = AppTheme.type.body, modifier = Modifier.padding(start = Spacing.s))
                }
            }
        }
    }
}

/** Pick several options, confirmed with "Gotowe". [maxSelected] caps the selection. */
@Composable
fun <T> MultiChoiceSheet(
    title: String,
    options: List<SheetOption<T>>,
    selected: Set<T>,
    onApply: (Set<T>) -> Unit,
    onDismiss: () -> Unit,
    maxSelected: Int = Int.MAX_VALUE,
    supportingText: String? = null,
) {
    var current by remember(selected) { mutableStateOf(selected) }
    AppBottomSheet(onDismiss = onDismiss, title = title) {
        if (supportingText != null) {
            Text(supportingText, style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
        }
        Column(Modifier.fillMaxWidth()) {
            options.forEach { option ->
                val checked = option.value in current
                val enabled = checked || current.size < maxSelected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.minTouchTarget)
                        .toggleable(
                            value = checked,
                            enabled = enabled,
                            role = Role.Checkbox,
                            onValueChange = { current = if (it) current + option.value else current - option.value },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        enabled = enabled,
                        colors = CheckboxDefaults.colors(checkedColor = AppTheme.colors.brandStrong),
                    )
                    Text(
                        option.label,
                        style = AppTheme.type.body,
                        color = if (enabled) AppTheme.colors.text else AppTheme.colors.textSecondary,
                        modifier = Modifier.padding(start = Spacing.s),
                    )
                }
            }
        }
        PrimaryButton(
            text = stringResource(Res.string.ds_done),
            onClick = { onApply(current) },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp),
        )
    }
}
