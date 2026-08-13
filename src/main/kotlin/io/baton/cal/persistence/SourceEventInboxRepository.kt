package io.baton.cal.persistence

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
            ON CONFLICT (event_id) DO NOTHING
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

    fun findPayloadHashByEventId(eventId: UUID): String? =
        jdbcClient.sql(
            """
            SELECT payload_hash
            FROM source_event_inbox
            WHERE event_id = :eventId
            """.trimIndent(),
        )
            .param("eventId", eventId)
            .query(String::class.java)
            .optional()
            .orElse(null)

    fun findPayloadHashBySourceItemIdAndRevision(
        sourceItemId: UUID,
        sourceRevision: Int,
    ): String? =
        jdbcClient.sql(
            """
            SELECT payload_hash
            FROM source_event_inbox
            WHERE source_item_id = :sourceItemId
              AND source_revision = :sourceRevision
            LIMIT 1
            """.trimIndent(),
        )
            .param("sourceItemId", sourceItemId)
            .param("sourceRevision", sourceRevision)
            .query(String::class.java)
            .optional()
            .orElse(null)
}
