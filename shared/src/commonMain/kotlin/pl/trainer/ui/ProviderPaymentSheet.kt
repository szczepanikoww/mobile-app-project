package pl.trainer.ui

import androidx.compose.runtime.staticCompositionLocalOf
import pl.trainer.domain.CheckoutResult
import pl.trainer.domain.ProviderCheckout

/**
 * The payment provider's own sheet (Stripe PaymentSheet on Android), provided by the platform host. Card and BLIK data
 * are entered there and go straight to the provider; the app never sees them. Results go to [onResult], which the
 * presenting screen sets: the host may be recreated while the sheet is open (rotation), the screen's ViewModel is not.
 */
interface ProviderPaymentSheet {
    var onResult: ((CheckoutResult) -> Unit)?

    fun present(checkout: ProviderCheckout, merchantName: String)
}

/** Null where there is no provider sheet (desktop, previews, tests): a provider checkout then fails safely. */
val LocalProviderPaymentSheet = staticCompositionLocalOf<ProviderPaymentSheet?> { null }
