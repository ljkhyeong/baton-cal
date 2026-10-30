# 인수인계

## 현재 상태

- CAL의 일정 수신·캘린더 변환·구독·복구 진단을 구현했다. 기능은 [README](README.md),
  API는 [PRD-0002](docs/PRD/0002_mvp-contract/spec.md)를 따른다.
- 공개 HEAD는 캘린더 본문을 조회하지 않고 구독 상태·캐시 검증 값·파일 크기를 반환한다.
  [후속 기능 검토](docs/reviews/2026-09-12-feature-review.md)에 채택 근거와 보류 항목을 정리했다.
- `POST /internal/api/v1/schedule-snapshots/batch`는 1~100건을 한 트랜잭션으로 처리하고 변경된 시즌별로
  한 번 재생성한다. JSON 128 KiB 상한을 유지하고 토큰 상한을 8,192개로 늘렸다. 기존 단건 경로는 유지한다.
  BATON 아웃박스의 묶음 전송은 아직 연결하지 않았다. [측정 결과](docs/performance-baseline.md)
- 개정 번호·복구 건수의 소수·지수 표기를 정수로 잘라 받던 동작을 차단했다. `0.5`, `1.0`, `1e0`은
  저장 전에 `400 INVALID_REQUEST`로 반환한다. 타임스탬프의 소수 초와 허용된 `null`은 유지한다.
- 문자열 필드의 숫자·불리언 입력도 변환 없이 `400 INVALID_REQUEST`로 반환한다. 정상 문자열과
  허용된 필드 생략·`null`은 유지하며 Jackson의 타입별 설정을 사용한다.
- DB 연결·트랜잭션 시작 실패, 교착 상태·직렬화 실패와 시간 초과는 `503 SERVICE_BUSY`와 재시도 간격을 반환한다.
  교착 상태·직렬화 실패는 Spring의 공통 잠금 실패 예외로 처리한다. 실제 연결 풀 고갈 테스트에서는
  연결 반환 후 기존 구독 조회와 같은 ID의 발급 재시도가 정상 처리되는지 확인했다.
- 이미 저장된 구독의 재요청은 캘린더 준비와 토큰 생성을 생략한다. 동시 생성은 DB 기본키로 판정한다.
  구독의 투영 외래키 제약에 따라 캘린더 준비 후 구독을 저장하는 순서는 유지한다.
- 내부 API 응답은 인증 필터가 성공·오류와 `401`·`405`까지 모두 `Cache-Control: no-store`로 보낸다. 상태가 바뀐 뒤
  이전 응답이 재사용되는 것을 막는다. 공개 피드의 `503`은 예외 처리기가 같은 헤더를 붙이고 오류 본문은 `Accept`와
  관계없이 JSON이다.
- 보존할 DB 데이터와 운영 배포가 없어 마이그레이션 V1~V8을 최종 스키마의 V1 하나로 합쳤다. 운영 데이터가 생기기
  전까지는 V1을 직접 고친다. 시즌 잠금은 잠금 전용 테이블 대신 복구 실행과 같은 `pg_advisory_xact_lock`을 쓰고,
  경로 UUID는 `@InitBinder` 편집기로 36자 표준 형식만 받는다. 기존 V1~V8을 적용한 로컬 DB는 다시 만들어야 한다.
  표준 API 대체 검토에서 유지한 구현과 근거는 [코드 축소 검토](docs/reviews/2026-10-05-code-reduction-review.md)에 정리했다.
