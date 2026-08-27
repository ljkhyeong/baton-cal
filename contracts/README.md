# BATON CAL MVP 계약

이 디렉터리는 PRD-0002의 언어 중립적 JSON 계약과 예시를 보관한다. BATON 생산자 계약 검증은
완료했지만 실제 운영 활성화나 운영 준비 완료를 뜻하지 않는다.

## 계약 목록

| 경로 | 요청 스키마 | 성공 응답 스키마 | 예시 |
| --- | --- | --- | --- |
| `POST /internal/api/v1/schedule-snapshots` | `schemas/schedule-snapshot.v1.schema.json` | `schemas/schedule-snapshot-result.v1.schema.json` | `examples/schedule-snapshot.*.json`, `examples/schedule-snapshot-result.*.json` |
| `POST /internal/api/v1/subscriptions` | `schemas/subscription-create.v1.schema.json` | `schemas/subscription-credential.v1.schema.json` | `examples/subscription-create.json`, `examples/subscription-credential.json` |
| `POST /internal/api/v1/subscriptions/{subscriptionId}/rotate` | 본문 없음 | `schemas/subscription-credential.v1.schema.json` | `examples/subscription-credential.json` |
| `POST /internal/api/v1/projections/seasons/{seasonId}/rebuild` | 본문 없음 | `schemas/projection-rebuild-result.v1.schema.json` | `examples/projection-rebuild-result.json` |
| 내부 오류 | - | `schemas/api-error.v1.schema.json` | `examples/api-error.source-revision-conflict.json` |

`DELETE /internal/api/v1/subscriptions/{subscriptionId}`는 요청/응답 본문이 없고 `204`를
반환한다. `GET /calendars/v1/{token}.ics`는 JSON이 아니라 PRD-0002의 정규
`text/calendar` 계약을 따른다.

## 기준과 버전 관리

- JSON 구조, 필수 필드, 열거형과 길이 제한은 스키마가 정식 기준이다. null을 허용하는 선택 필드인
  `description`과 `location`은 생략과 명시적인 `null`을 같은 의미로 처리한다.
- 필드 간 비교, 개정 번호 순서, 멱등성, HTTP 상태, 토큰과 iCalendar 의미는
  PRD-0002가 정식 기준이다.
- 예시는 스키마와 의미를 설명하지만 새로운 규칙을 만들지 않는다.
- `schedule-snapshot.zoned-active-r0.json`, `schedule-snapshot.zoned-active-r2.json`,
  `schedule-snapshot.zoned-cancelled.json`은 한 항목의 `ACTIVE` 개정 번호 0, 개정 번호 간격이 있는
  `ACTIVE` 개정 번호 2, `CANCELLED` 개정 번호 3 생명주기를 구성한다.
- `schedule-snapshot.utc-point-active.json`, `schedule-snapshot.zoned-point-active.json`,
  `schedule-snapshot.all-day-active.json`은 임의 지속 시간을 만들지 않는 UTC·시간대 지정 시점과
  `VALUE=DATE`를 사용하는 종일 날짜 구간을 설명한다.
- 소비자는 `additionalProperties: false`를 전제로 한다. 필드나 열거형을 추가하려면 새 스키마
  버전과 생산자·소비자 계약 픽스처가 필요하다.
- v1 절대 시각은 명시적 오프셋이 있는 RFC 3339이고 로컬 날짜·시간은 오프셋 없이 보낸다.
  초는 00부터 59까지, 소수는 최대 9자리만 허용한다. CAL은 타임스탬프를 마이크로초 정밀도로
  정규화하고 iCalendar DATE-TIME은 초 단위로 내림한다.
- JSON Schema만으로 표현하지 못하는 구간의 `end > start`, NFC, Java가 제공하는 이름 있는 TZDB 시간대,
  항목별 개정 번호·타임스탬프 순서와 자격 증명 응답의 `feedUrl` 경로 토큰 = `token`
  관계는 애플리케이션 검증 대상이다.

