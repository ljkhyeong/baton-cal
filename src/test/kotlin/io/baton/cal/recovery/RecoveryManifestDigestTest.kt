package io.baton.cal.recovery

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
        assertThat(RecoveryManifestDigest.seasons(seasons.reversed())).isEqualTo(RecoveryManifestDigest.seasons(seasons))
    }
}
