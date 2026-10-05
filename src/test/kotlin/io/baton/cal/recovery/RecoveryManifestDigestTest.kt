package io.baton.cal.recovery

import io.baton.cal.contract.contractExample
import io.baton.cal.snapshot.SnapshotFingerprint
import io.baton.cal.web.ScheduleSnapshotRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

class RecoveryManifestDigestTest {
    @Test
    fun `항목과 시즌 다이제스트는 조회 순서와 관계없이 같다`() {
        val firstId = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val secondId = UUID.fromString("f0000000-0000-0000-0000-000000000002")
        val items = listOf(
            RecoveryItemState(firstId, 1, "a".repeat(64)),
            RecoveryItemState(secondId, 2, "b".repeat(64)),
        )
        assertThat(RecoveryManifestDigest.items(items.reversed())).isEqualTo(RecoveryManifestDigest.items(items))

        val seasons = listOf(
            RecoverySeasonState(firstId, 2, RecoveryManifestDigest.items(items), null, null),
            RecoverySeasonState(secondId, 0, RecoveryManifestDigest.items(emptyList()), 1, "c".repeat(64)),
        )
        assertThat(RecoveryManifestDigest.seasons(seasons.reversed()))
            .isEqualTo(RecoveryManifestDigest.seasons(seasons))
    }

    @Test
    fun `계약 예시의 수신 지문으로 BATON과 공유하는 복구 다이제스트를 만든다`() {
        // BATON은 같은 예시 값으로 직렬화기를 검증하므로 인코딩이 바뀌면 DB 없이 여기서 실패해야 한다.
        val snapshot = JSON
            .readValue(contractExample("schedule-snapshot.zoned-cancelled.json"), ScheduleSnapshotRequest::class.java)
            .toDomain()
        val metadata = exampleNode("season-calendar-metadata.r2.json")
        val expected = exampleNode("recovery-season-state.zoned-cancelled.json")

        val item = RecoveryItemState(snapshot.sourceItemId, snapshot.revision, SnapshotFingerprint.sha256(snapshot))
        val state = RecoveryManifestDigest.seasonState(
            snapshot.seasonId,
            listOf(item),
            metadata["revision"].asInt() to metadata["displayName"].asString(),
        )

        assertThat(JSON.valueToTree<JsonNode>(state)).isEqualTo(expected)
        assertThat(RecoveryManifestDigest.seasons(listOf(state)))
            .isEqualTo(exampleNode("recovery-run-completion.zoned-cancelled.json")["seasonDigest"].asString())
    }

    private fun exampleNode(name: String): JsonNode = JSON.readTree(contractExample(name))

    private companion object {
        val JSON = jacksonObjectMapper()
    }
}
