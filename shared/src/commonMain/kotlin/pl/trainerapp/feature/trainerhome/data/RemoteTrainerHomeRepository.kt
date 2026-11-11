package pl.trainerapp.feature.trainerhome.data

import com.trainingapp.contract.api.ClientSummaryDto
import com.trainingapp.contract.api.SessionDto
import com.trainingapp.contract.api.TrainerHomeDto
import kotlinx.datetime.TimeZone
import pl.trainer.data.remote.TrainerApiClient
import pl.trainer.domain.Failure
import pl.trainer.domain.TrainerException
import pl.trainerapp.feature.trainerhome.TrainerHomeRepository
import pl.trainerapp.feature.trainerhome.model.AttentionItem
import pl.trainerapp.feature.trainerhome.model.ClientRef
import pl.trainerapp.feature.trainerhome.model.HomeSnapshot
import pl.trainerapp.feature.trainerhome.model.SessionStatus
import pl.trainerapp.feature.trainerhome.model.SessionSummary
import pl.trainerapp.feature.trainerhome.model.TrainerHomeSummary
import pl.trainerapp.feature.trainerhome.model.WeekStats
import com.trainingapp.contract.api.SessionStatus as ApiSessionStatus

/**
 * The trainer's start screen from `GET trainer/home`.
 *
 * Mapping notes (the DTO does not carry everything the screen model has):
 * - [TrainerHomeSummary.trainerName] is the first name from the DTO; [WeekStats.newClients] / [WeekStats.cancellations]
 *   are 0 (not in the DTO).
 * - Unread messages are only a total on the server, so [TrainerHomeSummary.unreadChats] carries it but no
 *   [AttentionItem.UnreadMessages] is produced (those need a client). Technique videos likewise come from
 *   [techniqueVideos] (the shell's chat store); the DTO only has a count.
 * - [AttentionItem.OverduePayment] is produced for every client with the server's overdue flag.
 * - [SessionSummary.checkInOpen] is derived from the server: a confirmed session that has not ended yet on the
 *   server clock ([TrainerHomeDto.serverNow]). The check-in screen itself asks the server again.
 * - Session statuses the home model lacks map as follows: NO_SHOW -> Completed (the slot is over), REJECTED, EXPIRED and
 *   both cancellations -> Cancelled.
 * - Only the last successful snapshot is cached, in memory; without a connection it is returned flagged offline.
 */
class RemoteTrainerHomeRepository(
    private val api: TrainerApiClient,
    private val techniqueVideos: suspend () -> List<AttentionItem> = { emptyList() },
) : TrainerHomeRepository {
    private var last: HomeSnapshot? = null

    override suspend fun load(): HomeSnapshot {
        val dto = try {
            api.get<TrainerHomeDto>("trainer/home")
        } catch (e: TrainerException) {
            val cached = last
            if (e.failure == Failure.Offline && cached != null) return cached.copy(isOffline = true)
            throw e
        }
        val videos = runCatching { techniqueVideos() }.getOrDefault(emptyList())
        return HomeSnapshot(dto.toSummary(videos), isOffline = false, lastUpdated = dto.serverNow).also { last = it }
    }
}

internal fun TrainerHomeDto.toSummary(videos: List<AttentionItem> = emptyList()): TrainerHomeSummary {
    val names = clients.associateBy { it.id }
    fun SessionDto.toSummary() = SessionSummary(
        id = id,
        client = names[clientId].toRef(clientId),
        start = start,
        end = end,
        zone = runCatching { TimeZone.of(timeZone) }.getOrDefault(TimeZone.UTC),
        location = location,
        planTitle = planTitle,
        status = status.toHome(),
        checkInOpen = status == ApiSessionStatus.CONFIRMED && serverNow < end,
    )

    val today = todaySessions.sortedBy { it.start }
    val next = nextSession
    val nextIsToday = next != null && today.any { it.id == next.id }
    val carouselRest = if (nextIsToday) today.filterNot { it.id == next!!.id } else today
    val overdue = clients.filter { it.hasOverduePayment }.map { AttentionItem.OverduePayment("overdue-${it.id}", it.toRef(it.id)) }

    return TrainerHomeSummary(
        generatedAt = serverNow,
        nextSession = if (nextIsToday) next!!.toSummary() else null,
        todaySessions = carouselRest.map { it.toSummary() },
        attention = videos + overdue,
        upcomingAfterToday = if (next != null && !nextIsToday) next.toSummary() else null,
        isNewTrainer = clients.isEmpty() && today.isEmpty() && next == null,
        trainerName = trainerName?.substringBefore(' ')?.takeIf { it.isNotBlank() },
        unreadChats = unreadMessages,
        hasNotifications = openRequests > 0 || unreadMessages > 0 || pendingVideos > 0,
        week = WeekStats(weekSessionsDone, weekSessionsPlanned, newClients = 0, cancellations = 0),
    )
}

private fun ClientSummaryDto?.toRef(id: String) = ClientRef(id, this?.name ?: "—")

private fun ApiSessionStatus.toHome(): SessionStatus = when (this) {
    ApiSessionStatus.REQUESTED -> SessionStatus.Requested
    ApiSessionStatus.CONFIRMED -> SessionStatus.Confirmed
    ApiSessionStatus.COMPLETED, ApiSessionStatus.NO_SHOW -> SessionStatus.Completed
    ApiSessionStatus.REJECTED, ApiSessionStatus.EXPIRED,
    ApiSessionStatus.CANCELLED_BY_CLIENT, ApiSessionStatus.CANCELLED_BY_TRAINER -> SessionStatus.Cancelled
}
