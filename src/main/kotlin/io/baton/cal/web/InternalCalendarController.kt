package io.baton.cal.web

import io.baton.cal.config.StandardUuidPath
import io.baton.cal.projection.SeasonProjectionService
import io.baton.cal.projection.SeasonCalendarMetadataService
import io.baton.cal.recovery.RecoveryManifestService
import io.baton.cal.snapshot.SnapshotIngestionService
import io.baton.cal.snapshot.SnapshotIngestionResult
import io.baton.cal.subscription.SubscriptionService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

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
    private val ingestionCounters: Map<SnapshotIngestionResult, Counter> =
        SnapshotIngestionResult.entries.associateWith { result ->
            Counter.builder(INGESTION_METRIC)
                .description("일정 스냅샷 수신 판정")
                .tag("result", result.name.lowercase())
                .register(meterRegistry)
        }

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
        @PathVariable sourceItemId: StandardUuidPath,
    ) = noStore(snapshotIngestionService.getItemStatus(sourceItemId.value))

    @GetMapping("/subscriptions/{subscriptionId}")
    fun getSubscriptionStatus(
        @PathVariable subscriptionId: StandardUuidPath,
    ) = noStore(subscriptionService.getStatus(subscriptionId.value))

    @PutMapping("/seasons/{seasonId}/calendar-metadata")
    fun updateSeasonCalendarMetadata(
        @PathVariable seasonId: StandardUuidPath,
        @Valid @RequestBody request: SeasonCalendarMetadataRequest,
    ) = noStore(metadataService.update(seasonId.value, request.revision, request.displayName))

    @GetMapping("/recovery-runs/{recoveryId}")
    fun getRecoveryRunStatus(
        @PathVariable recoveryId: StandardUuidPath,
    ) = noStore(recoveryManifestService.getStatus(recoveryId.value))

    @GetMapping("/seasons/{seasonId}/recovery-state")
    fun getRecoverySeasonState(
        @PathVariable seasonId: StandardUuidPath,
    ) = noStore(recoveryManifestService.getSeasonState(seasonId.value))

    @PutMapping("/recovery-runs/{recoveryId}/seasons/{seasonId}/manifest")
    fun verifyRecoverySeasonManifest(
        @PathVariable recoveryId: StandardUuidPath,
        @PathVariable seasonId: StandardUuidPath,
        @Valid @RequestBody request: RecoverySeasonManifestRequest,
    ) = noStore(recoveryManifestService.verifySeason(recoveryId.value, request.toState(seasonId.value)))

    @PutMapping("/recovery-runs/{recoveryId}/completion")
    fun completeRecoveryRun(
        @PathVariable recoveryId: StandardUuidPath,
        @Valid @RequestBody request: RecoveryRunCompletionRequest,
    ) = noStore(recoveryManifestService.complete(recoveryId.value, request.seasonCount, request.seasonDigest))

    @PostMapping("/subscriptions")
    fun createSubscription(
        @RequestBody request: CreateSubscriptionRequest,
    ) = noStore(subscriptionService.create(request.seasonId), HttpStatus.CREATED)

    @PostMapping("/subscriptions/{subscriptionId}/rotate")
    fun rotateSubscription(
        @PathVariable subscriptionId: StandardUuidPath,
    ) = noStore(subscriptionService.rotate(subscriptionId.value))

    @PutMapping("/subscriptions/{subscriptionId}")
    fun createSubscriptionWithId(
        @PathVariable subscriptionId: StandardUuidPath,
        @RequestBody request: CreateSubscriptionRequest,
    ) = noStore(subscriptionService.create(request.seasonId, subscriptionId.value), HttpStatus.CREATED)

    @DeleteMapping("/subscriptions/{subscriptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeSubscription(
        @PathVariable subscriptionId: StandardUuidPath,
    ) {
        subscriptionService.revoke(subscriptionId.value)
    }

    @PostMapping("/projections/seasons/{seasonId}/rebuild")
    fun rebuildProjection(
        @PathVariable seasonId: StandardUuidPath,
    ) = projectionService.rebuild(seasonId.value)

    private fun <T : Any> noStore(body: T, status: HttpStatus = HttpStatus.OK): ResponseEntity<T> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)

    private companion object {
        const val INGESTION_METRIC = "baton.cal.snapshot.ingestion"
    }
}
