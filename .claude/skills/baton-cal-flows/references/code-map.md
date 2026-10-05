# CAL 코드 지도

운영 코드는 `src/main/kotlin/io/baton/cal/`, 테스트는 `src/test/kotlin/io/baton/cal/` 아래에 있다.
테스트 이름은 `io.baton.cal.<패키지>.<클래스>`로 지정한다. 표시한 `DB` 테스트는 Docker의 PostgreSQL을 사용한다.
내부 HTTP 테스트는 `support/InternalHttpTest.kt`의 `@InternalHttpTest`(복구 모드는 `@RecoveryModeInternalHttpTest`)로
Spring 컨텍스트를 공유한다. 속성이나 빈 교체가 다르면 컨텍스트와 DB 연결 풀이 늘어나므로 필요할 때만 별도 설정을 둔다.
요청 보조 함수는 `support/InternalApiTestSupport.kt`, 계약 예시·스키마 검증은 `contract/ContractSchemaSupport.kt`에 있다.

| 영역 | 운영 코드 | 관련 테스트 |
| --- | --- | --- |
| 일정 수신 | `snapshot/ScheduleSnapshot.kt`(도메인), `snapshot/SnapshotIngestionService.kt`, `web/ScheduleSnapshotDtos.kt`, `web/SnapshotBatchDtos.kt`, `persistence/SourceEventInboxRepository.kt`, `persistence/CalendarItemRepository.kt`, `persistence/CalendarItemRow.kt`(시간 열 변환) | `snapshot.SnapshotFingerprintTest`, `snapshot.SnapshotIngestionConcurrencyTest`(DB), `snapshot.RenderFailureRollbackTest`(DB), `web.SnapshotInputContractTest`(DB), `web.SnapshotBatchHttpTest`(DB) |
| iCalendar 렌더링 | `calendar/CalendarItem.kt`(도메인), `calendar/IcsCalendarRenderer.kt` | `calendar.IcsCalendarRendererTest`, `web.PublicCalendarContractTest`(DB) |
| 시즌 투영·표시 이름 | `projection/SeasonProjectionService.kt`, `projection/SeasonCalendarMetadataService.kt`, `persistence/SeasonFeedProjectionRepository.kt`, `persistence/SeasonProjectionLockRepository.kt`, `persistence/SeasonCalendarMetadataRepository.kt`, `web/ProjectionRebuildResponse.kt` | `projection.SeasonProjectionServiceTest`, `web.SeasonCalendarMetadataHttpTest`(DB), `web.PublicCalendarContractTest`(DB) |
| 구독 토큰 | `subscription/SubscriptionService.kt`, `subscription/SubscriptionTokenCodec.kt`, `persistence/CalendarSubscriptionRepository.kt`, `web/SubscriptionDtos.kt` | `subscription.SubscriptionTokenCodecTest`, `subscription.SubscriptionConcurrencyTest`(DB), `web.MvpHttpFlowTest`(DB) |
| 복구 | `recovery/RecoveryManifestDigest.kt`(도메인, 대조값 계산), `snapshot/DigestWriter.kt`(스냅샷 지문과 공유하는 다이제스트 인코딩), `recovery/RecoveryManifestService.kt`, `persistence/RecoveryManifestRepository.kt`, `web/RecoveryManifestDtos.kt` | `recovery.RecoveryManifestDigestTest`, `web.RecoveryManifestHttpTest`(DB), `web.RecoveryModeHttpTest`(DB) |
| HTTP 공통·인증·오류 | `web/InternalCalendarController.kt`, `web/PublicCalendarController.kt`, `web/InternalApiAuthenticationFilter.kt`, `web/ApiExceptionHandler.kt`, `web/ApiErrors.kt`, `web/ContractInputPatterns.kt`, `web/CalendarItemStatusResponse.kt` | `web.ApiExceptionHandlerTest`, `web.InternalApiAuthenticationHttpTest`(DB), `web.OperationalHttpTest`(DB), `web.MvpHttpFlowTest`(DB) |
| 설정·운영 프로필 | `config/*.kt`, `src/main/resources/application*.yml` | `config.CalPropertiesTest`, `config.ProductionProfileConfigurationTest`, `config.TlsHttpIntegrationTest`(DB) |
| DB 스키마 | `src/main/resources/db/migration/V*.sql`, `persistence/PersistenceRows.kt`, `persistence/CalendarItemRow.kt` | `persistence.PersistenceRepositoryTest`(DB) |
| 계약 산출물 | `contracts/**`, PRD-0002 | `contract.ContractArtifactsTest`, `verifyContractsZip` |

## Gradle 작업

| 작업 | 범위 |
| --- | --- |
| `test` | 일반 테스트. `load`·`ingestion-load`·`architecture` 태그는 제외한다 |
| `architectureTest` | ArchUnit 계층 규칙. DB 없이 실행한다 |
| `feedbackLoopTest` | `scripts/tests`의 검사 스크립트 회귀 테스트 |
| `verifyContractsZip` | 계약 ZIP 생성과 파일명·내부 버전·포함 파일 검증 |
| `check` | 위 작업 전체 |
| `projectionLoadTest`, `ingestionLoadTest` | 부하 측정. 목표·환경을 정한 경우에만 실행한다 |
| `bootJar`, `bootBuildImage` | 실행 JAR, OCI 이미지 |

예: `./gradlew --no-daemon --max-workers=2 test --tests 'io.baton.cal.web.MvpHttpFlowTest'`