## JSON 문서 자원 경계

JSON Schema와 DTO의 길이·형식 제약은 파싱된 개별 필드 값을 검증한다. 이와 별도로 CAL의 JSON
파서는 공백과 구조를 포함한 전체 요청 문서를 128 KiB(131,072바이트)로 제한한다. 이 상한은
JSON Schema에 표현하는 필드 제약이 아니라, 큰 문서를 DTO로 만들기 전에 적용하는 자원 경계다.

전체 문서 상한을 넘으면 필드 값의 유효성과 관계없이 `413`과 다음 고정 `api-error.v1` 응답을
반환한다.

```json
{"code":"REQUEST_TOO_LARGE","message":"request body exceeds the maximum size"}
```

## 자동 검증과 BATON 연동

`ContractSchemaSupport`가 Draft 2020-12 스키마 로딩과 검증 결과 보고를 한 곳에서 맡는다.
`ContractArtifactsTest`는 이를 사용해 Docker 없이 모든 JSON 예시를 검증하고, UUID, RFC 3339
시각과 URI의 `format`도 단순 주석이 아니라 검증 조건으로 평가한다. 일정 생명주기 예시는 실제 CAL
내부 HTTP 경로에서 개정·취소·정확한 재전달 순서로 검증하고, 세 시점·종일 예시도 같은 수신 경로로
실행한다. 기존 전체 HTTP 흐름을 실행하는 `MvpHttpFlowTest`는 같은 스키마 지원 코드를 재사용해
MockMvc의 일정 수신 결과, 구독 생성·회전, 투영 재구축과 공통 오류 응답 JSON을 각 응답 스키마에
직접 대조한다. 따라서 별도 Spring 테스트 컨텍스트나 중복 시나리오를 만들지 않으면서, 예제가
유효하더라도 실제 직렬화 결과에 필드가 빠지거나 예고 없이 추가되면 계약 검증이 실패한다.

이 검증은 CAL 계약 팩 자체와 CAL 소비자 구현의 일치를 증명한다. BATON 저장소는 불변 `rc.2`
계약 팩을 고정한 생산자 테스트와 실제 CAL 컨테이너 교차 서비스 테스트로 운영 직렬화기,
`sourceItemId` 비재사용, 원본 트랜잭션 커밋 이후 발행, 변경·취소·중복·역순 전달을 검증했다.

## 계약 팩 생성과 배포

계약 버전의 단일 원천은 `contracts/VERSION`이며 현재 값은 `1.0.0`이다. `rc.2`의 계약 의미가
BATON 생산자 구현에서 변경 없이 검증되어 안정 버전으로 승격했다. 계약 팩은 Gradle 표준 `Zip`
작업으로 생성한다.

```shell
./gradlew --no-daemon contractsZip
```

`build/distributions/baton-cal-contracts-1.0.0.zip`에는 다음 기준만 들어간다.

- `contracts/**`: JSON Schema, 예시, 정규 iCalendar 골든과 이 안내서
- `docs/PRD/0002_mvp-contract/spec.md`: JSON Schema로 표현하지 못하는 필드 간 의미, HTTP 상태,
  토큰과 iCalendar 규칙

작업은 파일 시각을 보존하지 않고 재현 가능한 항목 순서와 Unix 권한을 고정하므로 같은 입력에서
같은 ZIP 바이트를 만든다. 계약을 JVM 구현에 결합하는 공유 DTO JAR은 만들지 않는다. 압축, 체크섬이나
매니페스트도 별도 코드로 구현하지 않고 Gradle의 아카이브 기능과 GitHub Actions가 제공하는
artifact digest를 사용한다.

ZIP 내부의 `contracts/VERSION`, 파일명과 태그 `contracts-v1.0.0`은 모두 같은 버전을 가리켜야
한다. 안정 버전은 새 ZIP과 태그로 게시하며, 게시된 RC 파일과 태그를 덮어쓰지 않는다.

