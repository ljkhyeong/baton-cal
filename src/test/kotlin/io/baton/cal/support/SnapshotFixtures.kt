package io.baton.cal.support

import io.baton.cal.contract.contractExample
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

private val JSON = JsonMapper()
private val UTC_SNAPSHOT = contractExample("schedule-snapshot.utc-active.json")

/**
 * 계약 예시 UTC 일정으로 번호별 원본 항목의 스냅샷을 만든다. `eventId`는 `eventGroup`과 번호로 정하고,
 * 원본 수정 시각은 개정 번호만큼 1초씩 늦춘다.
 */
fun numberedSnapshot(
    index: Int,
    revision: Int,
    seasonId: UUID,
    status: String,
    eventGroup: Long = revision + 1L,
): ObjectNode = (JSON.readTree(UTC_SNAPSHOT) as ObjectNode).apply {
    put("eventId", UUID(eventGroup, index.toLong()).toString())
    put("sourceItemId", UUID(0, index.toLong()).toString())
    put("seasonId", seasonId.toString())
    put("revision", revision)
    put("status", status)
    put("sourceUpdatedAt", Instant.parse("2026-08-11T01:00:00Z").plusSeconds(revision.toLong()).toString())
}
