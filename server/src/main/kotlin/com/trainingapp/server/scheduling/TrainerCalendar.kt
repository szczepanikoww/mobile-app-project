package com.trainingapp.server.scheduling

import com.trainingapp.contract.api.AvailabilityDto
import com.trainingapp.contract.api.AvailabilityRuleDto
import com.trainingapp.contract.api.CalendarBlockDto
import com.trainingapp.contract.api.CreateBlockRequest
import com.trainingapp.contract.api.TrainerDashboardDto
import com.trainingapp.server.db.AppUsers
import com.trainingapp.server.db.AvailabilityRules
import com.trainingapp.server.db.CalendarBlocks
import com.trainingapp.server.db.CoachingNotes
import com.trainingapp.server.db.Gyms
import com.trainingapp.server.db.TrainerClients
import com.trainingapp.server.db.TrainerProfiles
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.invalid
import com.trainingapp.server.platform.newId
import com.trainingapp.server.platform.notFound
import com.trainingapp.server.platform.toInstantK
import com.trainingapp.server.platform.toOffset
import com.trainingapp.server.platform.toUuid
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Calendar reads, availability blocks and weekly working hours of the trainer. */
class TrainerCalendar(private val db: Db, private val clock: ServerClock) {

    /**
     * Everything the calendar and clients screens need in one call (TrainerRepository.load). Sessions in [from, to)
     * plus every open request regardless of date; defaults: one week back, eight weeks ahead.
     */
    suspend fun dashboard(trainerId: UUID, from: Instant?, to: Instant?): TrainerDashboardDto = db.tx {
        val now = clock.now()
        val start = from ?: (now - 7.days)
        val end = to ?: (now + 56.days)
        if (end <= start || end - start > 400.days) invalid("Invalid range")
        val sessions = (SessionReads.sessions(trainerId, start, end) + SessionReads.openRequestSessions(trainerId))
            .distinctBy { it.id }.sortedBy { it.start }
        TrainerDashboardDto(
            clients = SessionReads.clients(trainerId),
            sessions = sessions,
            blocks = SessionReads.blocks(trainerId, start, end),
            notes = latestNotes(trainerId),
            serverNow = now,
        )
    }

    /** The newest non-deleted note per relationship, written by this trainer (notes are visible to the author only). */
    private fun latestNotes(trainerId: UUID): Map<String, String> =
        CoachingNotes.join(TrainerClients, org.jetbrains.exposed.sql.JoinType.INNER, CoachingNotes.relationshipId, TrainerClients.id)
            .selectAll()
            .where { (TrainerClients.trainerId eq trainerId) and (CoachingNotes.authorId eq trainerId) and CoachingNotes.deletedAt.isNull() }
            .orderBy(CoachingNotes.updatedAt, SortOrder.DESC)
            .groupBy { it[CoachingNotes.relationshipId] }
            .mapKeys { it.key.toString() }
            .mapValues { it.value.first()[CoachingNotes.body] }

    suspend fun addBlock(trainerId: UUID, request: CreateBlockRequest): CalendarBlockDto = db.tx {
        if (request.end <= request.start) invalid("End must be after start")
        if (request.end - request.start > 31.days) invalid("Block is too long")
        if ((request.title?.length ?: 0) > 120) invalid("Title too long")
        val id = newId()
        CalendarBlocks.insert {
            it[CalendarBlocks.id] = id
            it[CalendarBlocks.trainerId] = trainerId
            it[kind] = request.kind.name
            it[title] = request.title?.takeIf { t -> t.isNotBlank() }
            it[startsAt] = request.start.toOffset()
            it[endsAt] = request.end.toOffset()
            it[createdAt] = clock.now().toOffset()
        }
        CalendarBlockDto(id.toString(), request.kind, request.title, request.start, request.end)
    }

