package io.baton.cal.persistence

import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class SourceEventInboxRepository(
    private val jdbcClient: JdbcClient,
) {
    fun insert(row: SourceEventInboxRow): Boolean =
        jdbcClient.sql(
            """
            INSERT INTO source_event_inbox (
                event_id,
                payload_hash,
                source_item_id,
                season_id,
                source_revision,
                occurred_at,
                received_at
            ) VALUES (
                :eventId,
                :payloadHash,
                :sourceItemId,
                :seasonId,
                :sourceRevision,
                :occurredAt,
                :receivedAt
            )
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        )
            .param("eventId", row.eventId)
            .param("payloadHash", row.payloadHash)
            .param("sourceItemId", row.sourceItemId)
            .param("seasonId", row.seasonId)
            .param("sourceRevision", row.sourceRevision)
            .param("occurredAt", OffsetDateTime.ofInstant(row.occurredAt, ZoneOffset.UTC))
            .param("receivedAt", OffsetDateTime.ofInstant(row.receivedAt, ZoneOffset.UTC))
            .update() == 1

    fun findByEventId(eventId: UUID): SourceEventInboxRow? =
        jdbcClient.sql(
            """
            SELECT
                event_id,
                payload_hash,
                source_item_id,
                season_id,
                source_revision,
                occurred_at,
                received_at
            FROM source_event_inbox
            WHERE event_id = :eventId
            """.trimIndent(),
        )
            .param("eventId", eventId)
            .query(::mapRow)
            .optional()
            .orElse(null)

    fun findBySourceItemIdAndRevision(
        sourceItemId: UUID,
        sourceRevision: Int,
    ): SourceEventInboxRow? =
        jdbcClient.sql(
            """
            SELECT
                event_id,
                payload_hash,
                source_item_id,
                season_id,
                source_revision,
                occurred_at,
                received_at
            FROM source_event_inbox
            WHERE source_item_id = :sourceItemId
              AND source_revision = :sourceRevision
            ORDER BY received_at, event_id
            LIMIT 1
            """.trimIndent(),
        )
            .param("sourceItemId", sourceItemId)
            .param("sourceRevision", sourceRevision)
            .query(::mapRow)
            .optional()
            .orElse(null)

    private fun mapRow(resultSet: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int) =
        SourceEventInboxRow(
            eventId = resultSet.getObject("event_id", UUID::class.java),
            payloadHash = resultSet.getString("payload_hash"),
            sourceItemId = resultSet.getObject("source_item_id", UUID::class.java),
            seasonId = resultSet.getObject("season_id", UUID::class.java),
            sourceRevision = resultSet.getInt("source_revision"),
            occurredAt = resultSet.requiredInstant("occurred_at"),
            receivedAt = resultSet.requiredInstant("received_at"),
        )
}
