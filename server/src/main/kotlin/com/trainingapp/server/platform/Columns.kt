package com.trainingapp.server.platform

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.postgresql.util.PGobject
import java.time.OffsetDateTime

/** timestamptz column. Values are read and written as UTC [OffsetDateTime]; convert with [toInstantK]/[toOffset]. */
fun Table.tstz(name: String): Column<OffsetDateTime> = timestampWithTimeZone(name)

/** `citext` (case-insensitive e-mail) read and written as text. */
class CitextColumnType : ColumnType<String>() {
    override fun sqlType() = "CITEXT"
    override fun valueFromDB(value: Any): String = value.toString()
    override fun notNullValueToDB(value: String): Any = PGobject().apply { type = "citext"; this.value = value }
}

fun Table.citext(name: String): Column<String> = registerColumn(name, CitextColumnType())

/** `jsonb` stored and read as a JSON string; callers (de)serialize with kotlinx.serialization. */
class JsonbColumnType : ColumnType<String>() {
    override fun sqlType() = "JSONB"
    override fun valueFromDB(value: Any): String = if (value is PGobject) value.value ?: "null" else value.toString()
    override fun notNullValueToDB(value: String): Any = PGobject().apply { type = "jsonb"; this.value = value }
}

fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())
