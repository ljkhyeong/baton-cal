# BATON CAL MVP 계약

이 디렉터리는 PRD-0002의 언어 중립적 JSON 계약과 예시를 보관한다. 현재 경로는
MVP 애플리케이션 골격에 구현되어 있지만 BATON 생산자 연동이나 운영 준비 완료를 뜻하지 않는다.

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
- 소비자는 `additionalProperties: false`를 전제로 한다. 필드나 열거형을 추가하려면 새 스키마
  버전과 생산자·소비자 계약 픽스처가 필요하다.
- v1 절대 시각은 명시적 오프셋이 있는 RFC 3339이고 로컬 날짜·시간은 오프셋 없이 보낸다.
  CAL은 타임스탬프를 마이크로초 정밀도로 정규화하고 iCalendar DATE-TIME은 초 단위로
  내림한다.
- JSON Schema만으로 표현하지 못하는 `end > start`, NFC, Java가 제공하는 이름 있는 TZDB 시간대,
  항목별 개정 번호·타임스탬프 순서와 자격 증명 응답의 `feedUrl` 경로 토큰 = `token`
  관계는 애플리케이션 검증 대상이다.

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

iCal4j 또는 시간대 데이터를 올려 픽스처 바이트가 바뀌면 자동 갱신하지 않고 변경점과 캘린더
호환성 영향을 먼저 검토한다. 추가 이스케이프·줄 접기 픽스처는 PRD-0002의 출시 조건에 남아
있다.
