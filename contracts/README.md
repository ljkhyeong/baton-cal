# BATON CAL MVP contracts

이 디렉터리는 PRD-0002의 language-neutral JSON 계약과 example을 보관한다. 현재 route는
MVP scaffold에 구현되어 있지만 BATON producer 연동이나 production readiness 완료를 뜻하지
않는다.

## Contract map

| route | request schema | success schema | example |
| --- | --- | --- | --- |
| `POST /internal/api/v1/schedule-snapshots` | `schemas/schedule-snapshot.v1.schema.json` | `schemas/schedule-snapshot-result.v1.schema.json` | `examples/schedule-snapshot.*.json`, `examples/schedule-snapshot-result.*.json` |
| `POST /internal/api/v1/subscriptions` | `schemas/subscription-create.v1.schema.json` | `schemas/subscription-credential.v1.schema.json` | `examples/subscription-create.json`, `examples/subscription-credential.json` |
| `POST /internal/api/v1/subscriptions/{subscriptionId}/rotate` | body 없음 | `schemas/subscription-credential.v1.schema.json` | `examples/subscription-credential.json` |
| `POST /internal/api/v1/projections/seasons/{seasonId}/rebuild` | body 없음 | `schemas/projection-rebuild-result.v1.schema.json` | `examples/projection-rebuild-result.json` |
| internal error | - | `schemas/api-error.v1.schema.json` | `examples/api-error.source-revision-conflict.json` |

`DELETE /internal/api/v1/subscriptions/{subscriptionId}`는 request/response body가 없고 `204`를
반환한다. `GET /calendars/v1/{token}.ics`는 JSON이 아니라 PRD-0002의 canonical
`text/calendar` 계약을 따른다.

## Authority와 versioning

- JSON shape, required field, enum과 길이 제한은 schema가 정식 기준이다. nullable 선택 field인
  `description`과 `location`은 생략과 명시적인 `null`을 같은 의미로 처리한다.
- cross-field 비교, revision ordering, idempotency, HTTP status, token과 iCalendar 의미는
  PRD-0002가 정식 기준이다.
- example은 schema와 의미를 설명하지만 새로운 규칙을 만들지 않는다.
- consumer는 `additionalProperties: false`를 전제로 한다. field 추가나 enum 추가는 새 schema
  version과 producer/consumer contract fixture가 필요하다.
- v1 instant는 explicit offset이 있는 RFC 3339이고 local date-time은 offset 없이 보낸다.
  CAL은 timestamp를 microsecond 정밀도로 canonicalize하고 iCalendar DATE-TIME은 초 단위로
  내림한다.
- JSON Schema만으로 표현하지 못하는 `end > start`, NFC, Java가 제공하는 named TZDB zone,
  per-item revision/timestamp ordering과 credential response의 `feedUrl` path token = `token`
  관계는 application validation 대상이다.

## Secret handling

`subscription-credential` example의 feed URL은 형식 설명용 가짜 값이다. 실제 create/rotate
응답 전체는 secret으로 취급하고 저장, fixture capture, log, trace와 metric label에서
제외한다.

## Golden calendar

`golden/season-utc.ics.b64`는 iCal4j가 만든 UTC active item의 canonical bytes를 Base64로
보관한다. test가 decode한 뒤 CRLF와 마지막 CRLF까지 byte 단위로 비교한다. zoned local/DST,
cancellation, empty feed와 추가 escaping/folding fixture는 PRD-0002의 release gate에 남아 있다.
