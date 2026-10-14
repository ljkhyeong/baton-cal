package io.baton.cal.projection

import io.baton.cal.calendar.IcsCalendarRenderer
import io.baton.cal.persistence.CalendarItemRepository
import io.baton.cal.persistence.CalendarItemRow
import io.baton.cal.persistence.SeasonCalendarMetadataRepository
import io.baton.cal.persistence.SeasonFeedHeaders
import io.baton.cal.persistence.SeasonFeedProjectionRepository
import io.baton.cal.persistence.SeasonFeedProjectionRow
import io.baton.cal.persistence.AdvisoryLockRepository
import io.baton.cal.web.ProjectionRebuildAllResponse
import io.baton.cal.web.ProjectionRebuildResponse
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.function.Supplier

@Service
class SeasonProjectionService(
    private val lockRepository: AdvisoryLockRepository,
    private val itemRepository: CalendarItemRepository,
    private val projectionRepository: SeasonFeedProjectionRepository,
    private val metadataRepository: SeasonCalendarMetadataRepository,
    private val renderer: IcsCalendarRenderer,
    private val transaction: TransactionOperations,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val rebuildTimer: Timer = Timer.builder("baton.cal.projection.rebuild")
        .description("시즌 피드 전체 재구축 시간")
        .register(meterRegistry)
    private val itemCountSummary: DistributionSummary = DistributionSummary
        .builder("baton.cal.projection.items")
        .description("재구축한 시즌의 일정 항목 수")
        .baseUnit("items")
        .register(meterRegistry)
    private val byteSizeSummary: DistributionSummary = DistributionSummary
        .builder("baton.cal.projection.bytes")
        .description("재구축한 iCalendar 표현 크기")
        .baseUnit("bytes")
        .register(meterRegistry)

    @Transactional
    fun rebuild(seasonId: UUID): ProjectionRebuildResponse {
        lockRepository.lockSeason(seasonId)
        return rebuildWhileLocked(seasonId)
    }

    /**
     * 저장된 모든 시즌 투영을 현재 렌더러로 다시 만든다. 렌더러나 내장 시간대 데이터를 바꾼 배포 뒤 일정 수신이 없는
     * 시즌도 새 출력을 쓰게 한다. 한 번에 한 시즌만 잠그도록 시즌마다 따로 커밋하고, 같은 잠금 안에서 ETag를 비교해
     * 바뀐 시즌을 센다. 호출자 트랜잭션에 묶이지 않도록 트랜잭션 밖에서 호출한다.
     */
    fun rebuildAll(): ProjectionRebuildAllResponse {
        val seasonIds = projectionRepository.listSeasonIds()
        val changedSeasonCount = seasonIds.count { seasonId ->
            transaction.execute {
                lockRepository.lockSeason(seasonId)
                val existing = projectionRepository.findHeadersBySeasonId(seasonId)
                rebuildWhileLocked(seasonId, existing).etag != existing?.etag
            } == true
        }
        return ProjectionRebuildAllResponse(seasonCount = seasonIds.size, changedSeasonCount = changedSeasonCount)
    }

    @Transactional
    fun ensureProjection(seasonId: UUID) {
        if (projectionRepository.findHeadersBySeasonId(seasonId) != null) {
            return
        }

        lockRepository.lockSeason(seasonId)
        if (projectionRepository.findHeadersBySeasonId(seasonId) == null) {
            rebuildWhileLocked(seasonId, existing = null)
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun rebuildWhileLocked(seasonId: UUID): ProjectionRebuildResponse =
        rebuildWhileLocked(seasonId, projectionRepository.findHeadersBySeasonId(seasonId))

    private fun rebuildWhileLocked(
        seasonId: UUID,
        existing: SeasonFeedHeaders?,
    ): ProjectionRebuildResponse =
        rebuildTimer.record(Supplier {
            val rows = itemRepository.listBySeasonId(seasonId)
            val metadata = metadataRepository.findBySeasonId(seasonId)
            val rendered = renderer.render(
                seasonId = seasonId,
                items = rows.map(CalendarItemRow::toCalendarItem),
                displayName = metadata?.displayName,
            )
            val lastModified = when {
                // 처음 만드는 투영은 항목과 시즌 이름 중 가장 늦은 채택 시각을 초 단위로 쓰고, 둘 다 없으면 Unix epoch를 쓴다.
                existing == null -> (rows.map(CalendarItemRow::acceptedAt) + listOfNotNull(metadata?.acceptedAt))
                    .maxOrNull()
                    ?.truncatedTo(ChronoUnit.SECONDS)
                    ?: Instant.EPOCH

                existing.etag == rendered.etag -> existing.lastModified
                else -> clock.instant().truncatedTo(ChronoUnit.SECONDS)
            }
            projectionRepository.upsert(SeasonFeedProjectionRow(seasonId, rendered.bytes, rendered.etag, lastModified))
            itemCountSummary.record(rows.size.toDouble())
            byteSizeSummary.record(rendered.bytes.size.toDouble())
            ProjectionRebuildResponse(seasonId = seasonId, etag = rendered.etag, itemCount = rows.size)
        })
}
