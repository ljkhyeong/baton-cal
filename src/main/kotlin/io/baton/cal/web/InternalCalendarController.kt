package io.baton.cal.web

import io.baton.cal.projection.SeasonProjectionService
import io.baton.cal.projection.SeasonCalendarMetadataService
import io.baton.cal.recovery.RecoveryManifestService
import io.baton.cal.snapshot.SnapshotIngestionService
import io.baton.cal.snapshot.SnapshotIngestionResult
import io.baton.cal.subscription.SubscriptionService
import io.micrometer.core.instrument.MeterRegistry
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/internal/api/v1")
class InternalCalendarController(
    private val snapshotIngestionService: SnapshotIngestionService,
    private val subscriptionService: SubscriptionService,
    private val projectionService: SeasonProjectionService,
    private val metadataService: SeasonCalendarMetadataService,
    private val recoveryManifestService: RecoveryManifestService,
    meterRegistry: MeterRegistry,
) {
    // 서비스 트랜잭션이 커밋된 뒤 증가시켜 롤백된 묶음 수신의 앞 항목을 집계하지 않는다.
    private val ingestionCounters =
        meterRegistry.resultCounters<SnapshotIngestionResult>(INGESTION_METRIC, "일정 스냅샷 수신 판정")

    @PostMapping("/schedule-snapshots")
    fun ingestSnapshot(
        @Valid @RequestBody request: ScheduleSnapshotRequest,
    ): SnapshotIngestionResponse {
        val result = snapshotIngestionService.ingest(request.toDomain())
        ingestionCounters.getValue(result).increment()
        return SnapshotIngestionResponse(result)
    }

    @PostMapping("/schedule-snapshots/batch")
    fun ingestSnapshotBatch(
        @Valid @RequestBody request: SnapshotBatchRequest,
    ): SnapshotBatchResponse {
        val snapshots = request.snapshots.map { it.toDomain() }
        val results = snapshotIngestionService.ingestBatch(snapshots)
        results.forEach { ingestionCounters.getValue(it).increment() }
        return SnapshotBatchResponse(
            snapshots.zip(results) { snapshot, result -> SnapshotBatchItemResult(snapshot.eventId, result) },
        )
    }

    @GetMapping("/calendar-items/{sourceItemId}")
    fun getCalendarItemStatus(
        @PathVariable sourceItemId: UUID,
    ) = snapshotIngestionService.getItemStatus(sourceItemId)

    @GetMapping("/subscriptions/{subscriptionId}")
    fun getSubscriptionStatus(
        @PathVariable subscriptionId: UUID,
    ) = subscriptionService.getStatus(subscriptionId)

    @PutMapping("/seasons/{seasonId}/calendar-metadata")
    fun updateSeasonCalendarMetadata(
        @PathVariable seasonId: UUID,
        @Valid @RequestBody request: SeasonCalendarMetadataRequest,
    ) = metadataService.update(seasonId, request.revision, request.displayName)

    @GetMapping("/recovery-runs/{recoveryId}")
    fun getRecoveryRunStatus(
        @PathVariable recoveryId: UUID,
    ) = recoveryManifestService.getStatus(recoveryId)

    @GetMapping("/seasons/{seasonId}/recovery-state")
    fun getRecoverySeasonState(
        @PathVariable seasonId: UUID,
    ) = recoveryManifestService.getSeasonState(seasonId)

    @PutMapping("/recovery-runs/{recoveryId}/seasons/{seasonId}/manifest")
    fun verifyRecoverySeasonManifest(
        @PathVariable recoveryId: UUID,
        @PathVariable seasonId: UUID,
        @Valid @RequestBody request: RecoverySeasonManifestRequest,
    ) = recoveryManifestService.verifySeason(recoveryId, request.toState(seasonId))

    @PutMapping("/recovery-runs/{recoveryId}/completion")
    fun completeRecoveryRun(
        @PathVariable recoveryId: UUID,
        @Valid @RequestBody request: RecoveryRunCompletionRequest,
    ) = recoveryManifestService.complete(recoveryId, request.seasonCount, request.seasonDigest)

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    fun createSubscription(
        @RequestBody request: CreateSubscriptionRequest,
    ) = subscriptionService.create(request.seasonId)

    @PostMapping("/subscriptions/{subscriptionId}/rotate")
    fun rotateSubscription(
        @PathVariable subscriptionId: UUID,
    ) = subscriptionService.rotate(subscriptionId)

    @PutMapping("/subscriptions/{subscriptionId}")
    @ResponseStatus(HttpStatus.CREATED)
    fun createSubscriptionWithId(
        @PathVariable subscriptionId: UUID,
        @RequestBody request: CreateSubscriptionRequest,
    ) = subscriptionService.create(request.seasonId, subscriptionId)

    @DeleteMapping("/subscriptions/{subscriptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeSubscription(
        @PathVariable subscriptionId: UUID,
    ) {
        subscriptionService.revoke(subscriptionId)
    }

    @PostMapping("/projections/seasons/{seasonId}/rebuild")
    fun rebuildProjection(
        @PathVariable seasonId: UUID,
    ) = projectionService.rebuild(seasonId)

    private companion object {
        const val INGESTION_METRIC = "baton.cal.snapshot.ingestion"
    }
}
