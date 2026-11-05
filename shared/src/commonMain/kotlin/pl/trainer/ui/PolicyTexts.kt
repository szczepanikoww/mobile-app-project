package pl.trainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.PolicyRules
import com.trainingapp.domain.format.LocaleFormats
import kotlinx.datetime.toLocalDateTime
import pl.trainer.resources.*

/** "1 września 2026": when a policy version was created, in the trainer's zone. */
fun formatPolicyDate(instant: kotlin.time.Instant): String =
    DateFormats.date(instant.toLocalDateTime(TrainerZone).date, DateFormats.defaultLanguage(), "d MMMM yyyy")

/** A money amount in the app's locale ("60 zł"). */
fun formatMoney(money: Money): String = LocaleFormats.formatMoney(money, DateFormats.defaultLanguage())

/**
 * The plain-language lines of a cancellation policy, exactly as the client sees them when booking (B2), in the
 * trainer's request details (B4) and in the policy editor's client preview (B5). With [price], each percentage also
 * shows its amount.
 */
@Composable
fun policyLines(rules: PolicyRules, price: Money? = null): List<String> {
    val tiers = rules.sortedTiers
    // "50% (60 zł)" when the price is known; the suffix is a resource so the word order can differ per language.
    val suffix = tr(Res.string.policy_amount_suffix, "\u0000")
    fun amount(percent: Int) = price?.let { suffix.replace("\u0000", formatMoney(it.percentOf(percent))) }.orEmpty()
    val lines = mutableListOf<String>()
    if (tiers.isEmpty()) {
        lines += tr(Res.string.policy_all_free)
    } else {
        lines += tr(Res.string.policy_free_before, tiers.first().hoursBefore)
        tiers.forEachIndexed { index, tier ->
            val lower = tiers.getOrNull(index + 1)?.hoursBefore
            val percent = "${tier.feePercent}%" + amount(tier.feePercent)
            lines += if (lower != null) tr(Res.string.policy_tier_range, lower, tier.hoursBefore, percent)
            else tr(Res.string.policy_tier_last, tier.hoursBefore, percent)
        }
    }
    lines += tr(Res.string.policy_no_show, "${rules.noShowPercent}%" + amount(rules.noShowPercent))
    lines += tr(Res.string.policy_grace, rules.graceMinutes)
    lines += tr(Res.string.policy_free_late, rules.freeLateCancellationsPerMonth)
    return lines
}

@Composable
fun CancellationPolicySummary(rules: PolicyRules, modifier: Modifier = Modifier, price: Money? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Layout.small)) {
        policyLines(rules, price).forEach { line ->
            Text("• $line", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
