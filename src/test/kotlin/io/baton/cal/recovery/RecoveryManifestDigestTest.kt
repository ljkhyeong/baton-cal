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
    // 기대값은 PRD-0002 복구 다이제스트 규칙으로 따로 계산한 고정값이다. `f0000000`은 부호 있는 UUID 비교에서는
    // 앞에 오고 문자열 사전순에서는 뒤에 오므로 BATON과 어긋나는 정렬을 잡아낸다.
    @Test
    fun `항목과 시즌 다이제스트는 조회 순서와 관계없이 문자열 순서의 고정값을 만든다`() {
        val firstId = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val secondId = UUID.fromString("f0000000-0000-0000-0000-000000000002")
        val items = listOf(
            RecoveryItemState(firstId, 1, "a".repeat(64)),
            RecoveryItemState(secondId, 2, "b".repeat(64)),
        )
        val itemDigest = "4d783d05370672f27d698fcc6ffddeb3bd82d51c8280616116f7312e95a8f4e5"
        assertThat(RecoveryManifestDigest.items(items)).isEqualTo(itemDigest)
        assertThat(RecoveryManifestDigest.items(items.reversed())).isEqualTo(itemDigest)

        val seasons = listOf(
            RecoverySeasonState(firstId, 2, itemDigest, null, null),
            RecoverySeasonState(secondId, 0, RecoveryManifestDigest.items(emptyList()), 1, "c".repeat(64)),
        )
        val seasonDigest = "d44ed0f55620beb8e8f8bc1ac8acc982aa8892c05c00072347b9c7c22762419b"
        assertThat(RecoveryManifestDigest.seasons(seasons)).isEqualTo(seasonDigest)
        assertThat(RecoveryManifestDigest.seasons(seasons.reversed())).isEqualTo(seasonDigest)
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
