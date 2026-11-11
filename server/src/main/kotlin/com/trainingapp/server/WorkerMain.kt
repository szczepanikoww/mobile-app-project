package com.trainingapp.server

import com.trainingapp.server.config.ServerConfig
import com.trainingapp.server.db.Db
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.TimeZone

/**
 * The worker process (architecture §5): the same code base as the API, started without HTTP. Run it next to an API
 * started with `WORKER_INLINE=false`. Several workers may run at once: jobs and outbox rows are claimed with
 * `FOR UPDATE SKIP LOCKED`.
 */
fun main() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    val config = ServerConfig.fromEnv()
    val services = Services(Db(Db.pool(config.db, maxPoolSize = 4), config.db.logSql), config)
    LoggerFactory.getLogger("trainer").info("worker started (profile {})", config.profile)
    runBlocking { coroutineScope { services.worker.start(this) } }
}
