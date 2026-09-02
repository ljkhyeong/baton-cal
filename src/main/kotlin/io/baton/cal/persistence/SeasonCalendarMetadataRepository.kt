package io.baton.cal.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

data class SeasonCalendarMetadataRow(
    val seasonId: UUID,
    val revision: Int,
    val displayName: String,
    val acceptedAt: Instant,
)

@Repository
class SeasonCalendarMetadataRepository(private val jdbcClient: JdbcClient) {
    fun findBySeasonId(seasonId: UUID): SeasonCalendarMetadataRow? = jdbcClient.sql(
        "SELECT season_id, revision, display_name, accepted_at FROM season_calendar_metadata WHERE season_id = :seasonId",
    )
        .param("seasonId", seasonId)
        .query(SeasonCalendarMetadataRow::class.java)
        .optional()
        .getOrNull()

    // 호출자는 같은 시즌의 투영 잠금 안에서 개정 번호를 판정한 뒤 저장한다.
    @Transactional(propagation = Propagation.MANDATORY)
    fun upsert(row: SeasonCalendarMetadataRow) {
        jdbcClient.sql(
            """
            INSERT INTO season_calendar_metadata (season_id, revision, display_name, accepted_at)
            VALUES (:seasonId, :revision, :displayName, :acceptedAt)
            ON CONFLICT (season_id) DO UPDATE SET
                revision = EXCLUDED.revision,
                display_name = EXCLUDED.display_name,
                accepted_at = EXCLUDED.accepted_at
            """.trimIndent(),
        )
            .param("seasonId", row.seasonId)
            .param("revision", row.revision)
            .param("displayName", row.displayName)
            .param("acceptedAt", row.acceptedAt.atOffset(ZoneOffset.UTC))
            .update()
    }
}
