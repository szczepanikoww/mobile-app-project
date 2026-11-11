package com.trainingapp.server

import com.trainingapp.server.db.Db
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.DriverManager

/**
 * One real PostgreSQL per test JVM, migrated with the same Flyway migrations as production (plan §13: real Postgres,
 * not mocks, not H2). Uses `TEST_DATABASE_URL` (+ `TEST_DATABASE_USER`/`TEST_DATABASE_PASSWORD`, a superuser on an
 * empty database) when set, e.g. a postgres:16-alpine container in CI; otherwise starts an embedded PostgreSQL.
 *
 * Each test class gets a fresh database cloned from a migrated template, so tests never see each other's rows.
 */
object TestDatabase {
    private const val TEMPLATE = "trainer_template"
    const val APP_ROLE = "trainer_app_test"
    private const val APP_PASSWORD = "app"

    private val admin: Admin by lazy { startAdmin().also { prepareTemplate(it) } }
    private var counter = 0

    private class Admin(val baseUrl: String, val user: String, val password: String) {
        fun url(db: String) = baseUrl.replaceAfterLast('/', db)
        fun exec(sql: String, db: String = "postgres") {
            DriverManager.getConnection(url(db), user, password).use { c -> c.createStatement().use { it.execute(sql) } }
        }
    }

    private fun startAdmin(): Admin {
        System.getenv("TEST_DATABASE_URL")?.let { url ->
            return Admin(url, System.getenv("TEST_DATABASE_USER") ?: "postgres", System.getenv("TEST_DATABASE_PASSWORD") ?: "postgres")
        }
        val pg = EmbeddedPostgres.builder().start()
        Runtime.getRuntime().addShutdownHook(Thread { pg.close() })
        return Admin(pg.getJdbcUrl("postgres", "postgres"), "postgres", "postgres")
    }

    private fun prepareTemplate(admin: Admin) {
        admin.exec("DROP DATABASE IF EXISTS $TEMPLATE")
        admin.exec("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$APP_ROLE') THEN CREATE ROLE $APP_ROLE LOGIN PASSWORD '$APP_PASSWORD'; END IF; END $$")
        admin.exec("CREATE DATABASE $TEMPLATE")
        Db.migrate(admin.url(TEMPLATE), admin.user, admin.password, APP_ROLE)
    }

    /** A new, migrated database; the returned [Db] connects as the DML-only role, like the API. */
    @Synchronized
    fun fresh(): Db {
        val name = "trainer_test_${++counter}_${System.nanoTime() % 100000}"
        admin.exec("CREATE DATABASE $name TEMPLATE $TEMPLATE")
        val ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = admin.url(name)
                username = APP_ROLE
                password = APP_PASSWORD
                maximumPoolSize = 8
                isAutoCommit = false
            },
        )
        return Db(ds)
    }

    /** A superuser connection to the same database, for assertions that bypass the API role. */
    fun superuserUrl(db: Db): Triple<String, String, String> {
        val url = (db.dataSource as HikariDataSource).jdbcUrl
        return Triple(url, admin.user, admin.password)
    }
}
