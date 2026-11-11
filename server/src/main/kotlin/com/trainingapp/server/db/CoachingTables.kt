package com.trainingapp.server.db

import com.trainingapp.server.platform.tstz
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date

/** Append-only consent history; a withdrawal is a new row. */
object ConsentRecords : Table("coaching.consent_record") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val purpose = text("purpose")
    val documentVersion = text("document_version")
    val action = text("action")
    val recordedAt = tstz("recorded_at")
    override val primaryKey = PrimaryKey(id)
}

/** Health questionnaire, one row per submitted version; answers are field-encrypted. */
object HealthQuestionnaires : Table("coaching.health_questionnaire") {
    val id = uuid("id")
    val clientId = uuid("client_id")
    val version = integer("version")
    val formVersion = text("form_version")
    val answersEncrypted = binary("answers_encrypted")
    val keyId = text("key_id")
    val submittedAt = tstz("submitted_at")
    override val primaryKey = PrimaryKey(id)
}

/** Goals set within a trainer-client relationship. */
object ClientGoals : Table("coaching.client_goal") {
    val id = uuid("id")
    val relationshipId = uuid("relationship_id")
    val text = text("text")
    val targetDate = date("target_date").nullable()
    val achievedAt = tstz("achieved_at").nullable()
    val createdAt = tstz("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Private trainer notes, visible to the authoring trainer only. */
object CoachingNotes : Table("coaching.coaching_note") {
    val id = uuid("id")
    val relationshipId = uuid("relationship_id")
    val authorId = uuid("author_id")
    val body = text("body")
    val createdAt = tstz("created_at")
    val updatedAt = tstz("updated_at")
    val deletedAt = tstz("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
