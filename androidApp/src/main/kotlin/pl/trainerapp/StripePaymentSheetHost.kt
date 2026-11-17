package pl.trainerapp

import androidx.activity.ComponentActivity
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import pl.trainer.domain.CheckoutResult
import pl.trainer.domain.ProviderCheckout
import pl.trainer.ui.ProviderPaymentSheet

/**
 * Stripe's PaymentSheet behind the app's [ProviderPaymentSheet]. Built in the activity's `onCreate` (it registers an
 * activity result), so a sheet that is open while the activity is recreated still reports back. The publishable key
 * comes from the server with each payment; nothing Stripe-specific is configured in the app.
 */
class StripePaymentSheetHost(private val activity: ComponentActivity) : ProviderPaymentSheet {
    override var onResult: ((CheckoutResult) -> Unit)? = null

    private val sheet: PaymentSheet = PaymentSheet.Builder(::deliver).build(activity)

    override fun present(checkout: ProviderCheckout, merchantName: String) {
        PaymentConfiguration.init(activity.applicationContext, checkout.publishableKey)
        val configuration = PaymentSheet.Configuration.Builder(merchantName).build()
        sheet.presentWithPaymentIntent(checkout.clientSecret, configuration)
    }

    private fun deliver(result: PaymentSheetResult) {
        val mapped = when (result) {
            is PaymentSheetResult.Completed -> CheckoutResult.Completed
            is PaymentSheetResult.Canceled -> CheckoutResult.Canceled
            is PaymentSheetResult.Failed -> CheckoutResult.Failed(result.error.message)
            else -> CheckoutResult.Failed(null)
        }
        onResult?.invoke(mapped)
    }
}