GitHub Actions는 단일 ZIP을 `upload-artifact`로 올리고 `retention-days: 90`으로 보존을 요청한다.
실제 만료 시점은 저장소·조직의 보존 정책을 따르며, 이 파일은 변경 검토와 다운로드 확인을 위한
임시 CI 산출물이므로 BATON이 고정할 안정적인 의존성이 아니다.

불변 `contracts-v1.0.0-rc.1`은 게시됐지만 BATON 원본에 필요한 시점·종일 표현이 없어 변경하지
않는다. [불변 `contracts-v1.0.0-rc.2`](https://github.com/ljkhyeong/baton-cal/releases/tag/contracts-v1.0.0-rc.2)는
병합 커밋 `730ae49a8b8eccf10e8f84f93b8a6a9d0fd24549`와 자산 SHA-256
`75120a7d21b6ea78c1e8bdab60829899525c1607262119053ea5904b57bd1eaf`에 고정되어 있다.
릴리스 증명과 자산은 `gh release verify`, `gh release verify-asset` 검증을 통과했다. BATON은 이
버전을 고정해 실제 직렬화기와 발행 계약 테스트를 통과했고, 같은 계약 의미의 안정
`contracts-v1.0.0` 게시를 준비한다.

## 비밀정보 처리

`subscription-credential` 예시의 피드 URL은 형식 설명용 가짜 값이다. 실제 생성·회전
응답 전체는 비밀정보로 취급하고 저장, 픽스처 캡처, 로그, 추적과 메트릭 레이블에서
제외한다.

## 골든 캘린더

아래 파일은 버전이 고정된 iCal4j 작성기가 한 번 생성한 정규 바이트를 Base64로 고정해 보관한다.
테스트는 픽스처를 다시 만들거나 덮어쓰지 않고 디코딩한 뒤 CRLF와 마지막 CRLF까지 바이트 단위로
비교한다.

- `golden/season-utc.ics.b64`: UTC 활성 항목, TEXT 이스케이프 처리와 안정적 식별자
- `golden/season-empty.ics.b64`: `VEVENT`와 `VTIMEZONE`이 없는 빈 시즌 피드
- `golden/season-zoned-midnight-cancellation.ics.b64`: `America/New_York` DST 종료와 자정을
  통과하는 시간대 지정 로컬 취소 표식
- `golden/season-unicode-fold-boundaries.ics.b64`: TEXT 이스케이프와 4바이트 Unicode 문자가
  iCal4j 줄 접기 경계에 놓이는 UTC 활성 항목
- `golden/season-point-and-all-day.ics.b64`: `DTEND`가 없는 UTC 시점과 `VALUE=DATE`인 종일 날짜 구간

iCal4j 또는 시간대 데이터를 올려 픽스처 바이트가 바뀌면 자동 갱신하지 않고 변경점과 캘린더
호환성 영향을 먼저 검토한다.

## 의존성과 골든 갱신 절차

Spring Boot, Kotlin, iCal4j 또는 JSON Schema 검증기 버전은 한 종류씩 올린다. 의존성을 바꾼 뒤
잠금 파일을 갱신하고 계약·캘린더 테스트를 먼저 실행한다.

```shell
./gradlew --no-daemon dependencies --write-locks
./gradlew --no-daemon test --tests io.baton.cal.contract.ContractArtifactsTest
./gradlew --no-daemon test --tests io.baton.cal.calendar.IcsCalendarRendererTest
```

골든이 달라지면 테스트에서 자동 덮어쓰지 않는다. 후보 `.ics`를 임시 위치에 생성해 속성·컴포넌트
순서, TEXT 재파싱 결과, TZID·VTIMEZONE, UTF-8 물리 줄 길이와 ETag 변화를 검토한다. 의도한
호환성 변화만 Base64 골든에 반영한 뒤 전체 테스트와 실행 JAR 생성을 검증한다.

```shell
./gradlew --no-daemon test
./gradlew --no-daemon bootJar
```
