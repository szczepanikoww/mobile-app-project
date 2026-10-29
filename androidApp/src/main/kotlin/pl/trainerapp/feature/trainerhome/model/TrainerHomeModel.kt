package pl.trainerapp.feature.trainerhome.model

import java.time.Instant
import java.time.ZoneId

// Local stand-ins for the shared contract (:contract) until it exists. Keep them free of Android types.

enum class SessionStatus { Requested, Confirmed, Cancelled, Completed }

data class ClientRef(val id: String, val name: String)

data class SessionSummary(
    val id: String,
    val client: ClientRef,
    val start: Instant,
    val end: Instant,
    val zone: ZoneId,
    val location: String,
    val planTitle: String?,
    val status: SessionStatus,
    /** Provided by the server; never derived from the device clock. */
    val checkInOpen: Boolean,
)

sealed interface AttentionItem {
    val id: String
    val client: ClientRef

    /** Only listed when the media is READY; unfinished uploads never appear here. */
    data class TechniqueVideo(override val id: String, override val client: ClientRef, val exercise: String) :
        AttentionItem

    data class UnreadMessages(
        override val id: String,
        override val client: ClientRef,
        val count: Int,
        /** Subject of the latest message, when known. */
        val topic: String? = null,
    ) : AttentionItem

    /** Yes/no flag only; amounts belong to the finance flow. */
    data class OverduePayment(override val id: String, override val client: ClientRef) : AttentionItem
}

/** Counters for the current week. Display only. */
data class WeekStats(
    val sessionsDone: Int,
    val sessionsPlanned: Int,
    val newClients: Int,
    val cancellations: Int,
)

data class TrainerHomeSummary(
    /** Server time of the snapshot; used for display only ("Za 45 min"), never for policy. */
    val generatedAt: Instant,
    val nextSession: SessionSummary?,
    val todaySessions: List<SessionSummary>,
    val attention: List<AttentionItem>,
    val upcomingAfterToday: SessionSummary? = null,
    val isNewTrainer: Boolean = false,
    val trainerName: String? = null,
    val unreadChats: Int = 0,
    val hasNotifications: Boolean = false,
    val week: WeekStats? = null,
) {
    /** Next session first, then the rest of today: the "Dzisiejsze sesje" carousel. */
    val carouselSessions: List<SessionSummary> get() = listOfNotNull(nextSession) + todaySessions
}

data class HomeSnapshot(
    val summary: TrainerHomeSummary,
    val isOffline: Boolean,
    val lastUpdated: Instant,
)
