package com.trainingapp.server.http

import com.trainingapp.contract.account.CreateAccountRequest
import com.trainingapp.contract.account.PushTokenRequest
import com.trainingapp.contract.account.UpdateMeRequest
import com.trainingapp.contract.catalog.AvailabilityBucketDto
import com.trainingapp.contract.catalog.GoalDto
import com.trainingapp.contract.catalog.PriceRangeDto
import com.trainingapp.contract.catalog.TrainerSortDto
import com.trainingapp.contract.chat.ReadRequest
import com.trainingapp.contract.chat.SendMessageRequest
import com.trainingapp.contract.media.CreateUploadSessionRequest
import com.trainingapp.contract.schedule.BookingRequestDto
import com.trainingapp.contract.schedule.CancelRequestDto
import com.trainingapp.contract.schedule.ProposalAnswerDto
import com.trainingapp.contract.schedule.RescheduleRequestDto
import com.trainingapp.contract.schedule.RevisionDto
import com.trainingapp.contract.sync.MeasurementUpsertDto
import com.trainingapp.contract.sync.WorkoutSyncRequest
import com.trainingapp.contract.wallet.PayRequestDto
import com.trainingapp.server.Services
import com.trainingapp.server.catalogue.TrainerQuery
import com.trainingapp.server.common.ApiException
import com.trainingapp.server.common.ApiJson
import com.trainingapp.server.common.ErrorCode
import com.trainingapp.server.common.invalid
import com.trainingapp.server.common.notFound
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.acceptLanguage
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.time.Instant

val PublicRateLimit = RateLimitName("public")

private fun ApplicationCall.locale(): String =
    if (request.acceptLanguage()?.lowercase()?.startsWith("en") == true) "en" else "pl"

private fun ApplicationCall.instantParam(name: String): Instant? =
    request.queryParameters[name]?.let { runCatching { Instant.parse(it) }.getOrNull() ?: throw invalid(name) }

private inline fun <reified E : Enum<E>> ApplicationCall.enumSet(name: String): Set<E> =
    request.queryParameters.getAll(name).orEmpty().flatMap { it.split(',') }.filter { it.isNotBlank() }
        .map { v -> enumValues<E>().firstOrNull { it.name.equals(v, ignoreCase = true) } ?: throw invalid(name) }.toSet()

/**
 * Public, cacheable JSON: `Cache-Control: public, max-age=60` and an ETag over the body; a matching
 * `If-None-Match` gets `304` (plan §5 caching).
 */
