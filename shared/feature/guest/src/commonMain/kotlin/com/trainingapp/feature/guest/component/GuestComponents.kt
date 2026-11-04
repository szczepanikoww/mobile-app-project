package com.trainingapp.feature.guest.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.component.AppBottomSheet
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.CardRow
import com.trainingapp.designsystem.component.InitialsAvatar
import com.trainingapp.designsystem.component.MetaLine
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.RowDivider
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.designsystem.component.TonalButton
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.format.LocaleFormats
import com.trainingapp.domain.media.MediaState
import com.trainingapp.feature.guest.model.GymSummary
import com.trainingapp.feature.guest.model.Offer
import com.trainingapp.feature.guest.model.PublicMedia
import com.trainingapp.feature.guest.model.Seller
import com.trainingapp.feature.guest.model.TrainerSummary
import com.trainingapp.feature.guest.resources.*
import org.jetbrains.compose.resources.stringResource

/** A trainer in a list: the client row of the trainer calendar/clients branch with public catalogue data. */
@Composable
fun TrainerRow(trainer: TrainerSummary, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    CardRow(
        title = trainer.name,
        modifier = modifier,
        supporting = if (compact) trainer.tags.take(2).joinToString(" · ") else trainer.headline,
        onClick = onClick,
        leading = { InitialsAvatar(trainer.initials) },
        meta = {
            trainer.rating?.let { RatingLine(ratingText(it), ratingDescription(it)) }
            if (!compact) trainer.mainGym?.let { MetaLine(Icons.Outlined.Place, it.name) }
            if (!compact && trainer.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    trainer.tags.take(3).forEach { StatusChip(it) }
                }
            }
            Text(startingPriceText(trainer.startingPrice), style = AppTheme.type.bodyStrong, color = AppTheme.colors.brandStrong)
        },
    )
}

/** The compact two-column card of the grid view. Only used below large font scales. */
@Composable
fun TrainerGridCard(trainer: TrainerSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(AppShapes.tile)
            .background(AppTheme.colors.surface)
            .border(BorderStroke(1.dp, AppTheme.colors.outline), AppShapes.tile)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        InitialsAvatar(trainer.initials)
        Text(trainer.name, style = AppTheme.type.itemTitle, color = AppTheme.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        trainer.tags.firstOrNull()?.let { StatusChip(it) }
        trainer.rating?.let { RatingLine(LocaleFormats.formatDecimal(it.average, uiLanguage()), ratingDescription(it)) }
        Text(startingPriceText(trainer.startingPrice), style = AppTheme.type.button, color = AppTheme.colors.brandStrong)
    }
}

/** A star and the rating text; read as one phrase by screen readers. */
@Composable
internal fun RatingLine(text: String, description: String) {
    Row(
        Modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(18.dp), tint = AppTheme.colors.brandStrong)
        Text(text, style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
    }
}

/** The neutral icon tile used for gyms instead of photos (D8). Decorative. */
@Composable
fun GymIconTile(modifier: Modifier = Modifier) {
    Box(
        modifier.size(Sizes.minTouchTarget).clip(AppShapes.card).background(AppTheme.colors.subtleSurface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.FitnessCenter, contentDescription = null, tint = AppTheme.colors.textSecondary, modifier = Modifier.size(Sizes.icon))
    }
}

@Composable
fun GymRow(gym: GymSummary, onClick: () -> Unit, modifier: Modifier = Modifier, badge: String? = null) {
    val badgeContent: (@Composable RowScope.() -> Unit)? = if (badge == null) null else {
        { StatusChip(badge, tone = StatusTone.Accent) }
    }
    CardRow(
        title = gym.name,
        modifier = modifier,
        supporting = gym.hoursSummary,
        onClick = onClick,
        leading = { GymIconTile() },
        badges = badgeContent,
        meta = {
            MetaLine(Icons.Outlined.Place, gym.distanceMeters?.let { "${gym.district} · ${distanceText(it)}" } ?: gym.district)
        },
    )
}

/** A goal on Start: an outlined tile with an icon and two lines, opening the Trainers tab with that goal. */
@Composable
fun GoalTile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(AppShapes.tile)
            .background(AppTheme.colors.surface)
            .border(BorderStroke(1.dp, AppTheme.colors.outline), AppShapes.tile)
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Icon(icon, contentDescription = null, tint = AppTheme.colors.brandStrong)
        Text(title, style = AppTheme.type.bodyStrong, color = AppTheme.colors.text)
        Text(subtitle, style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
    }
}

/** A benefit line with a success check, as on the login-required sheet and the join panel. */
@Composable
fun BenefitRow(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = AppTheme.colors.success, modifier = Modifier.size(20.dp))
        Text(text, style = AppTheme.type.compactBody, color = AppTheme.colors.text)
    }
}

/** The trainer's price list in one frame panel with dividers. The highlighted offer sits on the soft accent. */
@Composable
fun PriceList(offers: List<Offer>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.frame,
        color = AppTheme.colors.surface,
        border = BorderStroke(1.dp, AppTheme.colors.outline),
    ) {
        Column(Modifier.padding(vertical = Spacing.s)) {
            offers.forEachIndexed { index, offer ->
                if (index > 0) RowDivider(startIndent = Spacing.xl)
                PriceRow(offer)
            }
        }
    }
}

