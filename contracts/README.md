# BATON CAL MVP 계약

이 디렉터리는 PRD-0002의 언어 중립적 JSON 계약과 예시를 보관한다. 안정 버전 `1.0.0`의 BATON
생산자 계약 검증은 완료했지만, 현재 작업 후보의 검증 완료나 실제 운영 준비 완료를 뜻하지 않는다.

## 계약 목록

| 경로 | 요청 스키마 | 성공 응답 스키마 | 예시 |
| --- | --- | --- | --- |
| `POST /internal/api/v1/schedule-snapshots` | `schemas/schedule-snapshot.v1.schema.json` | `schemas/schedule-snapshot-result.v1.schema.json` | `examples/schedule-snapshot.*.json`, `examples/schedule-snapshot-result.*.json` |
| `GET /internal/api/v1/calendar-items/{sourceItemId}` | 본문 없음 | `schemas/calendar-item-status.v1.schema.json` | `examples/calendar-item-status.cancelled.json` |
| `GET /internal/api/v1/subscriptions/{subscriptionId}` | 본문 없음 | `schemas/subscription-status.v1.schema.json` | `examples/subscription-status.generation-mismatch.json` |
| `GET /internal/api/v1/recovery-runs/{recoveryId}` | 본문 없음 | `schemas/recovery-run-status.v1.schema.json` | `examples/recovery-run-status.*.json` |
| `GET /internal/api/v1/seasons/{seasonId}/recovery-state` | 본문 없음 | `schemas/recovery-season-state.v1.schema.json` | `examples/recovery-season-state.zoned-cancelled.json` |
| `PUT /internal/api/v1/seasons/{seasonId}/calendar-metadata` | `schemas/season-calendar-metadata.v1.schema.json` | `schemas/season-calendar-metadata-result.v1.schema.json` | `examples/season-calendar-metadata.*.json`, `examples/season-calendar-metadata-result.json` |
| `PUT /internal/api/v1/recovery-runs/{recoveryId}/seasons/{seasonId}/manifest` | `schemas/recovery-season-manifest.v1.schema.json` | `schemas/recovery-season-manifest-result.v1.schema.json` | `examples/recovery-season-manifest.json`, `examples/recovery-season-manifest-result.json` |
| `PUT /internal/api/v1/recovery-runs/{recoveryId}/completion` | `schemas/recovery-run-completion.v1.schema.json` | `schemas/recovery-run-completion-result.v1.schema.json` | `examples/recovery-run-completion.json`, `examples/recovery-run-completion-result.json` |
| `POST /internal/api/v1/subscriptions` | `schemas/subscription-create.v1.schema.json` | `schemas/subscription-credential.v1.schema.json` | `examples/subscription-create.json`, `examples/subscription-credential.json` |
| `PUT /internal/api/v1/subscriptions/{subscriptionId}` | `schemas/subscription-create.v1.schema.json` | `schemas/subscription-credential.v1.schema.json` | 같은 생성 예시와 `examples/api-error.subscription-*.json` |
| `POST /internal/api/v1/subscriptions/{subscriptionId}/rotate` | 본문 없음 | `schemas/subscription-credential.v1.schema.json` | `examples/subscription-credential.json` |
| `POST /internal/api/v1/projections/seasons/{seasonId}/rebuild` | 본문 없음 | `schemas/projection-rebuild-result.v1.schema.json` | `examples/projection-rebuild-result.json` |
| 내부 오류 | - | `schemas/api-error.v1.schema.json` | `examples/api-error.source-revision-conflict.json`, `examples/api-error.service-busy.json`, `examples/api-error.recovery-in-progress.json` |

`DELETE /internal/api/v1/subscriptions/{subscriptionId}`는 요청/응답 본문이 없고 `204`를
반환한다. `GET /calendars/v1/{token}.ics`는 JSON이 아니라 PRD-0002의 정규
`text/calendar` 계약을 따른다.

일정·구독 상태 조회는 Bearer 인증을 요구하고 성공 응답에 `Cache-Control: no-store`를 사용한다.
일정 조회는 CAL이 채택한
개정 번호·상태·원본 수정 시각을, 구독 조회는 저장된 상태와 현재 인스턴스의 구독 세대 일치 여부를
반환한다. 취소 일정·폐기 구독·세대 불일치 구독도 조회할 수 있다. 토큰·해시·피드 URL·세대 UUID는
반환하지 않으며 전체 재전달 완료를 증명하거나 유실된 자격 증명을 복구하지 않는다. 세부 의미와
오류는 PRD-0002의 각 경로 계약을 따른다.

