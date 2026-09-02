package io.baton.cal.persistence

import io.baton.cal.recovery.RecoveryItemState
import io.baton.cal.recovery.RecoveryManifestDigest
import io.baton.cal.recovery.RecoverySeasonState
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

data class RecoverySeasonManifestRow(
    val recoveryId: UUID,
    val seasonId: UUID,
    val itemCount: Int,
    val itemDigest: String,
    val metadataRevision: Int?,
    val metadataDigest: String?,
    val verifiedAt: Instant,
) {
    fun state() = RecoverySeasonState(
        seasonId = seasonId,
        itemCount = itemCount,
        itemDigest = itemDigest,
        metadataRevision = metadataRevision,
        metadataDigest = metadataDigest,
    )
}

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
    fun lockRecoveryRun(recoveryId: UUID) {
        jdbcClient.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:recoveryId AS TEXT), CAST(0 AS BIGINT)))",
        )
            .param("recoveryId", recoveryId.toString())
            .query { _, _ -> true }
            .single()
    }

    fun currentSeasonState(seasonId: UUID): RecoverySeasonState {
        val items = jdbcClient.sql(
            """
            SELECT item.source_item_id, item.revision, inbox.payload_hash
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
            ORDER BY item.source_item_id
            """.trimIndent(),
        )
            .param("seasonId", seasonId)
            .query { resultSet, _ ->
                RecoveryItemState(
                    sourceItemId = resultSet.getObject("source_item_id", UUID::class.java),
                    revision = resultSet.getInt("revision"),
                    payloadDigest = resultSet.getString("payload_hash"),
                )
            }
            .list()
            .requireNoNulls()
        val metadata = jdbcClient.sql(
            """
            SELECT revision, display_name
            FROM season_calendar_metadata
            WHERE season_id = :seasonId
            """.trimIndent(),
        )
            .param("seasonId", seasonId)
            .query { resultSet, _ ->
                resultSet.getInt("revision") to resultSet.getString("display_name")
            }
            .optional()
            .getOrNull()
        return RecoverySeasonState(
            seasonId = seasonId,
            itemCount = items.size,
            itemDigest = RecoveryManifestDigest.items(items),
            metadataRevision = metadata?.first,
            metadataDigest = metadata?.let { RecoveryManifestDigest.metadata(it.first, it.second) },
        )
    }

    fun upsertSeasonManifest(row: RecoverySeasonManifestRow) {
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
            .param("recoveryId", row.recoveryId)
            .param("seasonId", row.seasonId)
            .param("itemCount", row.itemCount)
            .param("itemDigest", row.itemDigest)
            .param("metadataRevision", row.metadataRevision, java.sql.Types.INTEGER)
            .param("metadataDigest", row.metadataDigest, java.sql.Types.CHAR)
            .param("verifiedAt", row.verifiedAt.atOffset(ZoneOffset.UTC))
            .update()
    }

    fun findSeasonManifest(recoveryId: UUID, seasonId: UUID): RecoverySeasonManifestRow? = jdbcClient.sql(
        """
        SELECT recovery_id, season_id, item_count, item_digest,
               metadata_revision, metadata_digest, verified_at
        FROM recovery_season_manifest
        WHERE recovery_id = :recoveryId AND season_id = :seasonId
        """.trimIndent(),
    )
        .param("recoveryId", recoveryId)
        .param("seasonId", seasonId)
        .query(RecoverySeasonManifestRow::class.java)
        .optional()
        .getOrNull()

    fun listSeasonManifests(recoveryId: UUID): List<RecoverySeasonManifestRow> = jdbcClient.sql(
        """
        SELECT recovery_id, season_id, item_count, item_digest,
               metadata_revision, metadata_digest, verified_at
        FROM recovery_season_manifest
        WHERE recovery_id = :recoveryId
        ORDER BY season_id
        """.trimIndent(),
    )
        .param("recoveryId", recoveryId)
        .query(RecoverySeasonManifestRow::class.java)
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

    fun insertCompletion(row: RecoveryRunCompletionRow): RecoveryRunCompletionRow {
        jdbcClient.sql(
            """
            INSERT INTO recovery_run_completion (recovery_id, season_count, season_digest, completed_at)
            VALUES (:recoveryId, :seasonCount, :seasonDigest, :completedAt)
            ON CONFLICT (recovery_id) DO NOTHING
            """.trimIndent(),
        )
            .param("recoveryId", row.recoveryId)
            .param("seasonCount", row.seasonCount)
            .param("seasonDigest", row.seasonDigest)
            .param("completedAt", row.completedAt.atOffset(ZoneOffset.UTC))
            .update()
        return requireNotNull(findCompletion(row.recoveryId))
    }
}
