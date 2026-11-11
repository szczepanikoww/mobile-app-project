package pl.trainer.data.remote.client

import com.trainingapp.contract.wallet.PayRequestDto
import com.trainingapp.contract.wallet.PayResponseDto
import com.trainingapp.contract.wallet.WalletDto
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import pl.trainer.domain.Failure
import pl.trainer.domain.Payment
import pl.trainer.domain.PaymentMethod
import pl.trainer.domain.TrainerException
import pl.trainer.domain.Wallet
import pl.trainer.domain.WalletRepository

/**
 * The signed-in client's packages, charges and payments from the server (`/v1/client/wallet`).
 *
 * A charge is [pl.trainer.domain.ChargeState.Settled] only when the server says so: [pay] returns a pending payment,
 * and the provider's confirmation arrives at the server later, so the screen reloads (see [updates]) until it does.
 * Reads fall back to the last loaded wallet when offline ([lastLoadWasCached]); [pay] offline fails with
 * [Failure.Offline] and nothing is sent. The same `idempotencyKey` always maps to the same operation id.
 */
class RemoteWalletRepository(
    private val api: ClientApi,
    private val identity: () -> String? = { null },
    private val updated: MutableStateFlow<Long> = MutableStateFlow(0L),
) : WalletRepository {
    override val updates: StateFlow<Long> get() = updated

    var lastLoadWasCached: Boolean = false
        private set

    private var cacheOwner: String? = null
    private var cached: Wallet? = null

    private fun owner() {
        val who = identity()
        if (who != cacheOwner) {
            cacheOwner = who
            cached = null
        }
    }

    override suspend fun load(): Wallet {
        owner()
        return try {
            api.get("v1/client/wallet", WalletDto.serializer()).toModel().also {
                cached = it
                lastLoadWasCached = false
            }
        } catch (e: ClientApiOfflineException) {
            lastLoadWasCached = true
            cached ?: throw TrainerException(Failure.Offline)
        } catch (e: Throwable) {
            throw e.toTrainerException(Op.Read)
        }
    }

    override suspend fun pay(chargeId: String, method: PaymentMethod, idempotencyKey: String): Payment {
        if (method == PaymentMethod.Cash) throw TrainerException(Failure.Save) // cash is recorded by the trainer, never paid in the app
        val operationId = operationIdFor(idempotencyKey)
        val answer = try {
            api.send(
                HttpMethod.Post,
                "v1/client/charges/$chargeId/payments",
                PayRequestDto(method.toDto()),
                PayRequestDto.serializer(),
                PayResponseDto.serializer(),
                operationId,
            ) ?: throw TrainerException(Failure.Save)
        } catch (e: Throwable) {
            throw e.toTrainerException(Op.Write)
        }
        val payment = answer.payment.toModel()
        // The cached wallet is stale now (the charge is pending); the next load shows the server's state.
        updated.update { it + 1 }
        return payment
    }
}
