package com.trainingapp.server.auth

import com.trainingapp.server.db.AppUsers
import com.trainingapp.server.db.ClientProfiles
import com.trainingapp.server.db.TrainerProfiles
import com.trainingapp.server.platform.ApiException
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.ErrorCode
import com.trainingapp.server.platform.denied
import io.ktor.server.application.ApplicationCall
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

/** The signed-in user as our database knows them. Roles come from profile rows, never from the token. */
data class Caller(
    val userId: UUID,
    val displayName: String,
    val email: String?,
    val locale: String,
    val timeZone: String,
    val isTrainer: Boolean,
    val isClient: Boolean,
)

class CallerResolver(private val db: Db) {
    suspend fun resolve(subject: String): Caller = db.tx {
        val user = AppUsers.selectAll()
            .where { (AppUsers.authSubject eq subject) }
            .singleOrNull()
            ?.takeIf { it[AppUsers.deletedAt] == null }
            ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Unknown account")
        val id = user[AppUsers.id]
        Caller(
            userId = id,
            displayName = user[AppUsers.displayName],
            email = user[AppUsers.email],
            locale = user[AppUsers.locale],
            timeZone = user[AppUsers.timeZone],
            isTrainer = TrainerProfiles.selectAll().where { TrainerProfiles.userId eq id }.any(),
            isClient = ClientProfiles.selectAll().where { ClientProfiles.userId eq id }.any(),
        )
    }
}

suspend fun ApplicationCall.caller(resolver: CallerResolver): Caller = resolver.resolve(subject())

/** The trainer id (= user id) of a caller holding the trainer role; DENIED otherwise. */
suspend fun ApplicationCall.trainer(resolver: CallerResolver): Caller =
    caller(resolver).also { if (!it.isTrainer) denied("Trainer role required") }
