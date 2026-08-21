package io.baton.cal.persistence

import java.sql.Types
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
class CalendarItemRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * 새 항목을 추가하거나 원본 개정 번호가 증가했을 때만 기존 항목을 교체한다.
     * 호출자는 외부 트랜잭션에서 해당 시즌의 투영 잠금을 보유해야 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun applyIfNewer(candidate: CalendarItemRow): CalendarItemApplyOutcome {
        if (upsert(candidate)) return CalendarItemApplyOutcome.APPLIED

        val current = findRevisionState(candidate.sourceItemId)
        return when {
            candidate.seasonId != current.seasonId -> CalendarItemApplyOutcome.SCOPE_CONFLICT
            candidate.revision < current.revision -> CalendarItemApplyOutcome.STALE
            else -> CalendarItemApplyOutcome.REVISION_CONFLICT
        }
    }

    fun listBySeasonId(seasonId: UUID): List<CalendarItemRow> =
        jdbcClient.sql(
            """
            SELECT $COLUMNS
            FROM calendar_item
            WHERE season_id = :seasonId
            """.trimIndent(),
        )
            .param("seasonId", seasonId)
            .query(CalendarItemRow::class.java)
            .list()
            .requireNoNulls()

    private fun upsert(candidate: CalendarItemRow): Boolean =
        bindCandidate(
            jdbcClient.sql(
                """
                INSERT INTO calendar_item (
                    source_item_id,
                    season_id,
                    revision,
                    status,
                    summary,
                    description,
                    location,
                    time_type,
                    starts_at_instant,
                    ends_at_instant,
                    starts_at_local,
                    ends_at_local,
                    zone_id,
                    source_updated_at,
                    accepted_at
                ) VALUES (
                    :sourceItemId,
                    :seasonId,
                    :revision,
                    :status,
                    :summary,
                    :description,
                    :location,
                    :timeType,
                    :startsAtInstant,
                    :endsAtInstant,
                    :startsAtLocal,
                    :endsAtLocal,
                    :zoneId,
                    :sourceUpdatedAt,
                    :acceptedAt
                )
                ON CONFLICT (source_item_id) DO UPDATE SET
                    revision = EXCLUDED.revision,
                    status = EXCLUDED.status,
                    summary = EXCLUDED.summary,
                    description = EXCLUDED.description,
                    location = EXCLUDED.location,
                    time_type = EXCLUDED.time_type,
                    starts_at_instant = EXCLUDED.starts_at_instant,
                    ends_at_instant = EXCLUDED.ends_at_instant,
                    starts_at_local = EXCLUDED.starts_at_local,
                    ends_at_local = EXCLUDED.ends_at_local,
                    zone_id = EXCLUDED.zone_id,
                    source_updated_at = EXCLUDED.source_updated_at,
                    accepted_at = EXCLUDED.accepted_at
                WHERE calendar_item.season_id = EXCLUDED.season_id
                  AND calendar_item.revision < EXCLUDED.revision
                  AND calendar_item.source_updated_at < EXCLUDED.source_updated_at
                """.trimIndent(),
            ),
            candidate,
        )
            .update() == 1

    private fun findRevisionState(sourceItemId: UUID): RevisionState =
        jdbcClient.sql(
            """
            SELECT season_id, revision
            FROM calendar_item
            WHERE source_item_id = :sourceItemId
            """.trimIndent(),
        )
            .param("sourceItemId", sourceItemId)
            .query(RevisionState::class.java)
            .single()

    private fun bindCandidate(
        statement: JdbcClient.StatementSpec,
        row: CalendarItemRow,
    ): JdbcClient.StatementSpec =
        statement
            .param("sourceItemId", row.sourceItemId)
            .param("seasonId", row.seasonId)
            .param("revision", row.revision)
            .param("status", row.status.name)
            .param("summary", row.summary)
            .param("description", row.description, Types.VARCHAR)
            .param("location", row.location, Types.VARCHAR)
            .param("timeType", row.timeType.name)
            .param(
                "startsAtInstant",
                row.startsAtInstant?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
                Types.TIMESTAMP_WITH_TIMEZONE,
            )
            .param(
                "endsAtInstant",
                row.endsAtInstant?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
                Types.TIMESTAMP_WITH_TIMEZONE,
            )
            .param("startsAtLocal", row.startsAtLocal, Types.TIMESTAMP)
            .param("endsAtLocal", row.endsAtLocal, Types.TIMESTAMP)
            .param("zoneId", row.zoneId, Types.VARCHAR)
            .param("sourceUpdatedAt", OffsetDateTime.ofInstant(row.sourceUpdatedAt, ZoneOffset.UTC))
            .param("acceptedAt", OffsetDateTime.ofInstant(row.acceptedAt, ZoneOffset.UTC))

    private companion object {
        const val COLUMNS = """
            source_item_id,
            season_id,
            revision,
            status,
            summary,
            description,
            location,
            time_type,
            starts_at_instant,
            ends_at_instant,
            starts_at_local,
            ends_at_local,
            zone_id,
            source_updated_at,
            accepted_at
        """

        data class RevisionState(
            val seasonId: UUID,
            val revision: Int,
        )
    }
}
