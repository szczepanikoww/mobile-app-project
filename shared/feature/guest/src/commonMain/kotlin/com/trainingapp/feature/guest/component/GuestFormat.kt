package com.trainingapp.feature.guest.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.intl.Locale
import com.trainingapp.domain.format.LocaleFormats
import com.trainingapp.domain.format.Money
import com.trainingapp.feature.guest.model.Amenity
import com.trainingapp.feature.guest.model.Availability
import com.trainingapp.feature.guest.model.Goal
import com.trainingapp.feature.guest.model.Offer
import com.trainingapp.feature.guest.model.PriceRange
import com.trainingapp.feature.guest.model.Rating
import com.trainingapp.feature.guest.model.TrainerSort
import com.trainingapp.feature.guest.resources.*
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The language the resources are shown in, so numbers and money match the copy around them. */
@Composable
@ReadOnlyComposable
internal fun uiLanguage(): String = Locale.current.toLanguageTag()

@Composable
internal fun formatMoney(money: Money): String = LocaleFormats.formatMoney(money, uiLanguage())

@Composable
internal fun startingPriceText(offer: Offer?): String = when {
    offer == null -> stringResource(Res.string.price_on_request)
    offer.durationMinutes != null -> stringResource(Res.string.price_from, formatMoney(offer.price), offer.durationMinutes)
    else -> stringResource(Res.string.price_from_short, formatMoney(offer.price))
}

/** "4,9 · 42 opinie". */
@Composable
internal fun ratingText(rating: Rating): String = stringResource(
    Res.string.rating_value,
    LocaleFormats.formatDecimal(rating.average, uiLanguage()),
    pluralStringResource(Res.plurals.reviews_count, rating.count, rating.count),
)

@Composable
internal fun ratingDescription(rating: Rating): String = stringResource(
    Res.string.rating_description,
    LocaleFormats.formatDecimal(rating.average, uiLanguage()),
    pluralStringResource(Res.plurals.reviews_count, rating.count, rating.count),
)

@Composable
internal fun distanceText(meters: Int): String = if (meters < 1000) {
    stringResource(Res.string.distance_meters, meters)
} else {
    stringResource(Res.string.distance_km, LocaleFormats.formatDecimal(meters / 1000.0, uiLanguage()))
}

internal fun Goal.label(): StringResource = when (this) {
    Goal.Reduction -> Res.string.goal_reduction
    Goal.Strength -> Res.string.goal_strength
    Goal.Mobility -> Res.string.goal_mobility
    Goal.Competition -> Res.string.goal_competition
}

internal fun Goal.hint(): StringResource = when (this) {
    Goal.Reduction -> Res.string.goal_reduction_hint
    Goal.Strength -> Res.string.goal_strength_hint
    Goal.Mobility -> Res.string.goal_mobility_hint
    Goal.Competition -> Res.string.goal_competition_hint
}

internal fun Availability.label(): StringResource = when (this) {
    Availability.Morning -> Res.string.availability_morning
    Availability.Afternoon -> Res.string.availability_afternoon
    Availability.Evening -> Res.string.availability_evening
    Availability.Weekend -> Res.string.availability_weekend
}

internal fun Amenity.label(): StringResource = when (this) {
    Amenity.FreeWeights -> Res.string.amenity_free_weights
    Amenity.Cardio -> Res.string.amenity_cardio
    Amenity.Parking -> Res.string.amenity_parking
    Amenity.Showers -> Res.string.amenity_showers
    Amenity.Sauna -> Res.string.amenity_sauna
    Amenity.FunctionalZone -> Res.string.amenity_functional
}

internal fun TrainerSort.label(): StringResource = when (this) {
    TrainerSort.PriceAscending -> Res.string.sort_price_ascending
    TrainerSort.PriceDescending -> Res.string.sort_price_descending
    TrainerSort.Rating -> Res.string.sort_rating
}

internal fun DayOfWeek.label(): StringResource = when (this) {
    DayOfWeek.MONDAY -> Res.string.day_monday
    DayOfWeek.TUESDAY -> Res.string.day_tuesday
    DayOfWeek.WEDNESDAY -> Res.string.day_wednesday
    DayOfWeek.THURSDAY -> Res.string.day_thursday
    DayOfWeek.FRIDAY -> Res.string.day_friday
    DayOfWeek.SATURDAY -> Res.string.day_saturday
    DayOfWeek.SUNDAY -> Res.string.day_sunday
}

@Composable
internal fun priceRangeText(range: PriceRange): String {
    val min = range.minMinor?.let { formatMoney(Money(it, "PLN")) }
    val max = range.maxMinor?.let { formatMoney(Money(it, "PLN")) }
    return when {
        min == null && max != null -> stringResource(Res.string.range_up_to, max)
        min != null && max != null -> stringResource(Res.string.range_between, min, max)
        min != null -> stringResource(Res.string.range_from, min)
        else -> stringResource(Res.string.any_price)
    }
}

internal fun LocalTime.hhmm(): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
