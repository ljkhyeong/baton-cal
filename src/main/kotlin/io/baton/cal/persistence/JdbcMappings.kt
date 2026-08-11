package io.baton.cal.persistence

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime

internal fun ResultSet.requiredInstant(column: String): Instant =
    getObject(column, OffsetDateTime::class.java).toInstant()

internal fun ResultSet.nullableInstant(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()
