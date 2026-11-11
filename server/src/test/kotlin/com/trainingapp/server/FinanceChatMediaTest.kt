package com.trainingapp.server

import com.trainingapp.contract.api.ApiError
import com.trainingapp.contract.api.ClientBalanceDto
import com.trainingapp.contract.api.ClientSummaryDto
import com.trainingapp.contract.api.ConversationDto
import com.trainingapp.contract.api.FinancePeriod
import com.trainingapp.contract.api.FinanceSummaryDto
import com.trainingapp.contract.api.MarkReadRequest
import com.trainingapp.contract.api.MessageDto
import com.trainingapp.contract.api.PaymentDto
import com.trainingapp.contract.api.PaymentMethod
import com.trainingapp.contract.api.PaymentStatus
import com.trainingapp.contract.api.RecordCashPaymentRequest
import com.trainingapp.contract.api.SendMessageRequest
import com.trainingapp.contract.api.ThreadDto
import com.trainingapp.contract.api.TrainerHomeDto
import com.trainingapp.contract.api.UploadSessionDto
import com.trainingapp.contract.media.CreateUploadSessionRequest
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.contract.media.MediaStatusDto
import com.trainingapp.contract.media.RemoteMediaState
import com.trainingapp.contract.money.Money
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinanceChatMediaTest {

    private val base = "/api/v1/trainer"

    private fun uuid() = UUID.randomUUID().toString()

    private fun String.withLastCharChanged() = dropLast(1) + (if (last() == 'A') 'B' else 'A')

    // ---------------------------------------------------------------- finance

    @Test
    fun `cash payment settles the overdue charge`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val before = trainer.get("$base/clients/${Seed.REL_1}/balance").body<ClientBalanceDto>()
        assertEquals(Money(15000, "PLN"), before.outstanding)
        assertTrue(trainer.get("$base/clients").body<List<ClientSummaryDto>>().single { it.id == Seed.REL_1 }.hasOverduePayment)

        val operationId = uuid()
        val request = RecordCashPaymentRequest(Seed.REL_1, Money.pln(150), operationId)
        val response = trainer.post("$base/payments/cash") { setBody(request) }
        assertEquals(HttpStatusCode.Created, response.status)
        val payment = response.body<PaymentDto>()
        assertEquals(PaymentMethod.CASH, payment.method)
        assertEquals(PaymentStatus.SUCCEEDED, payment.status)

        val after = trainer.get("$base/clients/${Seed.REL_1}/balance").body<ClientBalanceDto>()
        assertEquals(0L, after.outstanding.minor)
        assertEquals(1, after.payments.size)
        assertFalse(trainer.get("$base/clients").body<List<ClientSummaryDto>>().single { it.id == Seed.REL_1 }.hasOverduePayment)

        // A retry with the same operation id returns the same payment and records nothing new.
        val retry = trainer.post("$base/payments/cash") { setBody(request) }.body<PaymentDto>()
        assertEquals(payment.id, retry.id)
        assertEquals(1L, TrainerTestDatabase.count("SELECT count(*) FROM billing.payment WHERE operation_id = '$operationId'"))
        assertEquals(1L, TrainerTestDatabase.count("SELECT count(*) FROM billing.allocation WHERE payment_id = '${payment.id}'"))

        val summary = trainer.get("$base/finance?period=MONTH").body<FinanceSummaryDto>()
        assertEquals(FinancePeriod.MONTH, summary.period)
        assertEquals(15000L, summary.collected.minor)
        assertEquals(0L, summary.outstanding.minor)
        assertEquals(0, summary.overdueClients)
        assertTrue(summary.payments.any { it.id == payment.id })
    }

    @Test
    fun `invalid and foreign cash payments are rejected`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val zero = trainer.post("$base/payments/cash") { setBody(RecordCashPaymentRequest(Seed.REL_1, Money(0, "PLN"), uuid())) }
        assertEquals(HttpStatusCode.UnprocessableEntity, zero.status)
        val euro = trainer.post("$base/payments/cash") { setBody(RecordCashPaymentRequest(Seed.REL_1, Money(100, "EUR"), uuid())) }
        assertEquals(HttpStatusCode.UnprocessableEntity, euro.status)
        val foreign = signedIn("demo-other-trainer").post("$base/payments/cash") { setBody(RecordCashPaymentRequest(Seed.REL_1, Money.pln(10), uuid())) }
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertEquals(0L, TrainerTestDatabase.count("SELECT count(*) FROM billing.payment"))
        assertEquals(HttpStatusCode.UnprocessableEntity, trainer.get("$base/finance?period=DECADE").status)
    }

    // ---------------------------------------------------------------- home

    @Test
    fun `home shows open requests and the overdue client`() = apiTest {
        val home = signedIn("demo-trainer").get("$base/home")
        assertEquals(HttpStatusCode.OK, home.status)
        val dto = home.body<TrainerHomeDto>()
        assertTrue(dto.openRequests >= 1)
        assertEquals(1, dto.overdueClients)
        assertEquals(3, dto.clients.size)
    }

    // ---------------------------------------------------------------- chat

    @Test
    fun `sending a message twice stores it once and sequence numbers grow`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val path = "$base/conversations/${Seed.REL_1}/messages"
        val firstId = uuid()
        val first = trainer.post(path) { setBody(SendMessageRequest(firstId, "Cześć, do zobaczenia jutro")) }
        assertEquals(HttpStatusCode.Created, first.status)
        val firstDto = first.body<MessageDto>()
        assertEquals(1L, firstDto.seq)
        assertTrue(firstDto.mine)

        val resend = trainer.post(path) { setBody(SendMessageRequest(firstId, "Cześć, do zobaczenia jutro")) }.body<MessageDto>()
        assertEquals(1L, resend.seq)
        assertEquals(1L, TrainerTestDatabase.count("SELECT count(*) FROM messaging.message WHERE id = '$firstId'"))

        val second = trainer.post(path) { setBody(SendMessageRequest(uuid(), "Weź ręcznik")) }.body<MessageDto>()
        assertEquals(2L, second.seq)

        val thread = trainer.get(path).body<ThreadDto>()
        assertEquals(listOf(1L, 2L), thread.messages.map { it.seq })
        assertEquals(listOf(2L), trainer.get("$path?afterSeq=1").body<ThreadDto>().messages.map { it.seq })
        assertEquals(0L, thread.lastReadSeq)

        assertEquals(HttpStatusCode.UnprocessableEntity, trainer.post(path) { setBody(SendMessageRequest(uuid(), "   ")) }.status)
    }

    @Test
    fun `read marker moves forward only and is bounded by the last message`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val path = "$base/conversations/${Seed.REL_1}"
        repeat(2) { trainer.post("$path/messages") { setBody(SendMessageRequest(uuid(), "Wiadomość $it")) } }

        assertEquals(HttpStatusCode.NoContent, trainer.post("$path/read") { setBody(MarkReadRequest(2)) }.status)
        assertEquals(2L, trainer.get("$path/messages").body<ThreadDto>().lastReadSeq)
        assertEquals(HttpStatusCode.NoContent, trainer.post("$path/read") { setBody(MarkReadRequest(1)) }.status)
        assertEquals(2L, trainer.get("$path/messages").body<ThreadDto>().lastReadSeq)
        assertEquals(HttpStatusCode.UnprocessableEntity, trainer.post("$path/read") { setBody(MarkReadRequest(9)) }.status)

        val conversation = trainer.get("$base/conversations").body<List<ConversationDto>>().single { it.clientId == Seed.REL_1 }
        assertEquals(2L, conversation.lastMessage?.seq)
        assertEquals(0, conversation.unread)
    }

    @Test
    fun `another trainer cannot read or write the conversation`() = apiTest {
        val trainer = signedIn("demo-trainer")
        trainer.post("$base/conversations/${Seed.REL_1}/messages") { setBody(SendMessageRequest(uuid(), "Prywatne")) }

        val other = signedIn("demo-other-trainer")
        assertEquals(HttpStatusCode.NotFound, other.get("$base/conversations/${Seed.REL_1}/messages").status)
        assertEquals(HttpStatusCode.NotFound, other.post("$base/conversations/${Seed.REL_1}/messages") { setBody(SendMessageRequest(uuid(), "x")) }.status)
        assertEquals(HttpStatusCode.NotFound, other.post("$base/conversations/${Seed.REL_1}/read") { setBody(MarkReadRequest(1)) }.status)
        assertTrue(other.get("$base/conversations").body<List<ConversationDto>>().isEmpty())
    }

    // ---------------------------------------------------------------- media

    @Test
    fun `demo video upload becomes ready and plays only with a valid signed link`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val mediaId = uuid()
        val bytes = "demo-video-bytes".encodeToByteArray()
        val session = trainer.post("$base/media/upload-sessions") {
            setBody(CreateUploadSessionRequest(mediaId, MediaKind.EXERCISE_DEMO, bytes.size.toLong(), "video/mp4"))
        }
        assertEquals(HttpStatusCode.Created, session.status)
        val uploadPath = session.body<UploadSessionDto>().uploadUrl.removePrefix("http://localhost")
        assertTrue(uploadPath.startsWith("/media/$mediaId/content?token="), uploadPath)

        val pending = trainer.get("$base/media/$mediaId").body<MediaStatusDto>()
        assertEquals(RemoteMediaState.UPLOADING, pending.state)
        assertNull(pending.playbackUrl)

        // A forged or missing token is refused.
        val http = client()
        assertEquals(HttpStatusCode.Forbidden, http.put(uploadPath.withLastCharChanged()) { contentType(ContentType.Video.MP4); setBody(bytes) }.status)
        assertEquals(HttpStatusCode.Forbidden, http.put("/media/$mediaId/content") { contentType(ContentType.Video.MP4); setBody(bytes) }.status)

        val uploaded = http.put(uploadPath) { contentType(ContentType.Video.MP4); setBody(bytes) }
        assertEquals(HttpStatusCode.NoContent, uploaded.status)

        val ready = trainer.get("$base/media/$mediaId").body<MediaStatusDto>()
        assertEquals(RemoteMediaState.READY, ready.state)
        val playbackPath = assertNotNull(ready.playbackUrl).removePrefix("http://localhost")

        val played = http.get(playbackPath)
        assertEquals(HttpStatusCode.OK, played.status)
        assertContentEquals(bytes, played.body<ByteArray>())

        assertEquals(HttpStatusCode.Forbidden, http.get(playbackPath.withLastCharChanged()).status)
        assertEquals(HttpStatusCode.Forbidden, http.get("/media/$mediaId/content").status)
        // The upload token must not work for playback.
        assertEquals(HttpStatusCode.Forbidden, http.get(uploadPath).status)

        // A second upload to the same session is refused.
        assertEquals(HttpStatusCode.Conflict, http.put(uploadPath) { contentType(ContentType.Video.MP4); setBody(bytes) }.status)
    }

    @Test
    fun `a media file is not playable before it is uploaded`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val mediaId = uuid()
        trainer.post("$base/media/upload-sessions") {
            setBody(CreateUploadSessionRequest(mediaId, MediaKind.EXERCISE_DEMO, 100, "video/mp4"))
        }
        assertEquals(RemoteMediaState.UPLOADING, trainer.get("$base/media/$mediaId").body<MediaStatusDto>().state)
        // Another trainer does not even learn the media exists.
        assertEquals(HttpStatusCode.NotFound, signedIn("demo-other-trainer").get("$base/media/$mediaId").status)
    }

    @Test
    fun `trainers cannot start a technique video upload`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val mediaId = uuid()
        val response = trainer.post("$base/media/upload-sessions") {
            setBody(CreateUploadSessionRequest(mediaId, MediaKind.TECHNIQUE_VIDEO, 100, "video/mp4"))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("DENIED", response.body<ApiError>().code)
        assertEquals(0L, TrainerTestDatabase.count("SELECT count(*) FROM media.media_asset WHERE id = '$mediaId'"))
        val badType = trainer.post("$base/media/upload-sessions") {
            setBody(CreateUploadSessionRequest(uuid(), MediaKind.EXERCISE_DEMO, 100, "text/plain"))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, badType.status)
    }
}
