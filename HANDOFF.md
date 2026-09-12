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
  V3의 투영 외래키 제약에 따라 캘린더 준비 후 구독을 저장하는 순서는 유지한다.
- 일정·구독·복구 상태 조회의 `404`, 상태 충돌의 `409`, 복구 중 발급 차단의 `503`에도
  `Cache-Control: no-store`를 적용한다. 상태가 바뀐 뒤 이전 오류 응답이 재사용되는 것을 막는다.
- 파일 작성 직후 검사와 종료 전 전체 diff·ArchUnit 검사를 추가했다. 기존 Spring Repository 주입 구조를
  유지하며 Controller의 DB 접근, 도메인의 실행 계층 의존, Service의 JDBC 사용·Repository 생성을 검사한다.
  실행 방법과 Codex 훅 신뢰 절차는 [개발 검증 절차](docs/development.md)를 따른다.
- BATON의 개인 구독·내 구독 목록·여러 구독 해제·앱별 등록 안내는 원격 main에 반영됐다.
  실제 캘린더 앱 검증과 운영 활성화는 남아 있다.
- CAL [PR #20](https://github.com/ljkhyeong/baton-cal/pull/20)은 `118577a`,
  BATON [PR #21](https://github.com/ljkhyeong/baton/pull/21)은 `c94469e`로 원격 main에 병합됐다.
  BATON 필수 CI와 브라우저 테스트 746개가 통과했다. 다른 작업 브랜치는 변경하지 않았다.
- 안정 계약은 `1.0.0`, 공식 후보는 `1.1.0-rc.2`다. `3ba5889`에서 게시한 불변 릴리스·ZIP 증명을
  검증하고 BATON의 버전·해시와 구독·복구 스키마 출처를 고정했다. 실제 앱 검증·운영 활성화와
  안정 버전 승격은 남아 있다. [릴리스 현황](docs/contract-release-history.md)
  게시된 `contracts/**`·루트 `LICENSE`·PRD-0002를 바꾸려면 다음 계약 버전으로 올린다.
- 기존 Alertmanager에 Slack·Discord 기본 연동을 추가했다. Blackbox Exporter는 `cal.b4ton.com`의
  인증서 이름·체인·만료 시각을 검사하며, 실패와 14일 이내 만료를 알린다.
  [외부 연동 검토](docs/external-api-options.md), [연결 방법](docs/operations.md#운영-알림-채널-연결)
- Slack·Discord와 Healthchecks를 함께 쓰는 완성된 조합을 추가했다. 채널 알림과 정상 신호를 분리하며
  설정을 직접 합칠 필요가 없다. BATON 연결용 내부 토큰은 32~200자, 영문·숫자와 `-._~`로 안내했다.
  CAL의 기존 Bearer 허용 범위는 유지한다. [설정 선택과 입력 기준](docs/integration-runtime.md)
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
- 사용자 확인 기준으로 Ubuntu 홈서버·DNS·공인 IP·80/443 포트포워딩·인증서는 준비됐고 k3s는 구축 전이다.
  로컬 연동만 검증했으며 실제 서버·DNS·인증서·운영 알림 채널을 변경하지 않았다.

## 최근 검증

- 후속 작업 기준은 `285e99a`다. 알림 조합·스모크와 안내만 바뀌었으며 제품·Kotlin 테스트·의존성·계약 입력은
  같아 기존 HTTPS 테스트와 JAR 빌드 결과를 재사용했다. 새 이미지 빌드·게시·운영 변경은 하지 않았다.
  `bash scripts/smoke-alert-channels.sh`로 Slack·Discord 단독 및 Healthchecks 조합 4개를 검증했다.
  장애·복구 메시지, 정상 신호의 수신 경로 분리와 URL 비노출이 통과했다. 각 조합은 새 모의 수신기와
  실제 Alertmanager를 외부 통신 차단 네트워크에서 사용하고 정리했다.
  로그: `/private/tmp/baton-cal-combined-webhooks.log`.
- TLS 추가 검증은 `118577a`에서 시작했다. 당시 TLS 프로필·통합 테스트 외 제품·의존성·계약 변경은 없다.
  `./gradlew --no-daemon --max-workers=2 test --tests 'io.baton.cal.config.TlsHttpIntegrationTest'
  --tests 'io.baton.cal.config.ProductionDatasourceConfigurationTest' bootJar`가 통과했다.
  Java 25.0.3·PostgreSQL 18.6에서 테스트 6개를 실행했고 Gradle 작업 3개 실행·5개 결과를 재사용했다.
  로그: `/private/tmp/baton-cal-integration-runtime-tests.log`. HTTPS 인증·구독 발급/조회/해제,
  관리 HTTP 포트 분리와 토큰 비노출을 확인했다. 새 OCI 이미지는 빌드하지 않았다.
- `bash scripts/smoke-alert-channels.sh`가 통과했다. 외부 통신 차단 네트워크에서 Slack·Discord의
  장애·복구 메시지와 웹훅 주소 비노출을 확인하고 임시 컨테이너를 정리했다.
  로그: `/private/tmp/baton-cal-integration-webhooks.log`. 실제 수신 채널에는 발송하지 않았다.
- 파일·종료 검사와 전체 `review.diff` 검토를 마쳤다. ArchUnit 3개를 실행해 통과했고 검사 스크립트의
  기존 성공 결과를 재사용했다. 기록: `build/agent-feedback/0cb1831a1bec612d/`.
- CAL 제품 기준 `81cd46a`, 병합 커밋 `3ba5889`의 제품·테스트·계약 입력은 같다.
  `./gradlew --no-daemon --max-workers=2 check`로 일반 164개·구조 3개 테스트와 계약 ZIP이 통과했다.
  최초 전체 실행의 입력 테스트 29건은 공유 PostgreSQL 연결 한도로 시작하지 못했다.
  테스트 전용 `minimum-idle=0`(`a8d2ac0`)으로 수정한 뒤 해당 29건부터 재시도하고 전체 검증을 통과했다.
  로그: `/private/tmp/baton-cal-batch-db-retry.log`, `/private/tmp/baton-cal-batch-check-final.log`.
  Java 25.0.3·PostgreSQL 18.6, 최종 `check`는 3개 작업 실행·7개 기존 결과 재사용이다.
- 같은 코드에서 `ingestionLoadTest -PloadItemCount=1000`을 `-PloadBatchSize=1`과 `100`으로 실행했다.
  두 실행 모두 최종 1,000개 UID·개정 2·취소 500개·동일 ETag를 확인했다. 최초 적재는 82.589초와
  1.843초였다. 로컬 단회 비교이며 운영 성능 보장은 아니다. [측정 조건과 결과](docs/performance-baseline.md)
  로그와 XML: `/private/tmp/baton-cal-batch-load-single.{log,xml}`, `/private/tmp/baton-cal-batch-load-100.log`.
- CAL [PR #19 CI](https://github.com/ljkhyeong/baton-cal/actions/runs/34675397849)와
  [`3ba5889` main CI](https://github.com/ljkhyeong/baton-cal/actions/runs/34675704171)가 통과했다.
  전체 테스트·계약 ZIP·OCI 이미지·HTTPS·운영 알림·채널 검증을 포함하며 main의 GHCR 이미지 게시도 완료했다.
  로그: `/private/tmp/baton-cal-rc2-pr-ci.log`, `/private/tmp/baton-cal-rc2-main-ci.log`.
- 로컬 `bootBuildImage --imageName=baton-cal:batch-rc2`가 통과했다.
  이미지 digest는 `sha256:89f514d33792f89595dd7c3d7a1f87f934ac908fa3b826229190b3ebad9c916c`이며
  로그는 `/private/tmp/baton-cal-batch-image.log`다. 이 이미지와 BATON `6c3e5f5d`에서 다음 명령으로
  교차 서비스 테스트 5개를 통과했다. 일정·구독·시즌 이름 아웃박스·백업 복원·재전달·완료 확인을 포함한다.
  최신 main의 Spring 변경을 합친 뒤 다시 실행했으며 실패·제외 없이 통과하고 격리 컨테이너를 정리했다.
  로그: `/private/tmp/baton-cal-rc2-merged-consumer.log`.

  ```shell
  BATON_CAL_REPOSITORY_ROOT=/Users/lim/devProject/personal/baton-cal \
  BATON_CAL_IMAGE=baton-cal:batch-rc2 bash ops/tests/calendar-consumer-contract.sh
  ```

- BATON `7a064bcd`의 버전·해시 변경 후
  `./gradlew --no-daemon --max-workers=2 :adapter-out-external:test --tests 'com.personal.baton.adapter.out.external.calendar.*'`
  테스트 29개가 통과했다. 공식 ZIP과 기존 스키마 9개의 바이트 일치, 스크립트 문법·전체 diff도 확인했다.
  이후 main 병합에서 관련 변경은 오류 안내·테스트 이름뿐이다. 병합 상태의 전체 회귀는 PR 필수 CI에서 확인한다.
  로그: `/private/tmp/baton-cal-rc2-pin-tests.log`.
- `3ba5889`에서 `verifyContractsZip`을 통과하고 게시 후 `gh release verify`·`gh release verify-asset`으로
  `1.1.0-rc.2`의 릴리스·자산 증명을 검증했다. 태그 커밋과 SHA-256은 [릴리스 현황](docs/contract-release-history.md)에 기록했다.
- 2026-09-12 `curl -I https://cal.b4ton.com`은 로컬 네트워크에서 DNS 조회에 실패했다.
  실제 Google·Outlook 구독과 공개 HTTPS·운영 환경 검증은 실행하지 못했다.

결과를 재사용하기 전에 [개발 검증 절차](docs/development.md)에 따라 소스·테스트·설정·환경 차이를 확인한다.
기존 일반 HTTP·계약 입력은 유지되므로 이번에는 추가한 TLS와 Secret 연결을 검증했다.
과거 검증은 Git 이력을 참고한다.

## 남은 작업

외부 연동은 [추가 요금 없는 연동 기준](docs/external-api-options.md)을 따른다. 권장 방향으로 기존 구독의
앱별 등록 편의를 개선했다. 제공자 API 직접 연동은 보류하며 공휴일 활용은 BATON의 별도 작업이다.
`118577a` 기준 재검토에서도 새 제공자 API는 선정하지 않았다. BATON에는 공휴일 API 수집 코드가 있다.
CAL의 직접 HTTP 호출 부재와 기존 표준 연동을 유지하고 HTTPS 연결 입력을 보완했다.
남은 후보는 아래 조건이 정해지면 진행한다.

1. 실제 캘린더 앱·운영 연결 검증 후 `1.1.0` 안정 버전 승격을 판단한다.
   `1.0.x` 유지가 필요할 때만 `LICENSE`를 포함한 `1.0.1` 호환 보완판을 게시한다.
2. 실제 운영 환경에서 준비된 `cal.b4ton.com` DNS·인증서를 연결하고, 선택한 알림 채널의 웹훅을 등록한다.
   BATON에는 공개 구독 주소와 구분되는 사설 HTTPS 연결을 제공한다. CAL `tls` 사용 시 공개 프록시의
   상위 서버 프로토콜도 HTTPS로 맞춘다. 기존 HTTP Compose에 프로필만 추가해서는 연결되지 않는다.
   인증서 갱신·외부 점검·비밀 관리에 사용할 도구를 연결하고 내부 Bearer 회전과 프록시·추적의
   토큰 비노출을 검증한다. Healthchecks를 사용하면 첫 신호 수신과 누락·복구 알림을 실제 계정에서 확인한다.
   홈서버 설치·k3s 구축은 이번 연동 작업 범위에 포함하지 않는다.
3. 실제 캘린더 앱에서 구독·이름 표시·갱신·취소를 확인한 뒤 BATON의 관련 기능과 이름 보정·전달을
   활성화한다. [앱별 확인표](docs/calendar-subscription-guide.md)
4. 운영 RTO/RPO·백업 저장소·암호화와 실제 복원 훈련을 정한다. 구독 세대 교체, 최신 재전달·완료 확인,
   복구 모드 해제 순서를 운영 절차에 연결한다. 배포 이미지 digest 고정과 builder 갱신도 적용한다.
5. 실제 수신량·제공자 공유 IP·로그 보존 정책에 맞춰 요청 제한과 알림을 조정한다. 데이터 규모와
   복구 목표 시간을 정한 뒤 BATON의 기존 단건 전달을 묶음 수신 API에 연결한다.
6. 시간대 데이터 갱신이 필요하면 iCal4j 의존성 잠금·골든 바이트·ETag를 검토한 새 계약 후보를 만든다.

## 현재 제한

- Codex 자동 훅의 실제 세션 실행은 미검증이다. `.codex/hooks.json`의 세 훅을 `/hooks`에서 검토·신뢰해야
  자동 실행된다. 신뢰 전에는 AGENTS.md의 수동 파일·종료 검사를 사용한다.
- 로컬 복원은 대표 데이터 검증이다. 실제 운영 전체 데이터·백업 저장소 복원이나 운영 준비 완료를 뜻하지 않는다.
- 복구 상태 GET은 저장된 기록을 반환한다. 최신 원본의 반영 여부를 다시 확인하거나 복구 모드를 해제하지 않는다.
- ID 지정 PUT의 응답 유실은 사전 저장한 ID로 조회할 수 있다. 기존 POST 생성과 rotate의 토큰 원문은
  다시 조회할 수 없으므로 구독 주소를 재발급해야 한다.
- 시간대 입력과 출력은 iCal4j 4.3.0 내장 Olson `2025a` 기준이다.
- CI 산출물·공식 계약 릴리스·BATON의 계약 채택·운영 배포는 구분한다.