    suspend fun removeBlock(trainerId: UUID, blockId: UUID) = db.tx {
        val updated = CalendarBlocks.update({
            (CalendarBlocks.id eq blockId) and (CalendarBlocks.trainerId eq trainerId) and CalendarBlocks.deletedAt.isNull()
        }) { it[deletedAt] = clock.now().toOffset() }
        if (updated == 0) notFound("Block not found")
    }

    suspend fun availability(trainerId: UUID): AvailabilityDto = db.tx {
        val zone = AppUsers.selectAll().where { AppUsers.id eq trainerId }.single()[AppUsers.timeZone]
        val today = LocalDate.now(ZoneId.of(zone))
        val rules = AvailabilityRules.selectAll().where { AvailabilityRules.trainerId eq trainerId }
            .filter { it[AvailabilityRules.validTo]?.isBefore(today) != true }
            .sortedWith(compareBy({ it[AvailabilityRules.weekday] }, { it[AvailabilityRules.startTime] }))
            .map {
                AvailabilityRuleDto(
                    weekday = it[AvailabilityRules.weekday].toInt(),
                    startTime = it[AvailabilityRules.startTime].toString(),
                    endTime = it[AvailabilityRules.endTime].toString(),
                    gymId = it[AvailabilityRules.gymId]?.toString(),
                    validFrom = it[AvailabilityRules.validFrom].toString(),
                    validTo = it[AvailabilityRules.validTo]?.toString(),
                )
            }
        AvailabilityDto(rules.firstOrNull()?.let { zone } ?: zone, rules)
    }

    /** Replaces the weekly hours. Existing sessions are not affected; only later requests are checked against them. */
    suspend fun saveAvailability(trainerId: UUID, request: AvailabilityDto): AvailabilityDto {
        val zone = runCatching { ZoneId.of(request.timeZone) }.getOrElse { invalid("Unknown time zone") }
        if (request.rules.size > 50) invalid("Too many rules")
        val parsed = request.rules.map { rule ->
            if (rule.weekday !in 1..7) invalid("Weekday must be 1–7")
            val start = runCatching { LocalTime.parse(rule.startTime) }.getOrElse { invalid("Invalid start time") }
            val end = runCatching { LocalTime.parse(rule.endTime) }.getOrElse { invalid("Invalid end time") }
            if (!start.isBefore(end)) invalid("Start must be before end")
            val from = rule.validFrom?.let { runCatching { LocalDate.parse(it) }.getOrElse { invalid("Invalid date") } } ?: LocalDate.now(zone)
            val to = rule.validTo?.let { runCatching { LocalDate.parse(it) }.getOrElse { invalid("Invalid date") } }
            if (to != null && to.isBefore(from)) invalid("validTo before validFrom")
            Triple(rule, start to end, from to to)
        }
        parsed.groupBy { it.first.weekday }.values.forEach { day ->
            val sorted = day.sortedBy { it.second.first }
            if (sorted.zipWithNext().any { (a, b) -> b.second.first.isBefore(a.second.second) }) invalid("Overlapping hours on one day")
        }
        db.tx {
            TrainerProfiles.selectAll().where { TrainerProfiles.userId eq trainerId }.forUpdate().single()
            parsed.mapNotNull { it.first.gymId }.distinct().forEach { gym ->
                if (Gyms.selectAll().where { Gyms.id eq gym.toUuid("gymId") }.empty()) invalid("Unknown gym")
            }
            AvailabilityRules.deleteWhere { AvailabilityRules.trainerId eq trainerId }
            parsed.forEach { (rule, times, dates) ->
                AvailabilityRules.insert {
                    it[id] = newId()
                    it[AvailabilityRules.trainerId] = trainerId
                    it[weekday] = rule.weekday.toShort()
                    it[startTime] = times.first
                    it[endTime] = times.second
                    it[timeZone] = zone.id
                    it[gymId] = rule.gymId?.toUuid("gymId")
                    it[validFrom] = dates.first
                    it[validTo] = dates.second
                }
            }
            AppUsers.update({ AppUsers.id eq trainerId }) { it[timeZone] = zone.id }
        }
        return availability(trainerId)
    }

}