복구 실행 조회는 저장된 `IN_PROGRESS`·`COMPLETED`, 검증한 시즌 수·최초 완료 시각과 현재
인스턴스의 복구 모드를 반환한다. 시즌 진단은 현재 일정 수·다이제스트와 이름 개정·다이제스트를
반환하며 이름 미수신은 두 필드가 모두 `null`이다. 기록이 없는 복구 ID 또는 일정·이름이 모두
없는 시즌은 `404 RESOURCE_NOT_FOUND`다. 두 경로는 일반·복구 모드에서 쓰기 없이 동작한다.
완료 기록은 과거 대조 결과이며, 현재 CAL 진단 값을 BATON의 기대 매니페스트로 대신 사용하지 않는다.

ID 지정 생성은 BATON이 사전에 저장한 구독 ID를 경로에 사용한다. 최초 `201`에서만 토큰을 반환하고,
같은 ID·시즌은 `409 SUBSCRIPTION_ALREADY_EXISTS`, 다른 시즌은 `409 SUBSCRIPTION_SCOPE_CONFLICT`다.
재전달은 기존 토큰·상태·세대를 바꾸지 않는다. 응답 유실 뒤에는 알고 있는 ID로 상태를 조회하고,
활성 구독의 자격 증명이 없으면 사용자 요청에 따라 rotate로 재발급한다. 상태 GET의 `404` 뒤에도
같은 ID·시즌으로만 PUT을 재전달한다. 기존 POST는 응답 유실 시 ID를 찾을 수 없는 제한을 유지한다.
모든 CAL 인스턴스가 새 PUT을 지원하고 BATON이 새 계약을 고정한 뒤 이 경로를 활성화한다.

시즌 표시 이름은 `{revision, displayName}`으로 전달한다. 개정 번호는 일정과 별도로 관리하며,
응답은 CAL이 채택한 `{seasonId, revision, displayName}`이다. 같은 이름·개정 번호의 중복과 낮은
개정 번호는 저장하지 않고 현재 값을 반환한다. 현재 개정 번호에 다른 이름을 보내면
`409 SEASON_METADATA_REVISION_CONFLICT`다. 이름 미수신 시즌은 기존 `BATON season {seasonId}`를
유지한다. 이름을 받으면 iCal4j로 `X-WR-CALNAME`에 반영하며 일정 UID·SEQUENCE와 토큰은 바꾸지 않는다.

복구 모드의 시즌 매니페스트는 현재 일정 항목의 수·다이제스트와 선택적인 시즌 이름 개정·
다이제스트를 대조한다. 전체 완료 요청은 검증한 시즌 수·전체 다이제스트가 현재 CAL 데이터의 시즌
집합 및 상태와 정확히 같을 때만 `COMPLETED`를 반환한다. 완료 전 시즌 매니페스트는 최신 상태로
다시 검증할 수 있고, 완료 뒤에는 같은 내용만 멱등하게 조회할 수 있다. 불일치는
`409 RECOVERY_MANIFEST_MISMATCH`, 같은 복구 ID의 완료 내용 변경은 `409 RECOVERY_RUN_CONFLICT`,
일반 모드의 새 검증은 `409 RECOVERY_MODE_REQUIRED`다.

`recovery-season-manifest.zoned-cancelled.json`과 `recovery-run-completion.zoned-cancelled.json`은
`schedule-snapshot.zoned-cancelled.json` 한 항목과 `season-calendar-metadata.r2.json`의 고정된
기대 다이제스트다. OCI 복원 스모크와 HTTP 테스트가 이 값을 그대로 대조하며 DB의 현재 상태에서
정답을 다시 생성하지 않는다. 다이제스트를 자동 갱신하지 않고 원본 픽스처와 계약 변경을 함께 검토한다.

## 기준과 버전 관리

- JSON 구조, 필수 필드, 열거형과 길이 제한은 스키마가 정식 기준이다. null을 허용하는 선택 필드인
  `description`과 `location`은 생략과 명시적인 `null`을 같은 의미로 처리한다.
- 필드 간 비교, 개정 번호 순서, 멱등성, HTTP 상태, 토큰과 iCalendar 의미는
  PRD-0002가 정식 기준이다.
