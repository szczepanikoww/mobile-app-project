package pl.trainerapp.feature.chat.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The client's trainer–client relationship that their chat and technique videos belong to. */
data class ClientRelationship(
    val relationshipId: String,
    val trainerId: String,
    val trainerName: String,
    /** False once the relationship has ended: the history stays readable, sending is refused. */
    val canSend: Boolean,
)

/**
 * The signed-in client's active relationship, from `GET /v1/client/conversations`. [relationshipId] can be read
 * synchronously (empty until the first [refresh]); a failed refresh keeps the last known value, so a flaky
 * connection never forgets the relationship. Call [clear] on sign-out.
 */
class RemoteClientContext(private val api: ChatServerApi) {
    private val _relationship = MutableStateFlow<ClientRelationship?>(null)
    val relationship: StateFlow<ClientRelationship?> = _relationship.asStateFlow()

    /** The relationship id, or `null` until it is known. */
    val relationshipId: String? get() = _relationship.value?.relationshipId

    private val lock = Mutex()

    /** Asks the server; with several conversations the first one that can still send wins. */
    suspend fun refresh(): ClientRelationship? = lock.withLock {
        try {
            val rows = api.conversations()
            val row = rows.firstOrNull { it.canSend } ?: rows.firstOrNull()
            _relationship.value = row?.let {
                ClientRelationship(it.relationshipId, it.counterpart.id, it.counterpart.name, it.canSend)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep what we know.
        }
        _relationship.value
    }

    /** The known relationship, or one fresh attempt to find it. */
    suspend fun ensure(): ClientRelationship? = _relationship.value ?: refresh()

    fun clear() {
        _relationship.value = null
    }
}
