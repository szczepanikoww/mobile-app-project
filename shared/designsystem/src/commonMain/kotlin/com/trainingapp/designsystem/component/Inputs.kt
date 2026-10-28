package com.trainingapp.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.resources.Res
import com.trainingapp.designsystem.resources.ds_clear_search
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import org.jetbrains.compose.resources.stringResource

@Composable
private fun appFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = AppTheme.colors.surface,
    unfocusedContainerColor = AppTheme.colors.surface,
    errorContainerColor = AppTheme.colors.surface,
    focusedBorderColor = AppTheme.colors.brandStrong,
    unfocusedBorderColor = AppTheme.colors.outline,
    focusedLabelColor = AppTheme.colors.brandStrong,
    unfocusedLabelColor = AppTheme.colors.textSecondary,
    cursorColor = AppTheme.colors.brandStrong,
)

/**
 * Text input with a persistent label and an inline message below it. [error] replaces [supportingText]
 * and is announced as the field's error.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    supportingText: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (error != null) this.error(error) },
        label = { Text(label) },
        textStyle = AppTheme.type.body,
        isError = error != null,
        supportingText = (error ?: supportingText)?.let { message -> { Text(message, style = AppTheme.type.compactBody) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = AppShapes.input,
        colors = appFieldColors(),
    )
}

/**
 * A compact value box for set tables (planner and logger). It always has a content description, because the
 * column header is not part of the cell. Numeric keyboards are used by default.
 */
@Composable
fun SetValueField(
    value: String,
    onValueChange: (String) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Decimal,
    imeAction: ImeAction = ImeAction.Next,
) {
    val colors = AppTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .heightIn(min = Sizes.minTouchTarget)
            .semantics { this.contentDescription = contentDescription },
        singleLine = true,
        textStyle = AppTheme.type.value.copy(color = colors.text, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(colors.brandStrong),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .border(
                        BorderStroke(if (isError) 2.dp else 1.dp, if (isError) colors.onErrorContainer else colors.outline),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = Spacing.s, vertical = Spacing.m),
                contentAlignment = Alignment.Center,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = AppTheme.type.value, color = colors.textSecondary, textAlign = TextAlign.Center)
                }
                inner()
            }
        },
    )
}

/** Full-width white pill search field with a soft shadow, a leading icon and a clear button. */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, AppShapes.pill, ambientColor = Color(0x14000000), spotColor = Color(0x1F000000)),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, stringResource(Res.string.ds_clear_search))
                }
            }
        } else {
            null
        },
        singleLine = true,
        textStyle = AppTheme.type.body,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = AppShapes.pill,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = AppTheme.colors.surface,
            unfocusedContainerColor = AppTheme.colors.surface,
            focusedBorderColor = AppTheme.colors.brandAccent,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = AppTheme.colors.brandStrong,
            unfocusedLeadingIconColor = AppTheme.colors.textSecondary,
            focusedLeadingIconColor = AppTheme.colors.textSecondary,
        ),
    )
}

/**
 * A field that opens a sheet with options, styled as the "WYBRANE ĆWICZENIE" card of the Stitch Postępy screen:
 * an icon circle, the label above the value at all times, and a chevron.
 */
@Composable
fun SelectorField(
    label: String,
    value: String?,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(32.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, shape, ambientColor = Color(0x14000000), spotColor = Color(0x1F000000))
                .clip(shape)
                .background(if (enabled) colors.surface else colors.subtleSurface)
                .border(BorderStroke(2.dp, if (error != null) colors.onErrorContainer else Color.Transparent), shape)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
                .semantics { if (error != null) this.error(error) }
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            if (icon != null) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(colors.softAccent), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = colors.brandAccent)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), style = AppTheme.type.overline, color = if (error != null) colors.onErrorContainer else colors.textSecondary)
                Text(
                    text = value ?: placeholder,
                    style = AppTheme.type.itemTitle,
                    color = if (value == null || !enabled) colors.textSecondary else colors.text,
                )
            }
            if (enabled) Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = colors.textSecondary)
        }
        (error ?: supportingText)?.let {
            Text(
                it,
                style = AppTheme.type.compactBody,
                color = if (error != null) colors.onErrorContainer else colors.textSecondary,
                modifier = Modifier.padding(horizontal = Spacing.l),
            )
        }
    }
}

/**
 * A single-line text field in the white pill of the Stitch "NAZWA JEDNOSTKI" card: an icon circle, an uppercase
 * label that stays visible, and the value in bold.
 */
@Composable
fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    icon: ImageVector? = null,
    error: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(40.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(8.dp, shape, ambientColor = Color(0x14000000), spotColor = Color(0x1F000000))
                .clip(shape)
                .background(colors.surface)
                .border(BorderStroke(2.dp, if (error != null) colors.onErrorContainer else Color.Transparent), shape)
                .padding(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            if (icon != null) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(colors.tonalSurface), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = colors.brandAccent)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), style = AppTheme.type.overline, color = if (error != null) colors.onErrorContainer else colors.textSecondary)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = AppTheme.type.sectionTitle.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.brandStrong),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = label
                            if (error != null) this.error(error)
                        },
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty() && placeholder.isNotEmpty()) {
                                Text(placeholder, style = AppTheme.type.sectionTitle, color = colors.textSecondary, maxLines = 1)
                            }
                            inner()
                        }
                    },
                )
            }
            trailing?.invoke()
        }
        if (error != null) {
            Text(error, style = AppTheme.type.compactBody, color = colors.onErrorContainer, modifier = Modifier.padding(horizontal = Spacing.l))
        }
    }
}
