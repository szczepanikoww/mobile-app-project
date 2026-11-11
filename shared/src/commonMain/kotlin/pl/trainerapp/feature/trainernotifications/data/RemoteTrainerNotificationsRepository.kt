package pl.trainerapp.feature.trainernotifications.data

import com.trainingapp.contract.api.ProposerRole
import com.trainingapp.contract.api.RescheduleStatus
import com.trainingapp.contract.api.SessionStatus
import com.trainingapp.contract.api.TrainerDashboardDto
import com.trainingapp.contract.api.TrainerHomeDto
import kotlinx.datetime.TimeZone
import pl.trainer.data.remote.TrainerApiClient
import pl.trainer.domain.Failure
import pl.trainer.domain.TrainerException
import pl.trainerapp.feature.trainernotifications.TrainerNotificationsRepository
import pl.trainerapp.feature.trainernotifications.model.NotificationDetail
import pl.trainerapp.feature.trainernotifications.model.NotificationTarget
import pl.trainerapp.feature.trainernotifications.model.NotificationType
import pl.trainerapp.feature.trainernotifications.model.NotificationsSnapshot
import pl.trainerapp.feature.trainernotifications.model.TrainerNotification

/**
 * Notifications DERIVED from the current server state (the server has no notification table):
 * - `GET trainer/dashboard`: sessions in REQUESTED -> `request-<sessionId>`; pending client reschedule proposals ->
 *   `proposal-<sessionId>-<requestedAt>`; both target the session.
 * - `GET trainer/home`: unread messages and pending technique videos (only counts exist, so the target is None and
 *   the id carries the count: a new count is a new, unread notification); clients with an overdue payment ->
 *   `overdue-<clientId>`, targeting that client's chat.
 *
 * Privacy: texts carry only client names and counts, never message text, health data or video content.
 * Read state is kept in memory (ids). The last successful snapshot is cached in memory and returned flagged offline.
 */
class RemoteTrainerNotificationsRepository(private val api: TrainerApiClient) : TrainerNotificationsRepository {
    private var last: NotificationsSnapshot? = null
    private val readIds = mutableSetOf<String>()

    override suspend fun notifications(): NotificationsSnapshot {
        return try {
            fetch().also { last = it }
        } catch (e: TrainerException) {
            val cached = last
            if (e.failure == Failure.Offline && cached != null) withRead(cached).copy(isOffline = true) else throw e
        }
    }

    override suspend fun open(id: String): NotificationDetail {
        var snapshot = last
        if (snapshot == null || snapshot.items.none { it.id == id }) snapshot = notifications()
        val found = snapshot.items.firstOrNull { it.id == id } ?: error("Unknown notification")
        readIds += id
        return NotificationDetail(found.copy(read = true), snapshot.generatedAt)
    }

    override suspend fun markAllRead() {
        val snapshot = last ?: notifications()
        readIds += snapshot.items.map { it.id }
    }

    private fun withRead(s: NotificationsSnapshot) = s.copy(items = s.items.map { it.copy(read = it.id in readIds) })

    private suspend fun fetch(): NotificationsSnapshot {
        val dashboard = api.get<TrainerDashboardDto>("trainer/dashboard")
        val home = api.get<TrainerHomeDto>("trainer/home")
        val now = home.serverNow
        val names = (dashboard.clients + home.clients).associate { it.id to it.name }
        fun name(clientId: String) = names[clientId] ?: "klienta"
        val items = mutableListOf<TrainerNotification>()

        for (s in dashboard.sessions) {
            val zone = runCatching { TimeZone.of(s.timeZone) }.getOrDefault(TimeZone.UTC)
            val target = NotificationTarget.Session(s.id)
            if (s.status == SessionStatus.REQUESTED) {
                items += TrainerNotification(
                    id = "request-${s.id}", type = NotificationType.Session,
                    title = "Prośba o sesję od ${name(s.clientId)}", body = s.location,
                    detail = "${name(s.clientId)} prosi o potwierdzenie sesji. Miejsce: ${s.location}.",
                    at = s.requestedAt, zone = zone, read = false, target = target,
                )
            }
            val p = s.proposal
            if (p != null && p.author == ProposerRole.CLIENT &&
                (p.status == RescheduleStatus.PENDING || p.status == RescheduleStatus.COUNTER_PROPOSED)
            ) {
                items += TrainerNotification(
                    id = "proposal-${s.id}-${p.requestedAt}", type = NotificationType.Session,
                    title = "Prośba o zmianę terminu od ${name(s.clientId)}", body = "Czeka na Twoją decyzję",
                    detail = "${name(s.clientId)} proponuje nowy termin sesji. Do czasu Twojej decyzji obowiązuje dotychczasowy termin.",
                    at = p.requestedAt, zone = zone, read = false, target = target,
                )
            }
        }
        if (home.unreadMessages > 0) {
            items += TrainerNotification(
                id = "messages-${home.unreadMessages}", type = NotificationType.Message,
                title = "Nowe wiadomości", body = "Nieprzeczytane: ${home.unreadMessages}",
                detail = "Masz nieprzeczytane wiadomości w czacie (${home.unreadMessages}).",
                at = now, zone = TimeZone.UTC, read = false,
            )
        }
        if (home.pendingVideos > 0) {
            items += TrainerNotification(
                id = "videos-${home.pendingVideos}", type = NotificationType.Video,
                title = "Wideo techniki do obejrzenia", body = "Czeka na ocenę: ${home.pendingVideos}",
                detail = "Klienci wysłali nagrania techniki, które czekają na Twoją ocenę (${home.pendingVideos}).",
                at = now, zone = TimeZone.UTC, read = false,
            )
        }
        for (c in home.clients.filter { it.hasOverduePayment }) {
            items += TrainerNotification(
                id = "overdue-${c.id}", type = NotificationType.Payment,
                title = "Zaległa płatność", body = c.name,
                detail = "Płatność klienta ${c.name} jest zaległa. Możesz przypomnieć o niej w czacie.",
                at = now, zone = TimeZone.UTC, read = false, target = NotificationTarget.Chat(c.id, c.name),
            )
        }
        val sorted = items.sortedByDescending { it.at }
        return withRead(NotificationsSnapshot(sorted, generatedAt = now, isOffline = false, lastUpdated = now))
    }
}
