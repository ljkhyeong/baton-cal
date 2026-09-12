package io.baton.cal.web

import io.baton.cal.snapshot.SnapshotIngestionResult
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import java.util.UUID

data class SnapshotBatchRequest(
    @field:Size(min = 1, max = 100)
    val snapshots: List<@Valid ScheduleSnapshotRequest>,
)

data class SnapshotBatchResponse(
    val results: List<SnapshotBatchItemResult>,
)

data class SnapshotBatchItemResult(
    val eventId: UUID,
    val result: SnapshotIngestionResult,
)
