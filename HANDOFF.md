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
- CAL 경로는 `/Users/lim/devProject/personal/baton-cal`이다. BATON 작업 경로는
  `/Users/lim/devProject/personal/manager`에서 `git worktree list`로 확인한다.
  이전 `/private/tmp/baton-cal-registration-20260907`은 Git 작업 트리 정보가 삭제되어 작업 경로로 사용하지 않는다.
- 안정 계약은 `1.0.0`, 게시된 후보는 `1.1.0-rc.1`, 현재 작업 후보는 `1.1.0-rc.2`다.
  [rc.2 릴리스 노트](docs/releases/contracts-v1.1.0-rc.2.md)를 작성했다. 게시·BATON 사용 버전 지정·
  안정 버전 승격은 남아 있다. [릴리스 현황](docs/contract-release-history.md)
- 기존 Alertmanager에 Slack·Discord 기본 연동을 추가했다. Blackbox Exporter는 `cal.b4ton.com`의
  인증서 이름·체인·만료 시각을 검사하며, 실패와 14일 이내 만료를 알린다.
  [외부 연동 검토](docs/external-api-options.md), [연결 방법](docs/operations.md#운영-알림-채널-연결)
- Blackbox의 공개 경로 점검을 추가했다. 발급될 수 없는 고정 주소의 빈 `404`로 프록시→CAL 연결을
  확인하고, HTML 오류 페이지·연결 장애가 30초 지속되면 알린다. 실제 구독 토큰은 사용하지 않는다.
  [점검 범위](docs/operations.md#모니터링과-알림)
- 서버·모니터링 중단을 확인할 Healthchecks.io 선택 연동을 추가했다. Prometheus 정상 신호를
  Alertmanager가 외부로 전송하며, 기본 알림 채널에서는 이 신호를 제외한다. 무료 점검 1개를 쓰는
  설정이고 실제 계정·수신 채널은 아직 연결하지 않았다. [연결 방법](docs/operations.md#서버와-모니터링-중단-감지)
- Spring Boot `configtree`로 DB 비밀번호·현재 및 이전 내부 토큰을 Secret 파일에서 읽는 경로를 검증했다.
  제품 코드·의존성 추가 없이 기존 운영 설정에 연결한다. 파일 이름과 재시작 기준은
  [Secret 파일 연결](docs/operations.md#secret-파일-연결)을 따른다. 실제 k3s 마운트는 배포 시 적용한다.
- 사용자 확인 기준으로 Ubuntu 홈서버·DNS·공인 IP·80/443 포트포워딩·인증서는 준비됐고 k3s는 구축 전이다.
  로컬 연동만 검증했으며 실제 서버·DNS·인증서·운영 알림 채널을 변경하지 않았다.

## 최근 검증

- 2026-09-12 묶음 수신: `81cd46a`에서 HTTP 회귀 11개, 전체 일반 164개·구조 3개 테스트와 계약 ZIP이
  통과했다. 최초 전체 실행은 공유 DB의 연결 한도 때문에 입력 테스트 컨텍스트 29건이 시작하지 못했다.
  테스트 전용 유휴 연결 설정(`a8d2ac0`)으로 원인을 고친 뒤 해당 29건과 전체 검증을 통과했다.
  로그: `/private/tmp/baton-cal-batch-retry.log`, `/private/tmp/baton-cal-batch-db-retry.log`,
  `/private/tmp/baton-cal-batch-check-final.log`. 최종 `check`는 3개 작업 실행·7개 기존 결과 재사용이다.
  같은 코드의 `ingestionLoadTest -PloadItemCount=1000`을 `-PloadBatchSize=1`과 `100`으로 실행했다.
  두 실행 모두 성공했고 최종 1,000개 UID·개정 2·취소 500개·동일 ETag를 확인했다.
  로그와 XML: `/private/tmp/baton-cal-batch-load-single.{log,xml}`, `/private/tmp/baton-cal-batch-load-100.log`.
  파일·구조 검사와 전체 diff를 검토했다. 이후에는 검증 기록만 수정했다.

- 2026-09-12 문자열 입력: 기준 `8e2c7fb` 이후 설정·테스트·계약 변경을 `b417560`으로 커밋했다.
  `./gradlew --no-daemon --max-workers=2 test --tests 'io.baton.cal.web.SnapshotInputContractTest'
  --tests 'io.baton.cal.web.SeasonCalendarMetadataHttpTest'`로 문자열 필드의 숫자·불리언 입력 12건이
  기존에 `200`으로 처리되는 것을 재현했다. 수정 후 관련 34개 테스트가 통과했고 오류 응답·미저장·
  정상 문자열 재요청·Unicode·줄바꿈·선택 필드와 기존 정수 입력 규칙을 확인했다.
  `./gradlew --no-daemon --max-workers=2 check`는 일반 153개·구조 3개 테스트와 계약 ZIP을 통과했다.
  Java 25·Spring Boot 4.1.1·Jackson 3.1.5·PostgreSQL 18.6, 실패·제외 없음. 4개 작업을 새로 실행하고
  검사 스크립트 11개를 포함한 6개 작업은 Gradle의 기존 성공 결과를 재사용했다.
  로그: `/private/tmp/baton-cal-text-types-before.log`, `/private/tmp/baton-cal-text-types-after.log`,
  `/private/tmp/baton-cal-text-types-check.log`. 파일·구조 검사와 전체 diff 검토도 통과했다.
  이후에는 검증 기록만 수정했다. HTTP JSON 바인딩 설정만 바꿨으며 DB 처리·배포 구성은 같다.
  변경 이미지 빌드·운영 스모크는 미실행이며 새 CI는 미푸시로 실행하지 않았다.
- 2026-09-12 공개 경로: 기준 `c6e5615` 이후 운영 설정·검증 스크립트를 `c85312d`로 커밋했다.
  `bash scripts/smoke-operations.sh baton-cal:external-integrations`로 빈 `404`·프록시 HTML `404` 구분,
  공개 경로·준비 상태의 장애 발생·복구 알림, 규칙 8개와 기존 HTTPS·TLS·정상 신호 중단·재개·토큰
  비노출을 확인했다. 모두 통과하고 격리 자원을 정리했다. 로그: `/private/tmp/baton-cal-public-route-smoke.log`.
  제품 코드·의존성이 같아 기존 OCI 이미지와 일반 테스트 결과를 재사용했다. 채널 설정·스크립트도 같아
  `13737a7`의 `bash scripts/smoke-alert-channels.sh` 성공 결과를 재사용했다.
  채널 로그: `/private/tmp/baton-cal-healthchecks-channels.log`. 종료 파일·구조 검사와 전체 diff 검토도 통과했다.
  동작 검증 이후에는 문서만 변경했다. 실제 공인 DNS·인입 HTTPS·수신 채널·k3s 연결은 미검증이며 새 CI는
  미푸시로 실행하지 않았다. 정상 신호의 전송 검사 간격 10초와 반복 기준 1분은 유지한다.
- CAL `3c2936d`: [필수 CI](https://github.com/ljkhyeong/baton-cal/actions/runs/34172597027) 통과.
  전체 테스트·계약 ZIP·OCI 이미지·운영 스모크를 포함한다. 서버 중단 후 프록시 응답은 연결 거부 시
  `502`, 연결 시간 초과 시 `504`를 허용하며, 알림 발생·해제와 토큰 비노출을 확인했다.
- BATON `0861b040`의 이전 검증 기록은 `/private/tmp/baton-cal-registration-20260907/output/verification/latest.md`에 있다.
  현재 BATON 검증 결과로 재사용하려면 변경 파일과 실행 환경을 먼저 비교한다.
  이전 코드 검토는 [표준 API 검토](docs/reviews/2026-09-05-standard-api-review.md), 과거 검증은 Git 기록을 참고한다.

결과를 재사용하기 전에 [개발 검증 절차](docs/development.md)에 따라 소스·테스트·설정·환경 차이를 확인한다.

## 남은 작업

외부 연동은 [추가 요금 없는 연동 기준](docs/external-api-options.md)을 따른다. 권장 방향으로 기존 구독의
앱별 등록 편의를 개선했다. 제공자 API 직접 연동은 보류하며 공휴일 활용은 BATON의 별도 작업이다.
`5b7e0c1` 기준 재검토에서는 추가할 새 API를 찾지 못했다. 제품의 직접 HTTP 호출 부재, Dependabot 주간
설정과 iCal4j의 시간대 자동 갱신 기본값 `false`를 확인했다. 외부 연동 검토는 `81b2e99`에 기록했고,
이후 제품 수정의 검증은 위 최근 결과를 따른다. 남은 후보는 아래 조건이 정해지면 진행한다.

1. 작성한 `1.1.0-rc.2` 릴리스 노트를 게시할 커밋과 대조한 뒤 게시한다. BATON에서 공식 ZIP과 릴리스 증명을 검증한 뒤
   사용할 버전·해시를 지정한다. 연동 검증 후 안정 버전을 게시한다.
   `1.0.x` 유지가 필요하면 `LICENSE`를 포함한 `1.0.1` 호환 보완판도 게시한다.
2. 실제 운영 환경에서 준비된 `cal.b4ton.com` DNS·인증서를 연결하고, 선택한 알림 채널의 웹훅을 등록한다.
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
