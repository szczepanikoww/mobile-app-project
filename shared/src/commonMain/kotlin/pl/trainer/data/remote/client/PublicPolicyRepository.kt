package pl.trainer.data.remote.client

import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.CancellationPolicy
import com.trainingapp.contract.policy.PolicyRules
import com.trainingapp.contract.schedule.BookingOptionsDto
import com.trainingapp.contract.schedule.PolicyVersionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pl.trainer.domain.Failure
import pl.trainer.domain.PolicyRepository
import pl.trainer.domain.TrainerException

/**
 * Read-only access to other trainers' cancellation policies for the client views (a drop-in for the demo `others`
 * of `RemotePolicyRepository`). A version comes from `GET /v1/policies/{versionId}` (the server allows it for the
 * client's own sessions and for a public trainer's current version); the current version and the session price of a
 * trainer come from `GET /v1/client/trainers/{id}/booking-options`. Versions are immutable, so they are kept in memory.
 * Saving is the trainer's own flow and is denied here.
 */
class PublicPolicyRepository(private val api: ClientApi) : PolicyRepository {
    override val updates: StateFlow<Long> = MutableStateFlow(0L)

    private val versions = mutableMapOf<String, CancellationPolicy>()
    private val options = mutableMapOf<String, BookingOptionsDto>()

    private suspend fun options(trainerId: String): BookingOptionsDto = try {
        api.get("v1/client/trainers/$trainerId/booking-options", BookingOptionsDto.serializer())
            .also { options[trainerId] = it }
    } catch (e: ClientApiOfflineException) {
        options[trainerId] ?: throw TrainerException(Failure.Offline)
    } catch (e: Throwable) {
        throw e.toTrainerException(Op.Read)
    }

    override suspend fun current(trainerId: String): CancellationPolicy =
        options(trainerId).policy.toModel().also { versions[it.versionId] = it }

    override suspend fun version(versionId: String): CancellationPolicy {
        versions[versionId]?.let { return it }
        return try {
            api.get("v1/policies/$versionId", PolicyVersionDto.serializer()).toModel().also { versions[versionId] = it }
        } catch (e: Throwable) {
            throw e.toTrainerException(Op.Read)
        }
    }

    override suspend fun sessionPrice(trainerId: String): Money = options(trainerId).price.toMoney()

    override suspend fun save(trainerId: String, rules: PolicyRules, basedOnVersionId: String): CancellationPolicy =
        throw TrainerException(Failure.Denied)
}
