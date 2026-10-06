package io.baton.cal.persistence

import io.baton.cal.snapshot.ScheduleSnapshot
import kotlin.jvm.optionals.getOrNull
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class SourceEventInboxRepository(
    private val jdbcClient: JdbcClient,
) {
    /** 스냅샷의 수신 기록을 남긴다. 같은 `eventId`가 이미 있으면 저장하지 않고 false다. */
    fun insert(snapshot: ScheduleSnapshot, payloadHash: String, receivedAt: Instant): Boolean =
        jdbcClient.sql(
            """
            INSERT INTO source_event_inbox (event_id, payload_hash, source_item_id, source_revision, received_at)
            VALUES (:eventId, :payloadHash, :sourceItemId, :sourceRevision, :receivedAt)
            ON CONFLICT (event_id) DO NOTHING
            """.trimIndent(),
        )
            .param("eventId", snapshot.eventId)
            .param("payloadHash", payloadHash)
            .param("sourceItemId", snapshot.sourceItemId)
            .param("sourceRevision", snapshot.revision)
            .param("receivedAt", receivedAt.atOffset(ZoneOffset.UTC))
            .update() == 1

    fun getPayloadHashByEventId(eventId: UUID): String =
        jdbcClient.sql(
            """
            SELECT payload_hash
            FROM source_event_inbox
            WHERE event_id = :eventId
            """.trimIndent(),
        )
            .param("eventId", eventId)
            .query(String::class.java)
            .single()

    fun findPayloadHashBySourceItemIdAndRevision(
        sourceItemId: UUID,
        sourceRevision: Int,
        excludingEventId: UUID,
    ): String? =
        jdbcClient.sql(
            """
            SELECT payload_hash
            FROM source_event_inbox
            WHERE source_item_id = :sourceItemId
              AND source_revision = :sourceRevision
              AND event_id <> :excludingEventId
            LIMIT 1
            """.trimIndent(),
        )
            .param("sourceItemId", sourceItemId)
            .param("sourceRevision", sourceRevision)
            .param("excludingEventId", excludingEventId)
            .query(String::class.java)
            .optional()
            .getOrNull()
}