- 2026-10-06 2차 정리로 운영 코드 순 313줄, 테스트 순 500줄, 스모크 스크립트·Compose 순 98줄을 줄였다. HTTP 상태·
  헤더·오류 `code`, iCalendar 바이트·지문과 복구 다이제스트 바이트는 같고, 시즌 이름 짝이 맞지 않는 복구 매니페스트의
  `400` `message`만 공통 문구로 바뀌었다. V1을 다시 고쳐 수신함에서 읽지 않는 `season_id`·`occurred_at`을
  지우고 시간 형태 CHECK를 축약했으며, 복구 매니페스트 짝 CHECK가 한쪽 `NULL`을 통과시키던 결함을 고쳤다. `occurredAt`은
  HTTP 입력 형식만 검증한다. 반영·제외 근거는 [2차 정리](docs/reviews/2026-10-05-code-reduction-review.md#2차-정리--2026-10-06)에 있다.
- 필드가 응답 스키마와 같은 DTO는 두지 않고 일정 상태는 `AcceptedItemStatus`, 시즌 복구 진단은 `RecoverySeasonState`를
  그대로 응답한다. 스모크 스크립트는 준비 대기·내부 요청·429 헤더 확인을 curl 재시도·`--json`·`%header`로, 알림 JSON 생성을
  jq로 처리한다. 인증 401과 경로 UUID 400은 각각 한 테스트가 모든 내부 경로를 확인한다.
- 파일 작성 직후 검사와 종료 전 전체 diff·ArchUnit 검사를 추가했다. 기존 Spring Repository 주입 구조를
  유지하며 Controller의 DB 접근, 도메인의 실행 계층 의존, Service의 JDBC 사용·Repository 생성을 검사한다.
  실행 방법과 Codex 훅 신뢰 절차는 [개발 검증 절차](docs/development.md)를 따른다.
- Claude Code용 프로젝트 스킬 8개를 `.claude/skills/`에 추가했다. Codex 전역 `baton-cal-flows`를 저장소 기준으로
  옮기고, 범용 검토·CI 실패·커밋 스킬을 CAL 규칙에 맞게 다시 작성했다. 문서·운영·의존성·계약 릴리스 스킬과
  문서 링크 검사기도 추가했다. [스킬 목록](docs/development.md#claude-code-스킬)
- 운영 코드·테스트·스모크 스크립트·Gradle 설정의 중복을 정리해 순 442줄을 줄였다. HTTP 응답·계약·저장 열은
  같다. 공개 피드 조회 조건(ACTIVE·토큰 해시·구독 세대)은 저장소 쿼리 하나에서 공유한다. 같은 설정의 PostgreSQL
  통합 테스트는 `@CalIntegrationTest`·`@RecoveryModeIntegrationTest`로 Spring 컨텍스트를 재사용하고, 요청·계약 예시 보조
  함수는 `support`·`contract` 테스트 패키지에 둔다. Boot 기본값과 같은 설정은 지우되 보안 의도를 드러내는 health 상세 숨김·
  접근 로그 비활성화는 유지했다.
- 시간 형태 이름은 도메인 `ScheduleTimeType`(`calendar/CalendarItem.kt`) 하나로 두고 지문·DB 행 변환이 `ScheduleWindow.type`을
  쓴다. 일정 시간 열과 `ScheduleWindow`의 양방향 변환은 `persistence/CalendarItemRow.kt`, 수신 결과 분류는
  `SnapshotIngestionService`, 시즌·복구 실행 advisory lock은 `AdvisoryLockRepository`, 복구 대조값 계산은
  `RecoveryManifestDigest`, 첫 투영의 Last-Modified는 `SeasonProjectionService`가 맡는다. API 오류는 단일
  `ApiException`과 `invalidRequest`·`conflict`·`resourceNotFound` 함수로 만든다. 피드 헤더 값 타입은 `SeasonFeedHeaders`이며
  계약 TEXT·다이제스트 입력 패턴은 `web/ContractInputPatterns.kt`에서 공유한다. 서비스가 `web` 예외·DTO를 쓰는 구조는
  ADR-0002에 따라 유지한다.
- 스냅샷 지문과 복구 다이제스트는 PRD-0002의 같은 필드 인코딩을 `snapshot/DigestWriter.kt` 하나로 구현한다.
  BATON과 공유하는 복구 다이제스트는 계약 예시를 벡터로 삼아 DB 없이 검증한다. 알림 채널 스모크는 이미지 고정값을
  Compose 정의에서 읽고, 이미지 스모크는 Flyway 기대 버전을 마이그레이션 파일에서 만든다.
- 일정 전달이 조용히 멈추는 경우를 알리는 `CalInternalAuthenticationFailed`(내부 Bearer 인증 실패 반복)와
  `CalIngestionRejected`(일정·묶음·시즌 이름 수신의 `4xx`, 첫 거부 포함)를 추가했다. 4xx는 기존 5xx 알림에 잡히지
  않는다. 공개 조회의 최근 조회일 기록 실패가 반복되면 `CalSubscriptionFetchRecordFailed`가 발생한다. 실제 수신 채널 도착은 운영 연결 뒤 확인한다. [알림 표](docs/operations.md#모니터링과-알림)
- BATON의 개인 구독·내 구독 목록·여러 구독 해제·앱별 등록 안내는 원격 main에 반영됐다.
  실제 캘린더 앱 검증과 운영 활성화는 남아 있다.
- CAL [PR #20](https://github.com/ljkhyeong/baton-cal/pull/20)은 `118577a`,
  BATON [PR #21](https://github.com/ljkhyeong/baton/pull/21)은 `c94469e`로 원격 main에 병합됐다.
  BATON 필수 CI와 브라우저 테스트 746개가 통과했다. 다른 작업 브랜치는 변경하지 않았다.
- 안정 계약은 `1.0.0`, 공식 후보는 `1.1.0-rc.2`다. `3ba5889`에서 게시한 불변 릴리스·ZIP 증명을
  검증하고 BATON의 버전·해시와 구독·복구 스키마 출처를 고정했다. 실제 앱 검증·운영 활성화와
  안정 버전 승격은 남아 있다. [릴리스 현황](docs/contract-release-history.md)
  작업 후보 `1.1.0-rc.3`은 마이그레이션 통합에 맞춰 PRD-0002의 구버전 배포 순서 설명을 지우고 전체 시즌 투영
  재생성·구독 최근 조회일 경로와 iCal4j 4.4.0 기준을 추가했다. 기존 경로의 HTTP·스키마·다이제스트·iCalendar
  바이트는 같고 게시하지 않았다.
  [릴리스 노트 초안](docs/releases/contracts-v1.1.0-rc.3.md)
  게시된 `contracts/**`·루트 `LICENSE`·PRD-0002를 바꾸려면 다음 계약 버전으로 올린다.
- iCal4j·내장 시간대 데이터를 바꾼 배포 뒤 저장된 피드를 바꾸는 `POST /internal/api/v1/projections/rebuild`를
  추가했다. 투영이 저장된 모든 시즌을 시즌마다 따로 커밋해 다시 만들고 처리한 시즌 수와 바이트가 바뀐 시즌 수를
  반환한다. 과거 DB 복원 절차에도 복구 모드 전체 재생성을 넣어 백업 시점 출력이 남지 않게 했다. 운영 배포 전이라
  실제 운영 실행 기록은 없다. [실행 절차](README.md#캘린더-출력-변경-배포),
  [2026-10-14 기능 검토와 후속 진행](docs/reviews/2026-10-14-feature-review.md)에 채택·보류 근거를 정리했다.
- 공개 GET·HEAD·`304`가 성공하면 구독별 최근 조회일을 UTC 날짜로 하루 한 번 기록하고
  `GET /internal/api/v1/subscriptions/{subscriptionId}/activity`로 반환한다. 조회와 다른 문장에서 잠긴 행을
  건너뛰며 기록 실패는 피드 응답 대신 `baton.cal.subscription.fetch.record.failures`로 센다. 회전·폐기는 값을
  비우고 이전 세대 구독은 `null`이다. 조회 시각·IP·클라이언트 정보는 저장하지 않는다. BATON 화면 연결은 남아 있다.
- iCal4j를 4.4.0으로 올렸다. 내장 Olson `2025a`·`tz.alias`가 같고 골든 바이트·ETag가 유지되며 시간대 외부 갱신은
  계속 꺼져 있다. 위 세 변경은 [PR #40](https://github.com/ljkhyeong/baton-cal/pull/40)으로 `c7370b0`에 병합됐고
  Dependabot #39는 자동으로 닫혔다.
- Dependabot PR을 종류별 커밋으로 반영했다. GitHub Actions는 태그 커밋 SHA로, Compose 이미지는 태그와 레지스트리 digest로,
  Gradle Wrapper 9.8.2는 공식 해시 대조로 고정한다. Kotlin 2.4.21·ArchUnit 1.5.1·JSON Schema 검증기 3.0.8은 잠금 파일·
  검증 메타데이터를 다시 썼다. `bootBuildImage` 빌더는 `latest`가 단일 아키텍처를 가리켜 PR이 실패한 뒤
  `operations/buildpack/Dockerfile`의 태그·digest로 고정했고 Dependabot이 갱신한다. PR #41은 `9cafd90`으로 병합됐고
  `main` CI가 `docker/login-action` v4 로그인과 이미지 게시까지 통과했다.
- 2026-10-30 전수검토로 공개 프록시 요청 분할, DST 공백 오판, 저장 행 재검증으로 인한 시즌 정지, `status` 숫자 입력,
  계약 파일 변경 시 테스트 미실행을 포함한 결함을 재현해 고쳤다. DST 공백은 피드에 넣는 `VTIMEZONE`의 전환을 직접 평가해
  수신 입력에서만 판정한다. 공개 프록시는 토큰 형식 경로만 전달한다. 수정·미수정 판단과 근거는
  [전수검토](docs/reviews/2026-10-30-full-review.md)에 있다.
- 기존 Alertmanager에 Slack·Discord 기본 연동을 추가했다. Blackbox Exporter는 `cal.b4ton.com`의
  인증서 이름·체인·만료 시각을 검사하며, 실패와 14일 이내 만료를 알린다.
  [외부 연동 검토](docs/external-api-options.md), [연결 방법](docs/operations.md#운영-알림-채널-연결)
- Slack·Discord와 Healthchecks를 함께 쓰는 완성된 조합을 추가했다. 채널 알림과 정상 신호를 분리하며
  설정을 직접 합칠 필요가 없다. BATON 연결용 내부 토큰은 32~200자, 영문·숫자와 `-._~`로 안내했다.
  CAL의 기존 Bearer 허용 범위는 유지한다. [설정 선택과 입력 기준](docs/integration-runtime.md)
- 웹훅 스모크는 모의 API의 `429`·`503` 뒤 전달 재개와 오류 로그의 URL 비노출을 검사한다.
  실패한 시도와 실제 수신 기록을 분리한다. 기본 재시도는 Alertmanager가 맡으며 제품 코드는 추가하지 않았다.
- Blackbox의 공개 경로 점검을 추가했다. 발급될 수 없는 고정 주소의 빈 `404`로 프록시→CAL 연결을
  확인하고, HTML 오류 페이지·연결 장애가 30초 지속되면 알린다. 실제 구독 토큰은 사용하지 않는다.
  [점검 범위](docs/operations.md#모니터링과-알림)
- 서버·모니터링 중단을 확인할 Healthchecks.io 선택 연동을 추가했다. Prometheus 정상 신호를
  Alertmanager가 외부로 전송하며, 기본 알림 채널에서는 이 신호를 제외한다. 무료 점검 1개를 쓰는
  설정이고 실제 계정·수신 채널은 아직 연결하지 않았다. [연결 방법](docs/operations.md#서버와-모니터링-중단-감지)
- Spring Boot `configtree`로 DB 비밀번호·현재 및 이전 내부 토큰을 Secret 파일에서 읽는 경로를 검증했다.
  제품 코드·의존성 추가 없이 기존 운영 설정에 연결한다. 파일 이름과 재시작 기준은
  [Secret 파일 연결](docs/operations.md#secret-파일-연결)을 따른다. 실제 k3s 마운트는 배포 시 적용한다.
- BATON의 HTTPS 필수 조건과 공개 프록시의 내부 API 차단을 함께 확인했다. CAL 직접 HTTPS용 `tls`
  프로필, `.env.production.example`, [API·웹훅 실행 입력](docs/integration-runtime.md)을 추가했다.
  `.env.integration.local`과 `build/local-integration/`에는 Git에서 제외한 로컬 임시값만 준비했다.
  인증서는 생성 후 2일간 유효하고 로컬 DB 연결값은 실행 환경에 맞춰야 한다.
- `prod`는 API 포트에 `/livez`·`/readyz`를 제공한다. Spring Boot 상태를 재사용하고 준비 상태에만
  기존 DB 점검을 포함한다. k3s 점검에 사용할 경로이며 실제 배포 설정은 적용하지 않았다.
- `tls`는 SSL bundle의 파일 갱신 감시로 인증서·키 교체를 재시작 없이 반영한다. Secret 디렉터리의
  링크 교체와 새 HTTPS 연결의 인증서 변경을 검증했다. `subPath` 마운트는 갱신되지 않으며 DB 비밀번호·
  내부 토큰은 기존대로 재시작해야 한다. [연결 조건](docs/integration-runtime.md#인증서-갱신-반영)
- 사용자 확인 기준으로 Ubuntu 홈서버·DNS·공인 IP·80/443 포트포워딩·인증서는 준비됐고 k3s는 구축 전이다.
  로컬 연동만 검증했으며 실제 서버·DNS·인증서·운영 알림 채널을 변경하지 않았다.

## 최근 검증

- 전수검토 변경은 `6f26518` 위의 미커밋 작업 트리를 빈 `GRADLE_USER_HOME`에서
  `./gradlew --no-daemon --max-workers=2 check bootJar`로 검증했다. 일반 203개·구조 3개 테스트, 검사 스크립트 회귀 테스트
  18개와 `1.1.0-rc.3` 계약 ZIP 검증이 실패·제외 없이 새로 실행돼 통과했고 검증 메타데이터 누락은 없었다.
  Gradle 9.8.2·Kotlin 2.4.21·Java 25 toolchain·PostgreSQL 18.6(Testcontainers)·Docker 29.9.2·Compose 5.6.1. 같은 트리로
  `bootBuildImage --imageName=baton-cal:full-review`(`sha256:2bc42711ee2d902953af762ad5cc0fa44844e9c866ee54021a1400f6cd03c099`)
  뒤 `./scripts/smoke-oci-image.sh`, `./scripts/smoke-operations.sh`(공개 프록시 인코딩 공격, 리다이렉트 헤더 주입,
  잘못된 요청줄의 토큰 비노출 포함), `bash scripts/smoke-alert-channels.sh`(4개 조합)가 통과했다.
  `계약 릴리스 HEAD 검증`의 `workflow_dispatch`는 `actionlint` 1.7.12 정적 검사만 통과했고 실제 실행은 하지 않았다.
- 같은 이미지로 BATON `06fd981`의 교차 서비스 테스트 5개가 건너뜀 없이 통과했다. BATON 스크립트가 삭제된
  `compose.smoke.yml`과 `1.1.0-rc.2`만 허용하므로 두 저장소의 임시 복제본에 호환 파일을 넣어 실행했고 실제 저장소는 수정하지
  않았다. 복구·일정·구독·시즌 이름 아웃박스·백업 복원 뒤 재전달을 포함한다.
- `6f26518`에서 `ingestionLoadTest -PloadItemCount=1000`을 `-PloadBatchSize=1`과 `100`으로, `projectionLoadTest`를 5회
  실행했다. 개발 장비의 참고 측정이며 운영 성능 보장은 아니다. [측정 조건과 결과](docs/performance-baseline.md)
- 공백 판정은 입력 가능한 TZID 339개의 1970~2037년 공백 8,786건을 JVM 규칙과 대조했고, 차이는 내장 데이터가 다른
  `Europe/Chisinau`·`America/Tijuana`뿐이었다. 32만 회 병렬 호출에서 오판은 없었다. 일회 검증이며 상시 테스트에는
  대표 시각만 고정했다.
- PR #41 병합 `9cafd90`의 [main CI](https://github.com/ljkhyeong/baton-cal/actions/runs/38926931261)가 전체 테스트·이미지·
  스모크와 `docker/login-action` v4 로그인, `ghcr.io/ljkhyeong/baton-cal:9cafd90eb088347921d99f6e133e6a9dd34d3e5a` 게시까지 통과했다.
- `3ba5889`에서 `verifyContractsZip`을 통과하고 게시 후 `gh release verify`·`gh release verify-asset`으로
  `1.1.0-rc.2`의 릴리스·자산 증명을 검증했다. 태그 커밋과 SHA-256은 [릴리스 현황](docs/contract-release-history.md)에 기록했다.
- 2026-09-12 `curl -I https://cal.b4ton.com`은 로컬 네트워크의 DNS 조회 실패로 사용자 확인 내용을 직접 검증하지 못했다.
  실제 Google·Outlook 구독과 공개 HTTPS·운영 환경 검증은 실행하지 못했다.

결과를 재사용하기 전에 [개발 검증 절차](docs/development.md)에 따라 소스·테스트·설정·환경 차이를 확인한다.
과거 검증은 Git 이력을 참고한다.

## 남은 작업

외부 연동은 [추가 요금 없는 연동 기준](docs/external-api-options.md)을 따른다. 권장 방향으로 기존 구독의
앱별 등록 편의를 개선했다. 제공자 API 직접 연동은 보류하며 공휴일 활용은 BATON의 별도 작업이다.
`118577a` 기준 재검토에서도 새 제공자 API는 선정하지 않았다. BATON에는 공휴일 API 수집 코드가 있다.
CAL의 직접 HTTP 호출 부재와 기존 표준 연동을 유지하고 HTTPS 연결 입력을 보완했다.
남은 후보는 아래 조건이 정해지면 진행한다.

1. 실제 캘린더 앱·운영 연결 검증 후 `1.1.0` 안정 버전 승격을 판단한다. 저장소의 작업 후보 `1.1.0-rc.3`은
   기존 스키마·다이제스트·골든을 유지하고 전체 재생성·최근 조회일 경로와 DST 공백·입력 검증 정정을 담았다. 별도 게시할지
   안정 승격에 포함할지 그때 정한다. `1.0.x` 유지가 필요할 때만 `LICENSE`를 포함한 `1.0.1` 호환 보완판을 게시한다.
   이때 수동 실행으로 바꾼 `계약 릴리스 HEAD 검증`을 함께 확인한다. 이 작업은 main에 병합된 뒤에만 실행할 수 있어
   실제 실행은 아직 하지 않았다.
2. 실제 운영 환경에서 준비된 `cal.b4ton.com` DNS·인증서를 연결하고, 선택한 알림 채널의 웹훅을 등록한다.
   BATON에는 공개 구독 주소와 구분되는 사설 HTTPS 연결을 제공한다. CAL `tls` 사용 시 공개 프록시의
   상위 서버 프로토콜도 HTTPS로 맞춘다. 기존 HTTP Compose에 프로필만 추가해서는 연결되지 않는다.
   인증서 갱신·외부 점검·비밀 관리에 사용할 도구를 연결하고 내부 Bearer 회전과 프록시·추적의
   토큰 비노출을 검증한다. Healthchecks를 사용하면 첫 신호 수신과 누락·복구 알림을 실제 계정에서 확인한다.
   홈서버 설치·k3s 구축은 이번 연동 작업 범위에 포함하지 않는다. k3s 구성에서는 공개 경로 허용 목록, 실제 송신 IP,
   클러스터 DNS, 네트워크 분리, 컨테이너 권한 축소, TLS 설정, DB 계정 분리를 함께 정한다.
3. 실제 캘린더 앱에서 구독·이름 표시·갱신·취소를 확인한 뒤 BATON의 관련 기능과 이름 보정·전달을
   활성화한다. [앱별 확인표](docs/calendar-subscription-guide.md)
4. 운영 RTO/RPO·백업 저장소·암호화와 실제 복원 훈련을 정한다. 구독 세대 교체, 최신 재전달·완료 확인,
   복구 모드 해제 순서를 운영 절차에 연결한다. 배포 이미지 digest 고정도 적용한다.
5. 실제 수신량·제공자 공유 IP·로그 보존 정책에 맞춰 요청 제한과 알림을 조정한다. 데이터 규모와
   복구 목표 시간을 정한 뒤 BATON의 기존 단건 전달을 묶음 수신 API에 연결한다.
6. 시간대 데이터 갱신이 필요하면 iCal4j 의존성 잠금·골든 바이트·ETag를 검토한 새 계약 후보를 만들고, 운영 배포 뒤
   [전체 재생성](README.md#캘린더-출력-변경-배포)을 실행한다. iCal4j 4.4.0도 내장 데이터는 Olson `2025a`다.
7. BATON이 `1.1.0-rc.3` 이상을 고정한 뒤 구독 화면에서 최근 조회일 경로를 호출해 앱 등록·갱신 중단 안내를 연결한다.
   날짜가 이어서 갱신되는지로 판단하며 파일 가져오기의 한 번 조회와 구분한다.
8. BATON의 `ops/tests/calendar-consumer-contract.sh`는 CAL에서 삭제된 `compose.smoke.yml`과 `1.1.0-rc.2`만 허용해
   지금 저장소 그대로는 시작하지 못한다. BATON이 자체 Compose 정의를 갖고 새 버전을 허용하도록 고친다.
9. 새 Dependabot PR은 [의존성 절차](.claude/skills/baton-cal-dependencies/SKILL.md)로 처리한다. Gradle 생태계 PR은
   잠금 파일·검증 메타데이터를 함께 바꾸지 않아 CI가 실패하므로 깨끗한 Gradle 저장소에서 두 파일을 다시 쓴다.

## 현재 제한

- 통합 전 V1~V8이나 2026-10-16 이전 V1(`last_fetched_on` 추가 전)을 적용한 DB는 Flyway 체크섬 검증에 실패한다.
  로컬·테스트 DB만 해당하므로 볼륨을 지우고 다시 만든다.
- Codex 자동 훅의 실제 세션 실행은 미검증이다. `.codex/hooks.json`의 세 훅을 `/hooks`에서 검토·신뢰해야
  자동 실행된다. 신뢰 전에는 AGENTS.md의 수동 파일·종료 검사를 사용한다.
- Claude Code에는 저장소 훅을 연결하지 않았다. 파일·종료 검사는 수동으로 실행한다. Codex 전역 `baton-cal-flows`는
  저장소 스킬 파일을 읽는 안내만 두어 지침을 저장소에서만 관리한다. 전역 안내 파일은 Git으로 추적하지 않는다.
- 로컬 복원은 대표 데이터 검증이다. 실제 운영 전체 데이터·백업 저장소 복원이나 운영 준비 완료를 뜻하지 않는다.
- 복구 상태 GET은 저장된 기록을 반환한다. 최신 원본의 반영 여부를 다시 확인하거나 복구 모드를 해제하지 않는다.
- ID 지정 PUT의 응답 유실은 사전 저장한 ID로 조회할 수 있다. 기존 POST 생성과 rotate의 토큰 원문은
  다시 조회할 수 없으므로 구독 주소를 재발급해야 한다.
- 시간대 입력과 출력은 iCal4j 4.4.0 내장 Olson `2025a` 기준이다.
- 전체 재생성은 한 요청에서 동기로 처리하며 처리 시간이 전체 일정 수에 비례한다. 배포 중 이전 버전이 수신한
  시즌은 이전 출력으로 다시 저장될 수 있으므로 모든 인스턴스를 배포한 뒤 호출한다.
- 공개 조회는 최근 조회일 기록 문장을 한 번 더 실행한다. 같은 날에는 쓰지 않으며 측정한 지연 영향은 편차 안이었다.
  날짜는 UTC 기준이므로 화면에 표시할 때 시간대 차이를 고려한다.
- 내장 데이터가 JVM 최신 데이터와 다른 시간대가 있다. 공백 판정과 피드는 내장 `2025a`를 따르며 `Europe/Chisinau`의
  전환 시각과 `America/Tijuana`의 1970년대 기록이 이에 해당한다.
- 시간대 데이터 갱신으로 공백이 된 저장 일정은 거부되지 않고 피드에 남는다. 해당 시각의 표시는 캘린더 앱이 정한다.
- CI 산출물·공식 계약 릴리스·BATON의 계약 채택·운영 배포는 구분한다.
