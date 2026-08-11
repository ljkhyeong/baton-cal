package io.baton.cal.persistence

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleTimeType
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class CalendarItemRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * Inserts a new item or replaces it only when the source revision advances.
     * The caller is expected to hold the affected season projection lock in the
     * surrounding transaction.
     */
    fun applyIfNewer(candidate: CalendarItemRow): CalendarItemApplyResult {
        val applied = upsert(candidate)
        if (applied != null) {
            return CalendarItemApplyResult(CalendarItemApplyOutcome.APPLIED, applied)
        }

        val current = checkNotNull(findBySourceItemId(candidate.sourceItemId)) {
            "calendar item disappeared while classifying conditional apply"
        }
        val outcome = when {
            candidate.seasonId != current.seasonId -> CalendarItemApplyOutcome.CONFLICT
            candidate.revision < current.revision -> CalendarItemApplyOutcome.STALE
            candidate.revision == current.revision && candidate.payloadHash == current.payloadHash ->
                CalendarItemApplyOutcome.DUPLICATE
            candidate.revision == current.revision -> CalendarItemApplyOutcome.CONFLICT
            !candidate.sourceUpdatedAt.isAfter(current.sourceUpdatedAt) -> CalendarItemApplyOutcome.CONFLICT
            else -> error("newer calendar item revision was not applied")
        }
        return CalendarItemApplyResult(outcome, current)
    }

    fun findBySourceItemId(sourceItemId: UUID): CalendarItemRow? =
        jdbcClient.sql(
            """
            SELECT $COLUMNS
            FROM calendar_item
            WHERE source_item_id = :sourceItemId
            """.trimIndent(),
        )
            .param("sourceItemId", sourceItemId)
            .query(::mapRow)
            .optional()
            .orElse(null)

    fun listBySeasonId(seasonId: UUID): List<CalendarItemRow> =
        jdbcClient.sql(
            """
            SELECT $COLUMNS
            FROM calendar_item
            WHERE season_id = :seasonId
            ORDER BY source_item_id
            """.trimIndent(),
        )
            .param("seasonId", seasonId)
            .query(::mapRow)
            .list()

    private fun upsert(candidate: CalendarItemRow): CalendarItemRow? =
        bindCandidate(
            jdbcClient.sql(
                """
                INSERT INTO calendar_item (
                    source_item_id,
                    season_id,
                    revision,
                    payload_hash,
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
                    :payloadHash,
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
                    season_id = EXCLUDED.season_id,
                    revision = EXCLUDED.revision,
                    payload_hash = EXCLUDED.payload_hash,
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
                RETURNING $COLUMNS
                """.trimIndent(),
            ),
            candidate,
        )
            .query(::mapRow)
            .optional()
            .orElse(null)

    private fun bindCandidate(
        statement: JdbcClient.StatementSpec,
        row: CalendarItemRow,
    ): JdbcClient.StatementSpec =
        statement
            .param("sourceItemId", row.sourceItemId)
            .param("seasonId", row.seasonId)
            .param("revision", row.revision)
            .param("payloadHash", row.payloadHash)
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

    private fun mapRow(resultSet: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int) =
        CalendarItemRow(
            sourceItemId = resultSet.getObject("source_item_id", UUID::class.java),
            seasonId = resultSet.getObject("season_id", UUID::class.java),
            revision = resultSet.getInt("revision"),
            payloadHash = resultSet.getString("payload_hash"),
            status = CalendarItemStatus.valueOf(resultSet.getString("status")),
            summary = resultSet.getString("summary"),
            description = resultSet.getString("description"),
            location = resultSet.getString("location"),
            timeType = ScheduleTimeType.valueOf(resultSet.getString("time_type")),
            startsAtInstant = resultSet.nullableInstant("starts_at_instant"),
            endsAtInstant = resultSet.nullableInstant("ends_at_instant"),
            startsAtLocal = resultSet.getObject("starts_at_local", java.time.LocalDateTime::class.java),
            endsAtLocal = resultSet.getObject("ends_at_local", java.time.LocalDateTime::class.java),
            zoneId = resultSet.getString("zone_id"),
            sourceUpdatedAt = resultSet.requiredInstant("source_updated_at"),
            acceptedAt = resultSet.requiredInstant("accepted_at"),
        )

    private companion object {
        const val COLUMNS = """
            source_item_id,
            season_id,
            revision,
            payload_hash,
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
    }
}
