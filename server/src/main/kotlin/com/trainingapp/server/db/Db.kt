package com.trainingapp.server.db

import com.trainingapp.server.common.ApiException
import com.trainingapp.server.common.ErrorCode
import com.trainingapp.server.config.DbConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.StdOutSqlLogger
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * The application's database access: one Hikari pool for the DML-only role and transaction helpers that implement
 * the rules of docs/plans/backend-client-guest.md §3 (one transaction per write, retry on serialization failures,
 * constraint violations mapped by SQLSTATE).
 */
class Db(val dataSource: DataSource, private val logSql: Boolean = false) {
    val database: Database = Database.connect(
        dataSource,
        databaseConfig = DatabaseConfig {
            // Exposed would otherwise retry every SQLException, including constraint violations that are business
            // results. Serialization failures are retried below instead.
            defaultMaxAttempts = 1
            defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
        },
    )

    /** Runs [block] in one transaction on the IO dispatcher. Retries serialization failures and deadlocks 3 times. */
    suspend fun <T> tx(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { txBlocking(block) }

    fun <T> txBlocking(block: JdbcTransaction.() -> T): T {
        var attempt = 0
        while (true) {
            try {
                return transaction(database) {
                    if (logSql) addLogger(StdOutSqlLogger)
                    block()
                }
            } catch (e: Throwable) {
                val state = e.sqlState()
                if ((state == SqlState.SERIALIZATION_FAILURE || state == SqlState.DEADLOCK) && attempt < 3) {
                    attempt++
                    Thread.sleep(10L * attempt)
                    continue
                }
                if (state == SqlState.SERIALIZATION_FAILURE || state == SqlState.DEADLOCK) {
                    throw ApiException(409, ErrorCode.CONFLICT, "concurrent update, retries exhausted")
                }
                throw e
            }
        }
    }

    companion object {
        fun pool(config: DbConfig, maxPoolSize: Int = 10): HikariDataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.appUser
                password = config.appPassword
                maximumPoolSize = maxPoolSize
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
                poolName = "trainer-app"
            },
        )

        /**
         * Flyway owns the schema (decision DB1/DB2). V1 is docs/database/schema-draft.sql unchanged; it creates the
         * module schemas itself, so Flyway must not create them first and keeps its history table in `public`.
         * The DML role gets its grants afterwards from the `R__grants.sql` repeatable migration.
         */
        fun migrate(url: String, ownerUser: String, ownerPassword: String, appUser: String) {
            Flyway.configure()
                .dataSource(url, ownerUser, ownerPassword)
                .locations("classpath:db/migration")
                .createSchemas(false)
                .placeholders(mapOf("appRole" to appUser))
                .load()
                .migrate()
        }
    }
}

/** SQLSTATE codes the server treats as business results (plan §3 rule 2). */
object SqlState {
    const val UNIQUE_VIOLATION = "23505"
    const val FOREIGN_KEY_VIOLATION = "23503"
    const val CHECK_VIOLATION = "23514"
    const val EXCLUSION_VIOLATION = "23P01"
    const val RAISE_EXCEPTION = "P0001"
    const val SERIALIZATION_FAILURE = "40001"
    const val DEADLOCK = "40P01"
}

fun Throwable.sqlState(): String? {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is ExposedSQLException -> current.sqlState?.let { return it }
            is SQLException -> current.sqlState?.let { return it }
        }
        current = current.cause
    }
    return null
}

/** The name of the violated constraint or index, when PostgreSQL reports one. */
fun Throwable.constraintName(): String? {
    var current: Throwable? = this
    while (current != null) {
        if (current is org.postgresql.util.PSQLException) return current.serverErrorMessage?.constraint
        current = current.cause
    }
    return null
}

/**
 * Runs [block] inside a savepoint. When it fails with an SQL error the transaction rolls back to the savepoint and
 * stays usable, so the caller can re-read (e.g. after a unique violation on an idempotency key).
 */
fun <T> JdbcTransaction.savepoint(name: String = "sp", block: () -> T): T {
    val sp = connection.setSavepoint(name)
    try {
        val result = block()
        connection.releaseSavepoint(sp)
        return result
    } catch (e: Throwable) {
        if (e.sqlState() != null) connection.rollback(sp)
        throw e
    }
}

/** Like [savepoint], but returns null instead of throwing for the given SQLSTATE. */
fun <T> JdbcTransaction.attempt(sqlState: String, block: () -> T): T? = try {
    savepoint("attempt", block)
} catch (e: Throwable) {
    if (e.sqlState() == sqlState) null else throw e
}

/** Retries a suspended database call during start-up while PostgreSQL is still starting (docker-compose). */
suspend fun <T> waitForDatabase(attempts: Int = 30, block: () -> T): T {
    repeat(attempts - 1) {
        try {
            return block()
        } catch (e: Exception) {
            delay(1000)
        }
    }
    return block()
}