- 예시는 스키마와 의미를 설명하지만 새로운 규칙을 만들지 않는다.
- `schedule-snapshot.zoned-active-r0.json`, `schedule-snapshot.zoned-active-r2.json`,
  `schedule-snapshot.zoned-cancelled.json`, `schedule-snapshot.zoned-reactivated.json`은 한 항목의
  `ACTIVE` 개정 번호 0, 개정 번호 간격이 있는 `ACTIVE` 개정 번호 2, `CANCELLED` 개정 번호 3,
  다시 활성화된 `ACTIVE` 개정 번호 4 생명주기를 구성한다.
- `schedule-snapshot.utc-point-active.json`, `schedule-snapshot.zoned-point-active.json`,
  `schedule-snapshot.all-day-active.json`은 임의 지속 시간을 만들지 않는 UTC·시간대 지정 시점과
  `VALUE=DATE`를 사용하는 종일 날짜 구간을 설명한다.
- 소비자는 `additionalProperties: false`를 전제로 한다. 필드나 열거형을 추가하려면 새 스키마
  버전과 생산자·소비자 계약 픽스처가 필요하다.
- v1 절대 시각은 명시적 오프셋이 있는 RFC 3339이고 로컬 날짜·시간은 오프셋 없이 보낸다.
  초는 00부터 59까지, 소수는 최대 9자리만 허용한다. CAL은 타임스탬프를 마이크로초 정밀도로
  정규화하고 iCalendar DATE-TIME은 초 단위로 내림한다.
- JSON Schema만으로 표현하지 못하는 구간의 `end > start`, NFC, iCal4j 4.3.0에 내장된 Olson
  `2025a`의 원본 시간대 식별자,
  항목별 개정 번호·타임스탬프 순서와 자격 증명 응답의 `feedUrl` 경로 토큰 = `token`
  관계는 애플리케이션 검증 대상이다.

## JSON 문서 자원 경계

JSON Schema와 DTO의 길이·형식 제약은 파싱된 개별 필드 값을 검증한다. 이와 별도로 CAL의 JSON
파서는 공백과 구조를 포함한 전체 요청 문서를 128 KiB(131,072바이트), 필드명을 64자, 중첩을
16단계, 숫자를 10자리, 토큰을 256개로 제한한다. 이 상한은 JSON Schema에 표현하는 필드 제약이
아니라, DTO를 만들기 전에 Jackson이 적용하는 자원 경계다.

어느 자원 상한이든 넘으면 필드 값의 유효성과 관계없이 `413`과 다음 고정 `api-error.v1` 응답을
반환한다. 같은 요청을 그대로 재시도하지 않고 생산자 직렬화 또는 요청 크기를 먼저 고친다.

```json
{"code":"REQUEST_TOO_LARGE","message":"request body exceeds the maximum size"}
```

## 자동 검증과 BATON 연동

`ContractSchemaSupport`가 Draft 2020-12 스키마 로딩과 검증 결과 보고를 한 곳에서 맡는다.
`ContractArtifactsTest`는 이를 사용해 Docker 없이 모든 JSON 예시를 검증하고, UUID, RFC 3339
시각과 URI의 `format`도 단순 주석이 아니라 검증 조건으로 평가한다. 내부 HTTP 요청의 UUID도
하이픈을 포함한 36자 표준 문자열만 허용하며 Jackson의 22자·24자 Base64 UUID 표현은 거부한다.
일정 생명주기 예시는 실제 CAL 내부 HTTP 경로에서 개정·취소·재활성화·정확한 재전달 순서로
검증하고, 최종 피드의 같은 UID가
`SEQUENCE:4`, `STATUS:CONFIRMED`로 복원되는지 확인한다. 세 시점·종일 예시도 같은 수신 경로로
실행한다. 기존 전체 HTTP 흐름을 실행하는 `MvpHttpFlowTest`는 같은 스키마 지원 코드를 재사용해
MockMvc의 일정 수신 결과, 일정·구독 상태 조회, 구독 생성·회전, 투영 재구축과 공통 오류 응답 JSON을
각 응답 스키마에 직접 대조한다. 따라서 별도 Spring 테스트 컨텍스트나 중복 시나리오를 만들지 않으면서, 예제가
유효하더라도 실제 직렬화 결과에 필드가 빠지거나 예고 없이 추가되면 계약 검증이 실패한다. 예상 밖
`500`은 고정 오류 응답을 반환하고 예외 메시지의 비밀값을 응답과 애플리케이션 로그에 남기지 않는지
별도 MVC 회귀 테스트로 확인한다.

`SeasonCalendarMetadataHttpTest`는 이름의 수신·변경·중복·역순·충돌 응답을 실제 MVC 경로로 확인하고
성공 응답을 `season-calendar-metadata-result.v1`에 대조한다. 피드 재구축과 이름만 같은 개정 전진이
바이트·ETag·Last-Modified를 유지하는지도 검증한다.

