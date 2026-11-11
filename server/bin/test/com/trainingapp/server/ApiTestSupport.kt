package com.trainingapp.server

import com.trainingapp.contract.policy.PolicyPreset
import com.trainingapp.contract.policy.PolicyPresets
import com.trainingapp.server.billing.FakePaymentProvider
import com.trainingapp.server.catalogue.Gyms
import com.trainingapp.server.catalogue.Offers
import com.trainingapp.server.catalogue.PolicyTiers
import com.trainingapp.server.catalogue.PolicyVersions
import com.trainingapp.server.catalogue.TrainerGyms
import com.trainingapp.server.catalogue.TrainerListings
import com.trainingapp.server.common.ApiJson
import com.trainingapp.server.common.Ids
import com.trainingapp.server.config.ServerConfig
import com.trainingapp.server.identity.AppUsers
import com.trainingapp.server.identity.ClientProfiles
import com.trainingapp.server.identity.TrainerProfiles
import com.trainingapp.server.scheduling.AvailabilityRules
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime
import kotlin.uuid.Uuid

/** One migrated database and one service graph per test class; the app runs in Ktor's test engine. */
abstract class ApiTest {
    protected val db = TestDatabase.fresh()
    private val storageDir = Files.createTempDirectory("trainer-media").toString()
    protected val config = ServerConfig.fromEnv(
        mapOf("TRAINER_PROFILE" to "local", "MEDIA_STORAGE_DIR" to storageDir, "FAKE_PAYMENT_DELAY_SECONDS" to "0", "WORKER_INLINE" to "false"),
    )
    protected val fakeProvider = FakePaymentProvider(config.payments.fakeWebhookSecret)
    protected val services = Services(db, config, paymentProvider = fakeProvider)
    protected val fixtures = Fixtures(services)

    protected fun api(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { trainerModule(services, startWorker = false) }
        val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
        block(client)
    }

    /** Runs a counting query as the superuser-free app role. */
    protected fun count(sql: String): Long = db.txBlocking {
        var n = 0L
        TransactionManager.current().exec(sql) { rs -> rs.next(); n = rs.getLong(1) }
        n
    }
}

fun HttpRequestBuilder.auth(subject: String) = header(HttpHeaders.Authorization, "Bearer dev.$subject")
fun HttpRequestBuilder.json() = contentType(ContentType.Application.Json)

/**
 * Synthetic fixtures. A trainer who is available all day, every day, so tests can book "in 3 hours" and exercise the
 * late-cancellation tiers deterministically; the policy is a preset with a configurable number of free cancellations.
 */
class Fixtures(private val s: Services) {
    data class Trainer(val id: Uuid, val subject: String, val policyId: Uuid, val offerId: Uuid, val packageOfferId: Uuid, val gymName: String)

    fun client(subject: String, name: String = "Klient Testowy"): Uuid = s.db.txBlocking {
        val id = Ids.v7()
        AppUsers.insert { it[AppUsers.id] = id; it[authSubject] = subject; it[displayName] = name; it[locale] = "pl"; it[timeZone] = "Europe/Warsaw"; it[isOperator] = false }
        ClientProfiles.insert { it[userId] = id }
        id
    }

    fun trainer(
        subject: String,
        name: String = "Trener Testowy",
        priceZloty: Long = 120,
        freeLateCancellations: Int = 0,
        bufferMinutes: Int = 0,
        public: Boolean = true,
        preset: PolicyPreset = PolicyPreset.Standard,
    ): Trainer = s.db.txBlocking {
        val id = Ids.v7()
        AppUsers.insert { it[AppUsers.id] = id; it[authSubject] = subject; it[displayName] = name; it[locale] = "pl"; it[timeZone] = "Europe/Warsaw"; it[isOperator] = false }
        TrainerProfiles.insert { it[userId] = id; it[defaultCurrency] = "PLN"; it[sessionBufferMinutes] = bufferMinutes }
        val gymId = Ids.v7()
        val gymName = "Sala $subject"
        Gyms.insert {
            it[Gyms.id] = gymId; it[Gyms.name] = gymName; it[street] = "ul. Testowa 1"; it[city] = "Warszawa"; it[timeZone] = "Europe/Warsaw"
            it[isPublic] = true; it[district] = "Testowo"; it[searchText] = "sala testowo"
        }
        TrainerListings.insert {
            it[trainerId] = id; it[headline] = "Trener"; it[bio] = "Bio"; it[specialties] = listOf("Siła"); it[isPublic] = public
            it[displayName] = name; it[searchText] = name.lowercase(); it[goals] = listOf("STRENGTH")
        }
        TrainerGyms.insert { it[trainerId] = id; it[TrainerGyms.gymId] = gymId; it[isMain] = true }
        val offerId = Ids.v7()
        Offers.insert {
            it[Offers.id] = offerId; it[trainerId] = id; it[kind] = "SINGLE_SESSION"; it[Offers.name] = "Trening personalny"; it[durationMinutes] = 60
            it[priceMinor] = priceZloty * 100; it[currency] = "PLN"; it[seller] = "TRAINER"; it[purpose] = "PHYSICAL"; it[isPublic] = true
        }
        val packageOfferId = Ids.v7()
        Offers.insert {
            it[Offers.id] = packageOfferId; it[trainerId] = id; it[kind] = "PACKAGE"; it[Offers.name] = "Pakiet 8 sesji"; it[durationMinutes] = 60
            it[priceMinor] = priceZloty * 700; it[currency] = "PLN"; it[sessionsPerPeriod] = 8; it[seller] = "TRAINER"; it[purpose] = "PHYSICAL"; it[isPublic] = true
        }
        val rules = PolicyPresets.rules(preset)
        val policyId = Ids.v7()
        PolicyVersions.insert {
            it[PolicyVersions.id] = policyId; it[trainerId] = id; it[version] = 1; it[noShowPercent] = rules.noShowPercent; it[graceMinutes] = rules.graceMinutes
            it[PolicyVersions.freeLateCancellations] = freeLateCancellations; it[lateRescheduleIsCancel] = true; it[cashFeeMode] = "ADD_TO_NEXT_SESSION"; it[packageFeeMode] = "USE_CREDIT"
        }
        rules.tiers.forEach { tier -> PolicyTiers.insert { it[policyVersionId] = policyId; it[hoursBefore] = tier.hoursBefore; it[feePercent] = tier.feePercent } }
        for (day in 1..7) {
            AvailabilityRules.insert {
                it[AvailabilityRules.id] = Ids.v7(); it[trainerId] = id; it[weekday] = day.toShort()
                it[startTime] = LocalTime.of(0, 0); it[endTime] = LocalTime.of(23, 59); it[timeZone] = "Europe/Warsaw"
                it[validFrom] = LocalDate.now().minusDays(30)
            }
        }
        Trainer(id, subject, policyId, offerId, packageOfferId, gymName)
    }
}
