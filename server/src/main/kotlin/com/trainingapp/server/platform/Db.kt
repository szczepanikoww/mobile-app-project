package com.trainingapp.server.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection
import javax.sql.DataSource

/** All schemas, `platform` first so Flyway keeps its history table there (database-design.md). */
val SCHEMAS = arrayOf("platform", "identity", "catalogue", "coaching", "scheduling", "training", "messaging", "media", "billing")

class Db(val dataSource: DataSource) {
    val database: Database = Database.connect(dataSource)

    /** Runs [block] in one READ COMMITTED transaction off the request thread. No external calls inside (architecture §7). */
    suspend fun <T> tx(block: Transaction.() -> T): T = withContext(Dispatchers.IO) {
        transaction(Connection.TRANSACTION_READ_COMMITTED, db = database) {
            maxAttempts = 1 // a conflict is reported to the caller, never silently retried
            block()
        }
    }

    companion object {
        fun connect(config: AppConfig): Db {
            val ds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.dbUser
                password = config.dbPassword
                maximumPoolSize = 10
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
            })
            migrate(ds, config.devSeed)
            return Db(ds)
        }

        fun migrate(ds: DataSource, devSeed: Boolean) {
            val locations = buildList {
                add("classpath:db/migration")
                if (devSeed) add("classpath:db/dev")
            }
            // V1 creates the module schemas itself, so Flyway keeps its history table in `public` (it must exist
            // before V1 runs). database-design.md proposed `platform`; that would require editing V1.
            Flyway.configure()
                .dataSource(ds)
                .defaultSchema("public")
                .createSchemas(false)
                .locations(*locations.toTypedArray())
                .load()
                .migrate()
        }
    }
}