private suspend fun <T> ApplicationCall.respondPublic(serializer: KSerializer<T>, value: T) {
    val body = ApiJson.encodeToString(serializer, value)
    val etag = "\"" + MessageDigest.getInstance("SHA-256").digest(body.encodeToByteArray()).take(12).joinToString("") { "%02x".format(it) } + "\""
    response.header(HttpHeaders.CacheControl, "public, max-age=60")
    response.header(HttpHeaders.ETag, etag)
    if (request.headers[HttpHeaders.IfNoneMatch] == etag) {
        respond(HttpStatusCode.NotModified)
        return
    }
    respondBytes(body.encodeToByteArray(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
}

fun Route.publicRoutes(s: Services) = rateLimit(PublicRateLimit) {
    route("/v1/public") {
        get("/home") { call.respondPublic(com.trainingapp.contract.catalog.GuestHomeDto.serializer(), s.catalogue.home(call.locale())) }
        get("/trainers") {
            val q = call.request.queryParameters
            val query = TrainerQuery(
                text = q["text"].orEmpty().take(100),
                goals = call.enumSet<GoalDto>("goal"),
                gymId = q["gymId"]?.let { runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull() ?: throw invalid("gymId") },
                priceRange = q["price"]?.let { v -> PriceRangeDto.entries.firstOrNull { it.name.equals(v, true) } ?: throw invalid("price") },
                districts = q.getAll("district").orEmpty().flatMap { it.split(',') }.filter { it.isNotBlank() }.toSet(),
                availability = call.enumSet<AvailabilityBucketDto>("availability"),
                sort = q["sort"]?.let { v -> TrainerSortDto.entries.firstOrNull { it.name.equals(v, true) } ?: throw invalid("sort") }
                    ?: TrainerSortDto.PRICE_ASCENDING,
            )
            call.respondPublic(kotlinx.serialization.builtins.ListSerializer(com.trainingapp.contract.catalog.TrainerSummaryDto.serializer()), s.catalogue.trainers(query))
        }
        get("/trainers/filters") { call.respondPublic(com.trainingapp.contract.catalog.FilterOptionsDto.serializer(), s.catalogue.filterOptions()) }
        get("/trainers/{id}") {
            val profile = s.catalogue.trainer(call.pathUuid("id", "trainer"), call.locale()) ?: throw notFound("trainer")
            call.respondPublic(com.trainingapp.contract.catalog.TrainerProfileDto.serializer(), profile)
        }
        get("/gyms") {
            val q = call.request.queryParameters
            val districts = q.getAll("district").orEmpty().flatMap { it.split(',') }.filter { it.isNotBlank() }.toSet()
            call.respondPublic(
                kotlinx.serialization.builtins.ListSerializer(com.trainingapp.contract.catalog.GymSummaryDto.serializer()),
                s.catalogue.gyms(q["text"].orEmpty().take(100), districts, call.locale()),
            )
        }
        get("/gyms/{id}") {
            val gym = s.catalogue.gym(call.pathUuid("id", "gym"), call.locale()) ?: throw notFound("gym")
            call.respondPublic(com.trainingapp.contract.catalog.GymDetailDto.serializer(), gym)
        }
    }
}

fun Route.accountRoutes(s: Services) = authenticate(AUTH_API) {
    get("/v1/me") { call.respond(call.currentUser(s.identity).toMe()) }
    post("/v1/accounts") {
        val body = call.receive<CreateAccountRequest>()
        call.respond(HttpStatusCode.Created, s.identity.createAccount(call.token(), body).toMe())
    }
    patch("/v1/me") { call.respond(s.identity.updateMe(call.currentUser(s.identity), call.receive<UpdateMeRequest>()).toMe()) }
    post("/v1/me/roles/client") { call.respond(s.identity.addClientRole(call.currentUser(s.identity)).toMe()) }
    put("/v1/me/push-tokens/{token}") {
        s.identity.putPushToken(call.currentUser(s.identity), call.parameters["token"].orEmpty(), call.receive<PushTokenRequest>().platform)
        call.respond(HttpStatusCode.NoContent)
    }
    delete("/v1/me/push-tokens/{token}") {
        s.identity.deletePushToken(call.currentUser(s.identity), call.parameters["token"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }
    get("/v1/policies/{versionId}") {
        val policy = s.scheduling.policy(call.currentUser(s.identity), call.pathUuid("versionId", "policy"))
        call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
        call.respond(policy)
    }
    get("/v1/exercises") {
        val ids = call.request.queryParameters["ids"]?.split(',')?.filter { it.isNotBlank() }?.map {
            runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull() ?: throw invalid("ids")
        }
        if ((ids?.size ?: 0) > 500) throw invalid("ids")
        call.respond(s.training.exercises(call.currentUser(s.identity), ids, call.instantParam("updatedSince")))
    }
}

fun Route.clientRoutes(s: Services) = authenticate(AUTH_API) {
    route("/v1/client") {
        // Scheduling (B1, B2)
        get("/schedule") { call.respond(s.scheduling.schedule(call.currentClient(s.identity), call.instantParam("from"), call.instantParam("to"))) }
        get("/trainers/{trainerId}/booking-options") {
            call.respond(s.scheduling.bookingOptions(call.currentClient(s.identity), call.pathUuid("trainerId", "trainer")))
        }
        get("/trainers/{trainerId}/slots") {
            val date = call.request.queryParameters["date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: throw invalid("date")
            val exclude = call.request.queryParameters["excludeSessionId"]?.let { runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull() ?: throw invalid("excludeSessionId") }
            call.respond(s.scheduling.availableSlots(call.currentClient(s.identity), call.pathUuid("trainerId", "trainer"), date, exclude))
        }
        post("/booking-requests") {
            val body = call.receive<BookingRequestDto>()
            call.respond(HttpStatusCode.Created, s.scheduling.requestBooking(call.currentClient(s.identity), body, call.operationId(body.operationId)))
        }
        post("/sessions/{id}/withdraw") {
            call.respond(s.scheduling.withdraw(call.currentClient(s.identity), call.pathUuid("id", "session"), call.receive<RevisionDto>().revision))
        }
        get("/sessions/{id}/reschedule-fee") {
            val revision = call.request.queryParameters["revision"]?.toIntOrNull() ?: throw invalid("revision")
            val preview = s.scheduling.previewRescheduleFee(call.currentClient(s.identity), call.pathUuid("id", "session"), revision)
            if (preview == null) call.respond(HttpStatusCode.NoContent) else call.respond(preview)
        }
        post("/sessions/{id}/reschedule-requests") {
            call.respond(HttpStatusCode.Created, s.scheduling.requestReschedule(call.currentClient(s.identity), call.pathUuid("id", "session"), call.receive<RescheduleRequestDto>()))
        }
        post("/sessions/{id}/proposal/answer") {
            call.respond(s.scheduling.answerProposal(call.currentClient(s.identity), call.pathUuid("id", "session"), call.receive<ProposalAnswerDto>()))
        }
        post("/sessions/{id}/cancellation-quotes") {
            call.respond(HttpStatusCode.Created, s.scheduling.quoteCancellation(call.currentClient(s.identity), call.pathUuid("id", "session"), call.receive<RevisionDto>().revision))
        }
        post("/sessions/{id}/cancel") {
            call.respond(s.scheduling.cancel(call.currentClient(s.identity), call.pathUuid("id", "session"), call.receive<CancelRequestDto>()))
        }

        // Wallet (B3)
        get("/wallet") { call.respond(s.billing.wallet(call.currentClient(s.identity))) }
        post("/charges/{id}/payments") {
            val body = call.receive<PayRequestDto>()
            call.respond(HttpStatusCode.Created, s.billing.pay(call.currentClient(s.identity), call.pathUuid("id", "charge"), body.method, call.operationId()))
        }

        // Today, plans, workout sync, measurements (B1, A1–A3)
        get("/today") {
            val today = s.training.today(call.currentClient(s.identity))
            if (today == null) call.respond(HttpStatusCode.NoContent) else call.respond(today)
        }
        get("/assigned-plans") { call.respond(s.training.assignedPlans(call.currentClient(s.identity))) }
        post("/workout-sync") { call.respond(s.workoutSync.sync(call.currentClient(s.identity), call.receive<WorkoutSyncRequest>())) }
        get("/workouts") { call.respond(s.training.changes(call.currentClient(s.identity), call.instantParam("since"))) }
        get("/measurements") { call.respond(s.training.measurements(call.currentClient(s.identity), call.instantParam("since"))) }
        put("/measurements/{id}") {
            call.respond(s.training.putMeasurement(call.currentClient(s.identity), call.pathUuid("id", "measurement"), call.receive<MeasurementUpsertDto>()))
        }
        delete("/measurements/{id}") {
            s.training.deleteMeasurement(call.currentClient(s.identity), call.pathUuid("id", "measurement"))
            call.respond(HttpStatusCode.NoContent)
        }

        // Chat (A6)
        get("/conversations") { call.respond(s.messaging.conversations(call.currentClient(s.identity))) }
    }
}

/** Shared by both parties: access is checked per relationship. */
fun Route.conversationRoutes(s: Services) = authenticate(AUTH_API) {
    route("/v1/conversations/{relationshipId}") {
        get("/messages") {
            val after = call.request.queryParameters["afterSeq"]?.toLongOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
            call.respond(s.messaging.messages(call.currentUser(s.identity), call.pathUuid("relationshipId", "conversation"), after, limit))
        }
        post("/messages") {
            call.respond(HttpStatusCode.Created, s.messaging.send(call.currentUser(s.identity), call.pathUuid("relationshipId", "conversation"), call.receive<SendMessageRequest>()))
        }
        post("/read") {
            s.messaging.markRead(call.currentUser(s.identity), call.pathUuid("relationshipId", "conversation"), call.receive<ReadRequest>().upToSeq)
            call.respond(HttpStatusCode.NoContent)
        }
    }
    webSocket("/v1/ws") {
        val user = call.currentUser(s.identity)
        val job = launch {
            s.realtime.subscribe(user.id).collect { event ->
                send(Frame.Text(ApiJson.encodeToString(com.trainingapp.contract.chat.LiveEventDto.serializer(), event)))
            }
        }
        try {
            for (frame in incoming) Unit // the socket is server → client only
        } finally {
            job.cancel()
        }
    }
}

/** Media upload (tus-style) and status. Playback links are signed and need no token. */
fun Route.mediaRoutes(s: Services) {
    authenticate(AUTH_API) {
        post("/v1/media/upload-sessions") {
            call.respond(HttpStatusCode.Created, s.media.createUploadSession(call.currentUser(s.identity), call.receive<CreateUploadSessionRequest>()))
        }
        head("/v1/media/uploads/{id}") {
            val (offset, length) = s.media.uploadOffset(call.currentUser(s.identity), call.pathUuid("id", "media"))
            call.response.header("Tus-Resumable", "1.0.0")
            call.response.header("Upload-Offset", offset.toString())
            call.response.header("Upload-Length", length.toString())
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK)
        }
        patch("/v1/media/uploads/{id}") {
            val offset = call.request.headers["Upload-Offset"]?.toLongOrNull() ?: throw invalid("Upload-Offset")
            val newOffset = s.media.appendChunk(call.currentUser(s.identity), call.pathUuid("id", "media"), offset, call.receiveChannel().toInputStream())
            call.response.header("Tus-Resumable", "1.0.0")
            call.response.header("Upload-Offset", newOffset.toString())
            call.respond(HttpStatusCode.NoContent)
        }
        get("/v1/media/{id}") { call.respond(s.media.status(call.currentUser(s.identity), call.pathUuid("id", "media"))) }
    }
    get("/v1/media/{id}/content") {
        val expires = call.request.queryParameters["exp"]?.toLongOrNull() ?: throw notFound("media")
        val signature = call.request.queryParameters["sig"] ?: throw notFound("media")
        val (file, type) = s.media.playbackFile(call.pathUuid("id", "media"), expires, signature) ?: throw notFound("media")
        call.response.header(HttpHeaders.CacheControl, "private, max-age=300")
        call.response.header(HttpHeaders.ContentType, type)
        call.respondFile(file)
    }
}

/** Provider webhooks: signature checked over the raw body, each event processed once. */
fun Route.webhookRoutes(s: Services) {
    post("/v1/webhooks/{provider}") {
        val providerName = call.parameters["provider"]
        if (providerName != s.paymentProvider.name) throw notFound("provider")
        val body = call.receiveText()
        if (!s.paymentProvider.verifySignature(body, call.request.headers["X-Signature"])) {
            throw ApiException(401, ErrorCode.UNAUTHENTICATED, "bad signature")
        }
        val json = ApiJson.parseToJsonElement(body) as? JsonObject ?: throw invalid("body")
        val eventId = json["id"]?.jsonPrimitive?.content ?: throw invalid("id")
        val type = json["type"]?.jsonPrimitive?.content ?: throw invalid("type")
        val ref = json["paymentRef"]?.jsonPrimitive?.content ?: throw invalid("paymentRef")
        s.billing.handleProviderEvent(providerName, eventId, type, ref, json)
        call.respond(HttpStatusCode.OK, mapOf("received" to true))
    }
}