데이터베이스 잠금 획득, SQL 실행 또는 Spring 트랜잭션이 설정된 제한 시간을 넘으면 CAL은
`503 SERVICE_BUSY`와 `Retry-After: 1`을 반환한다. 스냅샷 전달자는 같은 요청을 즉시 반복하지 않고
`Retry-After` 이후 재시도한다. 스냅샷 수신의 이벤트 식별자와 원본 개정 번호 계약은 이 재시도가
중복으로 도착해도 같은 결과를 보장한다.

`BATON_CAL_RECOVERY_MODE=true`이면 구독 생성·회전은 `503 RECOVERY_IN_PROGRESS`를 반환하고
토큰을 발급하지 않는다. 일정·시즌 이름 수신·상태 조회·재구축·폐기와 공개 조회는 기존 계약을 유지한다.
복구 중 완료 시각을 미리 알 수 없으므로 `Retry-After`는 없으며, 운영자가 전체 완료 응답을
확인하고 모드를 해제한 뒤에만 생성·회전을 다시 요청한다. POST 생성·회전은 응답 유실로 자동
재시도하지 않는다. ID 지정 PUT 재전달은 위 복구 절차를 따르며 같은 토큰을 다시 반환하지 않는다.
이 오류도 기존 `api-error.v1` 구조를 사용하며 실제 HTTP
응답을 같은 스키마에 검증한다.

이 검증은 CAL 계약 팩 자체와 CAL 소비자 구현의 일치를 증명한다. 안정 버전 `1.0.0`은 사전 릴리스
`1.0.0-rc.2`를 고정한 BATON 생산자 테스트와 실제 CAL 컨테이너 교차 서비스 테스트로 운영
직렬화기, `sourceItemId` 비재사용, 원본 트랜잭션 커밋 이후 발행, 변경·취소·중복·역순 전달을
검증했다. 현재 작업 후보는 별도로 게시하고 BATON에서 다시 검증해야 한다.

## 계약 팩 생성과 배포

계약 버전은 `contracts/VERSION`에서 관리하며 현재 작업 후보는 `1.1.0-rc.2`이다. 계약 팩은
Gradle 표준 `Zip` 작업으로 생성하고 실제 산출물을 검증한다.

```shell
./gradlew --no-daemon verifyContractsZip
```

`build/distributions/baton-cal-contracts-1.1.0-rc.2.zip`에는 다음 기준만 들어간다.

- `LICENSE`: 계약 팩 복제·재배포 조건을 설명하는 MIT 라이선스
- `contracts/**`: JSON Schema, 예시, 정규 iCalendar 골든과 이 안내서
- `docs/PRD/0002_mvp-contract/spec.md`: JSON Schema로 표현하지 못하는 필드 간 의미, HTTP 상태,
  토큰과 iCalendar 규칙

`contractsZip`은 파일 시각을 보존하지 않고 재현 가능한 항목 순서와 디렉터리 `0755`·파일 `0644`
권한을 명시해 같은 입력에서 같은 ZIP 바이트를 만든다. `verifyContractsZip`은 생성된 ZIP의 파일명,
내부 버전과 포함 파일 목록이 소스와 같은지 확인한다. 계약을 JVM 구현에 결합하는 공유 DTO JAR은
만들지 않는다. 압축, 체크섬이나 매니페스트도 별도 코드로 구현하지 않고 Gradle의 아카이브 기능과
GitHub Actions가 제공하는 artifact digest를 사용한다.

ZIP 내부의 `contracts/VERSION`, 파일명과 후보 태그 `contracts-v1.1.0-rc.2`는 모두 같은 버전을
가리켜야 한다. 안정 버전은 새 ZIP과 태그로 게시하며, 게시된 RC 파일과 태그를 덮어쓰지 않는다.

GitHub Actions는 단일 ZIP을 `upload-artifact`로 올리고 `retention-days: 90`으로 보존을 요청한다.
실제 만료 시점은 저장소·조직의 보존 정책을 따르며, 이 파일은 변경 검토와 다운로드 확인을 위한
임시 CI 산출물이므로 BATON이 고정할 안정적인 의존성이 아니다.

