package io.baton.cal.web

import io.baton.cal.projection.SeasonProjectionService
import io.baton.cal.snapshot.SnapshotIngestionService
import io.baton.cal.subscription.SubscriptionService
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
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
) {
    @PostMapping("/schedule-snapshots")
    fun ingestSnapshot(
        @Valid @RequestBody request: ScheduleSnapshotRequest,
    ): SnapshotIngestionResponse = SnapshotIngestionResponse(
        result = snapshotIngestionService.ingest(request.toDomain()),
    )

    @PostMapping("/subscriptions")
    fun createSubscription(
        @RequestBody request: CreateSubscriptionRequest,
    ): ResponseEntity<SubscriptionCredential> = ResponseEntity
        .status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(subscriptionService.create(request.seasonId))

    @PostMapping("/subscriptions/{subscriptionId}/rotate")
    fun rotateSubscription(
        @PathVariable subscriptionId: UUID,
    ): ResponseEntity<SubscriptionCredential> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .body(subscriptionService.rotate(subscriptionId))

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
}
