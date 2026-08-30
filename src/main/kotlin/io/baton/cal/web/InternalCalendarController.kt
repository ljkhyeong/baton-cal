package io.baton.cal.web

import io.baton.cal.config.StandardUuidPath
import io.baton.cal.projection.SeasonProjectionService
import io.baton.cal.projection.SeasonCalendarMetadataService
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
    meterRegistry: MeterRegistry,
) {
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

    @GetMapping("/calendar-items/{sourceItemId}")
    fun getCalendarItemStatus(
        @PathVariable sourceItemId: StandardUuidPath,
    ): ResponseEntity<CalendarItemStatusResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(snapshotIngestionService.getItemStatus(sourceItemId.value))

    @GetMapping("/subscriptions/{subscriptionId}")
    fun getSubscriptionStatus(
        @PathVariable subscriptionId: StandardUuidPath,
    ): ResponseEntity<SubscriptionStatusResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(subscriptionService.getStatus(subscriptionId.value))

    @PutMapping("/seasons/{seasonId}/calendar-metadata")
    fun updateSeasonCalendarMetadata(
        @PathVariable seasonId: StandardUuidPath,
        @Valid @RequestBody request: SeasonCalendarMetadataRequest,
    ): ResponseEntity<SeasonCalendarMetadataResponse> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(metadataService.update(seasonId.value, request.revision, request.displayName))

    @PostMapping("/subscriptions")
    fun createSubscription(
        @RequestBody request: CreateSubscriptionRequest,
    ): ResponseEntity<SubscriptionCredential> = ResponseEntity
        .status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(subscriptionService.create(request.seasonId))

    @PostMapping("/subscriptions/{subscriptionId}/rotate")
    fun rotateSubscription(
        @PathVariable subscriptionId: StandardUuidPath,
    ): ResponseEntity<SubscriptionCredential> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(subscriptionService.rotate(subscriptionId.value))

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

    private companion object {
        const val INGESTION_METRIC = "baton.cal.snapshot.ingestion"
    }
}
