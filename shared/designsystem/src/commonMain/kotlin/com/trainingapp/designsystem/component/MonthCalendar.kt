package com.trainingapp.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.resources.Res
import com.trainingapp.designsystem.resources.ds_next_month
import com.trainingapp.designsystem.resources.ds_previous_month
import com.trainingapp.designsystem.resources.ds_unavailable
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.stringResource
import pl.trainer.ui.DateFormats

/**
 * A compact month grid (weeks start on Monday) for picking one day. Days outside [firstDate]..[lastDate] or rejected
 * by [isSelectable] are shown but disabled; the month arrows stay within that range. [today] gets a ring.
 */
@Composable
fun MonthCalendar(
    selected: LocalDate?,
    today: LocalDate,
    firstDate: LocalDate,
    lastDate: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    isSelectable: (LocalDate) -> Boolean = { true },
    language: String = DateFormats.defaultLanguage(),
) {
    val colors = AppTheme.colors
    var month by remember(firstDate) { mutableStateOf((selected ?: firstDate).firstOfMonth()) }
    val canGoBack = month > firstDate.firstOfMonth()
    val canGoForward = month < lastDate.firstOfMonth()
    val unavailable = stringResource(Res.string.ds_unavailable)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                DateFormats.date(month, language, "LLLL yyyy").replaceFirstChar { it.titlecase() },
                style = AppTheme.type.itemTitle,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { month = month.minus(1, DateTimeUnit.MONTH) }, enabled = canGoBack) {
                Icon(Icons.Outlined.ChevronLeft, stringResource(Res.string.ds_previous_month))
            }
            IconButton(onClick = { month = month.plus(1, DateTimeUnit.MONTH) }, enabled = canGoForward) {
                Icon(Icons.Outlined.ChevronRight, stringResource(Res.string.ds_next_month))
            }
        }
        // Weekday initials, Monday first.
        val monday = month.minus(month.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            repeat(7) { offset ->
                Text(
                    DateFormats.date(monday.plus(offset, DateTimeUnit.DAY), language, "EEEEE").uppercase(),
                    style = AppTheme.type.label,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        val nextMonth = month.plus(1, DateTimeUnit.MONTH)
        var weekStart = monday
        while (weekStart < nextMonth) {
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { offset ->
                    val date = weekStart.plus(offset, DateTimeUnit.DAY)
                    Box(Modifier.weight(1f).height(Sizes.minTouchTarget), contentAlignment = Alignment.Center) {
                        if (date >= month && date < nextMonth) {
                            val enabled = date in firstDate..lastDate && isSelectable(date)
                            val chosen = date == selected
                            val description = DateFormats.date(date, language, "EEEE, d MMMM") + if (enabled) "" else ", $unavailable"
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(if (chosen) colors.brandStrong else colors.surface.copy(alpha = 0f))
                                    .then(if (date == today && !chosen) Modifier.border(BorderStroke(1.5.dp, colors.brandAccent), CircleShape) else Modifier)
                                    .clickable(enabled = enabled, role = Role.Button) { onSelect(date) }
                                    .semantics {
                                        this.selected = chosen
                                        contentDescription = description
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    date.day.toString(),
                                    style = AppTheme.type.compactBody.copy(fontWeight = if (chosen || date == today) FontWeight.Bold else FontWeight.Normal),
                                    color = when {
                                        chosen -> colors.onBrandStrong
                                        enabled -> colors.text
                                        else -> colors.textSecondary.copy(alpha = 0.45f)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            weekStart = weekStart.plus(7, DateTimeUnit.DAY)
        }
    }
}

private fun LocalDate.firstOfMonth(): LocalDate = LocalDate(year, month, 1)