@Composable
private fun PriceRow(offer: Offer) {
    val details = listOfNotNull(
        offer.durationMinutes?.takeIf { offer.sessionCount == null || offer.sessionCount <= 1 }?.let { stringResource(Res.string.minutes, it) },
        offer.description,
        offer.validityText,
        if (offer.seller == Seller.Gym) stringResource(Res.string.sold_by_gym) else null,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s, vertical = Spacing.xs)
            .clip(AppShapes.tile)
            .background(if (offer.highlighted) AppTheme.colors.softAccent else AppTheme.colors.surface)
            .padding(horizontal = Spacing.m, vertical = Spacing.m),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(offer.title, style = AppTheme.type.bodyStrong, color = if (offer.highlighted) AppTheme.colors.onSoftAccent else AppTheme.colors.text)
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = AppTheme.type.compactBody,
                    color = if (offer.highlighted) AppTheme.colors.onSoftAccent else AppTheme.colors.textSecondary)
            }
            if (offer.highlighted) StatusChip(stringResource(Res.string.recommended), tone = StatusTone.Accent, icon = Icons.Outlined.Star)
        }
        // Prices never get clipped: the amount keeps its width and the title wraps instead.
        Text(formatMoney(offer.price), style = AppTheme.type.value, color = if (offer.highlighted) AppTheme.colors.onSoftAccent else AppTheme.colors.brandStrong,
            softWrap = false)
    }
}

/** A technique video tile. Only READY media with a URL is playable; the other states say why not. */
@Composable
fun MediaTile(media: PublicMedia, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val playLabel = stringResource(Res.string.media_play, media.title)
    val (icon, status) = when {
        media.playable -> Icons.Outlined.PlayArrow to null
        media.state == MediaState.FAILED -> Icons.Outlined.ErrorOutline to stringResource(Res.string.media_failed)
        media.state == MediaState.READY -> Icons.Outlined.VideocamOff to stringResource(Res.string.media_unavailable)
        else -> Icons.Outlined.HourglassEmpty to stringResource(Res.string.media_processing)
    }
    Column(
        modifier
            .width(200.dp)
            .clip(AppShapes.tile)
            .background(AppTheme.colors.surface)
            .border(BorderStroke(1.dp, AppTheme.colors.outline), AppShapes.tile)
            .then(if (media.playable) Modifier.clickable(role = Role.Button, onClickLabel = playLabel, onClick = onPlay) else Modifier)
            .padding(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Box(
            Modifier.fillMaxWidth().height(96.dp).clip(AppShapes.card).background(AppTheme.colors.subtleSurface),
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = CircleShape, color = if (media.playable) AppTheme.colors.brandStrong else AppTheme.colors.surface) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(Spacing.s).size(Sizes.icon),
                    tint = if (media.playable) AppTheme.colors.onBrandStrong else AppTheme.colors.textSecondary)
            }
        }
        Text(media.title, style = AppTheme.type.bodyStrong, color = AppTheme.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        media.subtitle?.let { Text(it, style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary) }
        status?.let { StatusChip(it, tone = if (media.state == MediaState.FAILED) StatusTone.Error else StatusTone.Neutral) }
    }
}

/** A single-choice tile (registration role) with radio semantics; the selected tile is soft accent with a check. */
@Composable
fun RoleTile(icon: ImageVector, title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(
        modifier
            .clip(AppShapes.tile)
            .background(if (selected) colors.softAccent else colors.surface)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.brandStrong else colors.outline), AppShapes.tile)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.onSoftAccent else colors.brandStrong)
            Spacer(Modifier.weight(1f))
            if (selected) Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = colors.onSoftAccent, modifier = Modifier.size(20.dp))
        }
        Text(title, style = AppTheme.type.bodyStrong, color = if (selected) colors.onSoftAccent else colors.text)
        Text(subtitle, style = AppTheme.type.compactBody, color = if (selected) colors.onSoftAccent else colors.textSecondary)
    }
}

/**
 * Shown when a guest tries a protected action. The screen behind keeps its state; "Nie teraz", swipe and the
 * scrim dismiss it.
 */
@Composable
fun LoginRequiredSheet(onRegister: () -> Unit, onLogIn: () -> Unit, onDismiss: () -> Unit) {
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Surface(color = AppTheme.colors.softAccent, shape = CircleShape) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = AppTheme.colors.brandStrong, modifier = Modifier.padding(Spacing.l).size(28.dp))
            }
            Text(stringResource(Res.string.login_required_title), style = AppTheme.type.sectionTitle, color = AppTheme.colors.text,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(Res.string.login_required_body), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center)
        }
        Column(Modifier.padding(vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            BenefitRow(stringResource(Res.string.benefit_requests))
            BenefitRow(stringResource(Res.string.benefit_chat))
            BenefitRow(stringResource(Res.string.benefit_progress))
        }
        PrimaryButton(stringResource(Res.string.create_account), onClick = onRegister, modifier = Modifier.fillMaxWidth(),
            icon = Icons.AutoMirrored.Outlined.ArrowForward)
        TonalButton(stringResource(Res.string.log_in), onClick = onLogIn, modifier = Modifier.fillMaxWidth())
        AppTextButton(stringResource(Res.string.not_now), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

/** The welcome pager's dots: 48 dp targets, "Slajd 1 z 3", and the current dot is wider (never colour alone). */
@Composable
fun PagerDots(count: Int, current: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val selected = index == current
            val label = stringResource(Res.string.welcome_slide_position, index + 1, count)
            Box(
                Modifier
                    .size(Sizes.minTouchTarget)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .height(8.dp)
                        .width(if (selected) 24.dp else 8.dp)
                        .clip(AppShapes.pill)
                        .background(if (selected) AppTheme.colors.brandStrong else AppTheme.colors.fieldOutline),
                )
            }
        }
    }
}

/** The verified badge (D2): display-only, with a label and an icon. */
@Composable
fun VerifiedChip() = StatusChip(stringResource(Res.string.verified), tone = StatusTone.Success, icon = Icons.Outlined.Verified)

/** Keeps a list readable on wide windows, as the trainer screens do. */
internal val ContentWidth = Modifier.widthIn(max = Sizes.contentWidth).fillMaxWidth()
