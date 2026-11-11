package com.trainingapp.server.home

import com.trainingapp.contract.api.RescheduleStatus
import com.trainingapp.contract.api.SessionStatus
import com.trainingapp.contract.api.TrainerHomeDto
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.server.db.AppUsers
import com.trainingapp.server.db.Conversations
import com.trainingapp.server.db.MediaAssets
import com.trainingapp.server.db.MessageAttachments
import com.trainingapp.server.db.Messages
import com.trainingapp.server.db.ReadMarkers
import com.trainingapp.server.db.RescheduleRequests
import com.trainingapp.server.db.Sessions
import com.trainingapp.server.db.TrainerClients
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.toOffset
import com.trainingapp.server.scheduling.SessionReads
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.time.toKotlinInstant

/** The trainer's start screen: today, what needs attention, and the week at a glance. */
class TrainerHome(private val db: Db, private val clock: ServerClock) {

    suspend fun home(trainerId: UUID): TrainerHomeDto = db.tx {
        val now = clock.now()
        val zone = runCatching { ZoneId.of(AppUsers.selectAll().where { AppUsers.id eq trainerId }.single()[AppUsers.timeZone]) }
            .getOrDefault(ZoneId.of("UTC"))
        val today = java.time.Instant.ofEpochSecond(now.epochSeconds).atZone(zone).toLocalDate()
        val dayStart = today.atStartOfDay(zone).toInstant().toKotlinInstant()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toKotlinInstant()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toKotlinInstant()
        val weekEnd = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1).atStartOfDay(zone).toInstant().toKotlinInstant()

        val todayStatuses = listOf("CONFIRMED", "COMPLETED", "NO_SHOW", "REQUESTED")
        val todaySessions = SessionReads.sessions(trainerId, dayStart, dayEnd).filter { it.status.name in todayStatuses }

        val nextRow = Sessions.selectAll()
            .where { (Sessions.trainerId eq trainerId) and (Sessions.status eq "CONFIRMED") and (Sessions.startsAt greater now.toOffset()) }
            .orderBy(Sessions.startsAt, SortOrder.ASC).limit(1).singleOrNull()
        val clients = SessionReads.clients(trainerId, includeEnded = false)

        val requestedCount = Sessions.selectAll()
            .where { (Sessions.trainerId eq trainerId) and (Sessions.status eq SessionStatus.REQUESTED.name) and (Sessions.startsAt greater now.toOffset()) }
            .count().toInt()
        val rescheduleCount = RescheduleRequests.join(Sessions, JoinType.INNER, RescheduleRequests.sessionId, Sessions.id)
            .selectAll()
            .where {
                (Sessions.trainerId eq trainerId) and (RescheduleRequests.proposerRole eq "CLIENT") and
                    (RescheduleRequests.status eq RescheduleStatus.PENDING.name)
            }.count().toInt()

        val (unread, videos) = unread(trainerId)

        val week = Sessions.selectAll()
            .where { (Sessions.trainerId eq trainerId) and (Sessions.startsAt greaterEq weekStart.toOffset()) and (Sessions.startsAt less weekEnd.toOffset()) }
            .map { it[Sessions.status] }

        TrainerHomeDto(
            todaySessions = todaySessions,
            nextSession = nextRow?.let { SessionReads.toDto(it) },
            clients = clients,
            openRequests = requestedCount + rescheduleCount,
            unreadMessages = unread,
            pendingVideos = videos,
            overdueClients = clients.count { it.hasOverduePayment },
            weekSessionsDone = week.count { it == "COMPLETED" },
            weekSessionsPlanned = week.count { it == "CONFIRMED" || it == "COMPLETED" || it == "NO_SHOW" },
            serverNow = now,
        )
    }

    /** Messages from clients after the trainer's read marker, and how many of them carry a technique video. */
    private fun unread(trainerId: UUID): Pair<Int, Int> {
        val conversations = Conversations.join(TrainerClients, JoinType.INNER, Conversations.relationshipId, TrainerClients.id)
            .selectAll().where { (TrainerClients.trainerId eq trainerId) and (TrainerClients.status eq "ACTIVE") }
            .map { it[Conversations.id] }
        var messages = 0
        var videos = 0
        for (conversation in conversations) {
            val lastRead = ReadMarkers.selectAll()
                .where { (ReadMarkers.conversationId eq conversation) and (ReadMarkers.userId eq trainerId) }
                .singleOrNull()?.get(ReadMarkers.lastReadSeq) ?: 0L
            val rows = Messages.selectAll()
                .where {
                    (Messages.conversationId eq conversation) and (Messages.seq greater lastRead) and
                        (Messages.senderId neq trainerId) and Messages.deletedAt.isNull()
                }.map { it[Messages.id] }
            messages += rows.size
            if (rows.isNotEmpty()) {
                videos += MessageAttachments.join(MediaAssets, JoinType.INNER, MessageAttachments.mediaId, MediaAssets.id)
                    .selectAll()
                    .where { (MessageAttachments.messageId inList rows) and (MediaAssets.kind eq MediaKind.TECHNIQUE_VIDEO.name) }
                    .count().toInt()
            }
        }
        return messages to videos
    }
}