현재 BATON 생산자 기준은 [불변 안정 릴리스 `contracts-v1.0.0`](https://github.com/ljkhyeong/baton-cal/releases/tag/contracts-v1.0.0)이다.
이 자산에는 루트 `LICENSE`가 없어 계약 의미를 유지한 `1.0.1` 호환 보완판으로 재포장하고 BATON이
새 태그·자산·SHA-256을 다시 고정할 예정이다.
`1.1.0-rc.1`의 게시·BATON 생산자 검증은 완료했다. 현재 작업 후보 `1.1.0-rc.2`는 ID 지정 구독 생성,
중복·시즌 충돌 응답과 고정된 복원 다이제스트 예시를 추가한다. `rc.2`는 미게시·BATON 미고정 상태이며
기존 안정 기준은 `1.0.0`이다.
게시·검증 이력과 절차는
[계약 릴리스 현황](https://github.com/ljkhyeong/baton-cal/blob/main/docs/contract-release-history.md)과
[계약 릴리스 절차](https://github.com/ljkhyeong/baton-cal/blob/main/docs/contract-release-procedure.md)가 관리한다.

## 비밀정보 처리

`subscription-credential` 예시의 피드 URL은 형식 설명용 가짜 값이다. 실제 생성·회전
응답 전체는 비밀정보로 취급하고 저장, 픽스처 캡처, 로그, 추적과 메트릭 레이블에서
제외한다.

## 골든 캘린더

아래 골든 파일은 고정된 iCal4j 버전으로 생성한 기준 `.ics` 바이트를 Base64로 보관한다.
테스트는 픽스처를 다시 만들거나 덮어쓰지 않고 디코딩한 뒤 CRLF와 마지막 CRLF까지 바이트 단위로
비교한다.

- `golden/season-utc.ics.b64`: UTC 활성 항목, TEXT 이스케이프 처리와 안정적 식별자
- `golden/season-empty.ics.b64`: `VEVENT`와 `VTIMEZONE`이 없는 빈 시즌 피드
- `golden/season-zoned-midnight-cancellation.ics.b64`: `America/New_York` DST 종료와 자정을
  통과하는 시간대 지정 로컬 취소 표식
- `golden/season-unicode-fold-boundaries.ics.b64`: TEXT 이스케이프와 4바이트 Unicode 문자가
  iCal4j 줄 접기 경계에 놓이는 UTC 활성 항목
- `golden/season-point-and-all-day.ics.b64`: `DTEND`가 없는 UTC 시점과 `VALUE=DATE`인 종일 날짜 구간

iCal4j 4.3.0은 내장 Olson `2025a`를 시간대 지정 입력 검증과 `VTIMEZONE` 출력의 단일 권위로 쓴다.
iCal4j 또는 시간대 데이터를 올려 픽스처 바이트가 바뀌면 자동 갱신하지 않고 변경점과 캘린더
호환성 영향을 먼저 검토한다.

## 의존성과 골든 갱신 절차

Spring Boot, Kotlin, iCal4j 또는 JSON Schema 검증기 버전은 한 종류씩 올린다. 의존성을 바꾼 뒤
잠금 파일을 갱신하고 계약·캘린더 테스트를 먼저 실행한다.

```shell
BATON_CAL_GRADLE_HOME="$(mktemp -d /tmp/baton-cal-gradle.XXXXXX)"
GRADLE_USER_HOME="$BATON_CAL_GRADLE_HOME" ./gradlew --no-daemon \
  --write-locks --write-verification-metadata sha256 help dependencies
git diff -- gradle.lockfile gradle/verification-metadata.xml
./gradlew --no-daemon test --tests io.baton.cal.contract.ContractArtifactsTest
./gradlew --no-daemon test --tests io.baton.cal.calendar.IcsCalendarRendererTest
```

잠금 파일은 선택된 버전을, 검증 메타데이터는 실제로 내려받은 플러그인과 의존성 파일의 SHA-256을
고정한다. 깨끗한 Gradle 저장소와 `help` 작업을 함께 사용해 기존 로컬 캐시에 가려질 수 있는 빌드
플러그인 메타데이터까지 기록한다. 두 파일의 변경이 의도한 의존성과 전이 의존성에만 해당하는지
확인한 뒤 테스트한다.

골든이 달라지면 테스트에서 자동 덮어쓰지 않는다. 후보 `.ics`를 임시 위치에 생성해 속성·컴포넌트
순서, TEXT 재파싱 결과, TZID·VTIMEZONE, UTF-8 물리 줄 길이와 ETag 변화를 검토한다. 의도한
호환성 변화만 Base64 골든에 반영한 뒤 전체 테스트와 실행 JAR 생성을 검증한다.

```shell
./gradlew --no-daemon test
./gradlew --no-daemon bootJar
```
