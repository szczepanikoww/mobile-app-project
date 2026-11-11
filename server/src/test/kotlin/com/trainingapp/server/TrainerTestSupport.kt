package com.trainingapp.server

import com.trainingapp.contract.api.DevTokenRequest
import com.trainingapp.contract.api.TokenDto
import com.trainingapp.server.platform.ApiJson
import com.trainingapp.server.platform.AppConfig
import com.trainingapp.server.platform.AuthConfig
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.SCHEMAS
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.toMicros
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.Base64
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Synthetic seed ids (db/dev/R__demo_seed.sql). */
object Seed {
    val TRAINER: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val OTHER_TRAINER: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val CLIENT_1: UUID = UUID.fromString("22222222-2222-2222-2222-222222222221")
    val REL_1 = "44444444-4444-4444-4444-444444444441"
    val REL_2 = "44444444-4444-4444-4444-444444444442"
    val REL_3 = "44444444-4444-4444-4444-444444444443"
    val CONFIRMED_SESSION = "99999999-9999-9999-9999-999999999991"
    val REQUESTED_SESSION = "99999999-9999-9999-9999-999999999992"
    val COMPLETED_SESSION = "99999999-9999-9999-9999-999999999993"
    val POLICY = "55555555-5555-5555-5555-555555555551"
}

/** A clock the test moves by hand. Starts at the real time so the seed's now()-relative sessions line up. */
class TestClock(var current: Instant = Clock.System.now().toMicros()) : ServerClock {
    override fun now() = current
    fun advance(by: Duration) { current += by }
}

/** One PostgreSQL 16 container for the test run; each test gets freshly migrated and seeded schemas. */
object TrainerTestDatabase {
    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:16-alpine").also { it.start() }
    }

    val dataSource: HikariDataSource by lazy {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            maximumPoolSize = 12
            isAutoCommit = false
        })
    }

    fun fresh(): Db {
        dataSource.connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st ->
                SCHEMAS.forEach { st.execute("DROP SCHEMA IF EXISTS $it CASCADE") }
                st.execute("DROP TABLE IF EXISTS public.flyway_schema_history")
            }
        }
        Db.migrate(dataSource, devSeed = true)
        return Db(dataSource)
    }

    /** Runs raw SQL for test setup (e.g. a client's reschedule proposal, which the client backend creates). */
    fun exec(sql: String) {
        dataSource.connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { it.execute(sql) }
        }
    }

    fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): T =
        dataSource.connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> read(rs) } }
        }

    fun count(sql: String): Long = query(sql) { rs -> rs.next(); rs.getLong(1) }
}

fun testConfig() = AppConfig(
    port = 0,
    dbUrl = "",
    dbUser = "",
    dbPassword = "",
    devSeed = true,
    auth = AuthConfig("dev", "test-secret-0123456789-0123456789", "trainer-app-dev", "trainer-app"),
    dataKeyId = "test-1",
    dataKey = Base64.getDecoder().decode("ZGV2LW9ubHkta2V5LWRvLW5vdC11c2UtaW4tcHJvZCE="),
    mediaDir = "build/test-media",
    publicBaseUrl = "http://localhost",
    runWorker = false,
)

class TrainerApiTest(val services: TrainerServices, val clock: TestClock, private val builder: ApplicationTestBuilder) {
    fun client(): HttpClient = builder.createClient {
        install(ContentNegotiation) { json(ApiJson) }
        defaultRequest { contentType(ContentType.Application.Json) }
    }

    /** A client signed in as the seeded demo account [subject]. */
    suspend fun signedIn(subject: String): HttpClient {
        val token = client().post("/api/v1/dev/token") { setBody(DevTokenRequest(subject)) }.body<TokenDto>().token
        return builder.createClient {
            install(ContentNegotiation) { json(ApiJson) }
            defaultRequest {
                contentType(ContentType.Application.Json)
                bearerAuth(token)
            }
        }
    }
}

fun apiTest(block: suspend TrainerApiTest.() -> Unit) {
    val db = TrainerTestDatabase.fresh()
    // The seed's confirmed session sits at "tomorrow 08:00"; free it so time-relative tests never collide with it.
    TrainerTestDatabase.exec("UPDATE scheduling.session SET status = 'CANCELLED_BY_TRAINER', closed_at = now() WHERE id = '${Seed.CONFIRMED_SESSION}'")
    val clock = TestClock()
    val services = TrainerServices(testConfig(), db, clock)
    testApplication {
        application { trainerApiModule(services) }
        TrainerApiTest(services, clock, this).block()
    }
}
