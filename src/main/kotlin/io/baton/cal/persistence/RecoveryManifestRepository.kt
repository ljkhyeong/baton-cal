package io.baton.cal.persistence

import io.baton.cal.recovery.RecoveryItemState
import io.baton.cal.recovery.RecoverySeasonState
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

data class RecoveryRunCompletionRow(
    val recoveryId: UUID,
    val seasonCount: Int,
    val seasonDigest: String,
    val completedAt: Instant,
)

@Repository
class RecoveryManifestRepository(
    private val jdbcClient: JdbcClient,
) {
    /** 시즌의 현재 항목마다 채택한 개정 번호를 처음 수신한 스냅샷 지문과 함께 조회한다. */
    fun listItemStates(seasonId: UUID): List<RecoveryItemState> = jdbcClient.sql(
        """
        SELECT item.source_item_id, item.revision, inbox.payload_hash AS payload_digest
        FROM calendar_item item
        JOIN LATERAL (
            SELECT payload_hash
            FROM source_event_inbox
            WHERE source_item_id = item.source_item_id
              AND source_revision = item.revision
            ORDER BY received_at, event_id
            LIMIT 1
        ) inbox ON TRUE
        WHERE item.season_id = :seasonId
        """.trimIndent(),
    )
        .param("seasonId", seasonId)
        .query(RecoveryItemState::class.java)
        .list()
        .requireNoNulls()

    fun upsertSeasonManifest(recoveryId: UUID, state: RecoverySeasonState, verifiedAt: Instant) {
        jdbcClient.sql(
            """
            INSERT INTO recovery_season_manifest (
                recovery_id, season_id, item_count, item_digest,
                metadata_revision, metadata_digest, verified_at
            ) VALUES (
                :recoveryId, :seasonId, :itemCount, :itemDigest,
                :metadataRevision, :metadataDigest, :verifiedAt
            )
            ON CONFLICT (recovery_id, season_id) DO UPDATE SET
                item_count = EXCLUDED.item_count,
                item_digest = EXCLUDED.item_digest,
                metadata_revision = EXCLUDED.metadata_revision,
                metadata_digest = EXCLUDED.metadata_digest,
                verified_at = EXCLUDED.verified_at
            """.trimIndent(),
        )
            .param("recoveryId", recoveryId)
            .param("seasonId", state.seasonId)
            .param("itemCount", state.itemCount)
            .param("itemDigest", state.itemDigest)
            .param("metadataRevision", state.metadataRevision, Types.INTEGER)
            .param("metadataDigest", state.metadataDigest, Types.CHAR)
            .param("verifiedAt", verifiedAt.atOffset(ZoneOffset.UTC))
            .update()
    }

    /** 완료 후 재시도는 시즌마다 들어오므로 기본 키로 한 시즌의 검증값만 읽는다. */
    fun findVerifiedSeasonState(recoveryId: UUID, seasonId: UUID): RecoverySeasonState? = jdbcClient.sql(
        """
        SELECT season_id, item_count, item_digest, metadata_revision, metadata_digest
        FROM recovery_season_manifest
        WHERE recovery_id = :recoveryId AND season_id = :seasonId
        """.trimIndent(),
    )
        .param("recoveryId", recoveryId)
        .param("seasonId", seasonId)
        .query(RecoverySeasonState::class.java)
        .optional()
        .getOrNull()

    fun listVerifiedSeasonStates(recoveryId: UUID): List<RecoverySeasonState> = jdbcClient.sql(
        """
        SELECT season_id, item_count, item_digest, metadata_revision, metadata_digest
        FROM recovery_season_manifest
        WHERE recovery_id = :recoveryId
        """.trimIndent(),
    )
        .param("recoveryId", recoveryId)
        .query(RecoverySeasonState::class.java)
        .list()
        .requireNoNulls()

    fun currentDataSeasonIds(): Set<UUID> = jdbcClient.sql(
        """
        SELECT season_id FROM calendar_item
        UNION
        SELECT season_id FROM season_calendar_metadata
        """.trimIndent(),
    )
        .query(UUID::class.java)
        .list()
        .requireNoNulls()
        .toSet()

    fun lockRecoveryState() {
        jdbcClient.sql(
            """
            LOCK TABLE calendar_item, season_calendar_metadata,
                       recovery_season_manifest IN SHARE MODE
            """.trimIndent(),
        ).update()
    }

    fun findCompletion(recoveryId: UUID): RecoveryRunCompletionRow? = jdbcClient.sql(
        """
        SELECT recovery_id, season_count, season_digest, completed_at
        FROM recovery_run_completion
        WHERE recovery_id = :recoveryId
        """.trimIndent(),
    )
        .param("recoveryId", recoveryId)
        .query(RecoveryRunCompletionRow::class.java)
        .optional()
        .getOrNull()

    fun insertCompletion(row: RecoveryRunCompletionRow) {
        jdbcClient.sql(
            """
            INSERT INTO recovery_run_completion (recovery_id, season_count, season_digest, completed_at)
            VALUES (:recoveryId, :seasonCount, :seasonDigest, :completedAt)
            """.trimIndent(),
        )
            .param("recoveryId", row.recoveryId)
            .param("seasonCount", row.seasonCount)
            .param("seasonDigest", row.seasonDigest)
            .param("completedAt", row.completedAt.atOffset(ZoneOffset.UTC))
            .update()
    }
}
