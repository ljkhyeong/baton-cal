# PRD-0002: BATON CAL MVP 실행 계약

- 상태: Accepted
- 결정일: 2026-08-11
- 범위: season-only, pull-only iCalendar projection MVP
- 상위 기준: PRD-0001, ADR-0001

## 목적과 현재 상태

이 문서는 PRD-0001의 미결정 사항 중 source snapshot, season scope subscription,
iCalendar identity, cancellation, validator와 rebuild의 관측 가능한 계약을 확정한다.
충돌하는 경우 이 문서의 더 구체적인 규칙을 따른다.

이 문서는 route의 관측 계약을 정의한다. worktree의 runtime scaffold 존재 여부와 별개로 실제
배포, BATON 연동과 운영 readiness는 검증 결과 없이 완료된 것으로 간주하지 않는다.

## MVP 경계

- 하나의 subscription은 정확히 하나의 `seasonId`만 조회한다.
- 같은 season에 여러 subscription을 만들 수 있다.
- team, account 또는 여러 season을 합친 feed는 MVP에 포함하지 않는다.
- timed event만 지원한다. all-day date, recurrence rule과 floating local time은 지원하지 않는다.
- BATON은 권한, season, schedule, recurrence, round, deadline과 시간 의미를 확정한다.
- CAL은 BATON이 보낸 완전한 item snapshot을 저장하고 iCalendar로 투영할 뿐 값을 다시
  계산하지 않는다.
- 모든 internal route는 BATON service principal만 호출한다. end-user session, workspace key,
  membership 또는 ROUND grant를 CAL에 전달하지 않는다.

## Schedule snapshot v1

정식 machine-readable 계약은
`contracts/schemas/schedule-snapshot.v1.schema.json`이다.
`POST /internal/api/v1/schedule-snapshots`는 `application/json`으로 아래 한 item의 전체
상태를 받는다.

| field | 의미 |
| --- | --- |
| `eventId` | delivery envelope의 UUID. 전체 namespace에서 유일하며 같은 source snapshot을 새 envelope로 재전송할 때 달라질 수 있다. |
| `occurredAt` | 해당 delivery envelope가 만들어진 RFC 3339 instant. snapshot fingerprint에는 포함하지 않는다. |
| `sourceItemId` | BATON calendar item의 전역적으로 유일하고 재사용하지 않는 UUID. |
| `seasonId` | item이 속한 season의 변경 불가능한 UUID. |
| `revision` | `sourceItemId`별 0부터 시작하는 단조 증가 정수. 최대값은 2,147,483,647이다. |
| `status` | `ACTIVE` 또는 `CANCELLED`. |
| `summary` | calendar 제목. 필수이며 LF는 iCalendar TEXT newline으로 투영한다. bare CR은 허용하지 않는다. |
| `description` | 선택 설명. field가 없거나 JSON `null`이면 calendar property를 생략한다. |
| `location` | 선택 장소. field가 없거나 JSON `null`이면 calendar property를 생략한다. |
| `sourceUpdatedAt` | 이 revision을 만든 RFC 3339 instant. 같은 item의 revision이 전진할 때 반드시 전진한다. |
| `time` | `UTC_INSTANT`의 `startInstant/endInstant` 또는 `ZONED_LOCAL`의 `startLocal/endLocal/zoneId` tagged union. |

모든 문자열은 Unicode NFC여야 한다. `summary`, `description`, `location`에는 token,
workspace key, session, grant, provider credential 또는 권한이 필요한 URL을 넣지 않는다.
CAL은 이를 보강하거나 BATON 원본을 조회하지 않는다. instant/local timestamp는 PostgreSQL과
같은 microsecond 정밀도로 내림한 뒤 비교·fingerprint·저장한다. source snapshot fingerprint는
정규화한 값 중 `eventId`와 `occurredAt`을 제외한 typed field로 만든다. JSON property 순서와
공백은 동일성에 영향을 주지 않지만 각 source field 값, `null`과 문자열의 차이는 영향을 준다.

### Revision과 delivery

- BATON은 source transaction commit 이후 event를 전송한다. CAL 호출 실패가 BATON source
  transaction을 rollback해서는 안 된다.
- 전달은 at-least-once이다. BATON은 응답을 확인하지 못했거나 `429`, `500`, `502`, `503`,
  `504`를 받았을 때 같은 `eventId`를 재사용하거나 새 envelope id로 같은 source snapshot을
  backoff 재전송할 수 있다.
- snapshot은 patch가 아니다. revision 간격이 있어도 새 snapshot 하나만으로 item 전체를
  다시 만들 수 있으므로 CAL은 최신 revision을 적용한다.
- `sourceItemId`는 season 사이를 이동하지 않는다. 다른 `seasonId`로 재사용하면 conflict다.
- 동일 `sourceItemId`의 projection-visible 값이 바뀌면 revision과 microsecond로 정규화한
  `sourceUpdatedAt`이 모두 전진해야 한다. 단순 delivery 재시도는 둘 다 바꾸지 않는다.
- 더 높은 revision의 `CANCELLED` item은 더 높은 revision의 `ACTIVE` snapshot으로만
  복구할 수 있다. 이 경우에도 UID는 바뀌지 않는다.

### Ingest 결과

성공 응답은 `200 OK`와 `schedule-snapshot-result.v1`이다. CAL은 inbox 기록과 아래 판정을
같은 PostgreSQL transaction으로 durable하게 만든 뒤 응답한다.

| disposition | 조건과 효과 |
| --- | --- |
| `APPLIED` | 처음 본 item이거나 현재보다 높은 revision이다. accepted snapshot과 projection을 원자적으로 교체한다. |
| `DUPLICATE` | 동일 `eventId`, 또는 동일 `(sourceItemId, revision)`에 같은 typed payload가 이미 있다. 새 envelope id의 delivery row만 보존하고 accepted item과 projection은 바꾸지 않는다. |
| `STALE` | 저장된 동일 revision이 없고 요청 revision이 현재 accepted revision보다 낮다. 최신 projection을 바꾸지 않는다. |

판정 순서는 event id conflict, exact duplicate, revision conflict, stale, applied 순이다.
동일 `eventId`를 다른 source snapshot에 쓰거나 동일 `(sourceItemId, revision)`에 다른 source
snapshot을 보내면
`409 Conflict`다. conflict에서는 inbox latest state와 projection을 바꾸지 않는다. revision
gap 자체는 conflict가 아니다.

## Time contract

모든 end는 exclusive이고 start보다 엄격히 뒤여야 한다. fractional second를 받아도 비교와
저장은 microsecond, canonical iCalendar DATE-TIME은 초 단위로 내림한다. 내림 후 start/end가
같아지면 유효하지 않은 범위다.

### `UTC_INSTANT`

`startInstant`와 `endInstant`는 explicit offset이 있는 RFC 3339 instant다. CAL은 같은 instant를
UTC로 정규화해 `DTSTART:...Z`, `DTEND:...Z`로 투영한다.

### `ZONED_LOCAL`

`startLocal`과 `endLocal`은 offset이나 `Z`가 없는 ISO local date-time이고 `zoneId`는 Java
`ZoneId.getAvailableZoneIds()`에 있는 named TZDB id다. `+09:00` 같은 숫자 offset은 허용하지
않는다. CAL은 local field를 instant로 바꾸지 않고 동일한 `TZID` parameter로 투영한다.

BATON은 zone의 DST gap에 들어가지 않는 local value를 보내야 한다. overlap에서 특정 instant가
중요하면 `ZONED_LOCAL`을 쓰지 않고 `UTC_INSTANT`를 쓴다. CAL은 zone 정책, duration 또는
offset을 다시 선택하지 않는다. feed에는 사용하는 zone마다 iCal4j 4.2.5
`TimeZoneRegistryImpl`이 제공하는 전체 `VTIMEZONE` 하나를 TZID 오름차순으로 포함한다. 같은
accepted item set과 pin된 iCal4j zone data에서는 component bytes가 같아야 한다.

## Cancellation과 retention

- 삭제, archive 또는 노출 철회는 BATON이 `status=CANCELLED`인 더 높은 revision의 전체
  snapshot으로 명시한다. 누락, 전송 중단 또는 빈 feed를 cancellation으로 추론하지 않는다.
- cancellation snapshot에도 `summary`, nullable description/location, complete time과
  `sourceUpdatedAt`을 모두 보낸다. CAL은 이전 active row에 의존하지 않고 tombstone을 재구축할
  수 있어야 한다.
- 정상적인 season 종료는 과거 event의 cancellation이 아니다. season 삭제나 일정 철회가
  필요하면 BATON이 각 item의 cancellation snapshot을 보낸다.
- cancellation tombstone은 MVP에서 자동 만료하지 않는다. 명시적인 후속 retention 계약과
  migration이 채택되기 전까지 inbox와 feed에 무기한 남긴다.
- tombstone은 같은 UID와 새 revision 기반 SEQUENCE를 쓰고 `STATUS:CANCELLED`를 포함한다.

## iCalendar projection

한 season feed는 그 season의 각 `sourceItemId`에 대한 최신 accepted snapshot 하나를
포함한다. `ACTIVE`와 `CANCELLED` 모두 `VEVENT`다.

### Identity와 property mapping

- `UID` = `{sourceItemId의 canonical lowercase UUID}@cal.baton`. `sourceItemId`는 전역
  유일하므로 season 변경이나 CAL database id를 UID에 섞지 않는다.
- `SEQUENCE` = decimal `revision`. CAL-side counter를 더하거나 rebuild 때 증가시키지 않는다.
- `DTSTAMP`와 `LAST-MODIFIED` = `sourceUpdatedAt`을 UTC basic date-time으로 표현한 값이다.
- source `ACTIVE`는 iCalendar `STATUS:CONFIRMED`, source `CANCELLED`는
  `STATUS:CANCELLED`로 투영한다.
- `SUMMARY`, `DESCRIPTION`, `LOCATION`은 iCalendar TEXT escaping 후 투영한다. nullable
  property는 `null`일 때 생략한다.
- recurrence, organizer, attendee, alarm, provider URL과 BATON private locator는 만들지 않는다.

VCALENDAR의 고정 property는 아래 순서다.

1. `PRODID:-//BATON//BATON CAL//EN`
2. `VERSION:2.0`
3. `CALSCALE:GREGORIAN`
4. `X-WR-CALNAME:BATON season {seasonId}`

그 뒤 `VTIMEZONE`을 `TZID` 오름차순, `VEVENT`를 UID의 Unicode code point 오름차순으로
정렬한다. VEVENT property는 `UID`, `DTSTAMP`, `LAST-MODIFIED`, `SEQUENCE`, `STATUS`,
`DTSTART`, `DTEND`, `SUMMARY`, `DESCRIPTION`, `LOCATION` 순서다. 입력 TEXT의 bare CR은
거부하고 iCal4j가 backslash, comma, semicolon과 LF를 RFC 5545 규칙으로 escape한다.
`CalendarOutputter`의 UTF-8, CRLF와 마지막 CRLF를 사용하며 fold length 25로 모든 물리 줄을
75 octet 이하로 고정한다.

### Deterministic validator

- canonical calendar bytes가 같으면 byte-for-byte 같은 response와 validator를 만든다.
- `ETag`는 canonical UTF-8 bytes의 SHA-256 lowercase hex를 quote한 strong tag, 즉
  `\"{64 lowercase hex chars}\"`다.
- `Last-Modified`는 season lock 안에서 projection 변화마다 전진시키는 CAL `acceptedAt`을
  IMF-fixdate GMT 초 정밀도로 표현한다. 새 projection의 값은 현재 UTC clock의 초 단위 값과
  이전 projection 값 + 1초 중 큰 값이며 accepted item row에 함께 저장한다. 따라서 source
  timestamp가 가장 최신이 아닌 item이 바뀌거나 같은 초에 여러 revision이 적용되어도
  `If-Modified-Since`가 새 bytes를 놓치지 않는다. item이 하나도 없으면 Unix epoch를 쓴다.
- `If-None-Match`가 있으면 이를 먼저 평가하며 match할 때 body 없는 `304 Not Modified`를
  반환한다. 이 header가 없을 때만 `If-Modified-Since`를 평가한다.
- `304`에도 `ETag`, `Last-Modified`, `Cache-Control`을 넣고 `Content-Type`과 body는 넣지 않는다.
- rebuild, exact replay와 stale delivery는 calendar bytes, ETag와 Last-Modified를 바꾸지 않는다.

## Subscription credential contract

BATON이 end-user 권한과 season scope를 먼저 승인한 뒤 internal route를 호출한다. CAL은 account,
team 또는 권한 snapshot을 저장하지 않는다.

- token은 CSPRNG로 만든 32 random bytes를 base64url without padding으로 인코딩한 43자
  opaque string이다.
- CAL은 43자 base64url token 문자열의 US-ASCII bytes를 SHA-256으로 계산한 lowercase hex
  digest만 저장한다. token, full feed URL 또는 복호화 가능한 형태를 persistence, log, trace,
  metric label에 저장하지 않는다.
- token과 token이 든 `feedUrl`은 각 create/rotate 성공 응답에서 한 번만 반환하며 응답은
  persistence나 application log에 보관하지 않는다.
- create/rotate에는 idempotency key나 response replay가 없다. create는 성공할 때마다 새
  subscription을 만들고 rotate는 호출할 때마다 새 token으로 바꾼다.
- `calendar_subscription`은 현재 `token_hash` 하나만 가진다. rotation은 현재 hash를 조건으로
  한 compare-and-set으로 새 digest를 교체하며 과거 digest row를 보존하지 않는다.
- rotation response 뒤에는 새 token만 성공하고 old URL은 generic `404`를 반환한다.
- create 응답을 잃은 경우 안전한 자동 replay 계약이 없다는 점은 MVP의 명시적인 제한이다.
  BATON은 불확실한 create를 blind retry하지 않고 사용자에게 재발급 절차를 안내한다.
- DELETE는 subscription과 모든 token을 폐기하는 idempotent operation이다. 응답 이후 이전
  URL은 성공할 수 없다.

## HTTP routes

### Internal authentication과 공통 오류

모든 `/internal/api/v1/**` route는 `Authorization: Bearer {internalToken}`을 요구한다.
`internalToken`은 BATON→CAL 호출만을 위해 배포 secret으로 주입하는 32자 이상의 고엔트로피
credential이고 constant-time으로 비교한다. BATON end-user bearer token, workspace key 또는
session을 재사용하지 않는다. 누락하거나 틀리면 `401`과 `UNAUTHORIZED` error를 반환한다.

오류는 달리 명시하지 않으면 `application/json`과 `api-error.v1`의 `{code, message}`를 쓴다.
response와 application log에는 token이나 feed URL을 넣지 않는다.

| status | code | 의미 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | malformed JSON, Bean Validation 또는 time shape/domain validation 실패 |
| `401` | `UNAUTHORIZED` | internal bearer credential 누락 또는 불일치 |
| `404` | `RESOURCE_NOT_FOUND` | internal subscription resource가 없거나 active가 아님 |
| `409` | `EVENT_ID_CONFLICT` | 같은 event id가 다른 source snapshot을 가리킴 |
| `409` | `SOURCE_REVISION_CONFLICT` | 같은 item/revision이 다른 source content를 가리킴 |
| `409` | `SOURCE_ITEM_SCOPE_CONFLICT` | 같은 source item을 다른 season에 재사용함 |
| `409` | `SUBSCRIPTION_CONFLICT` | rotate/revoke compare-and-set concurrent conflict |
| `500` | `INTERNAL_ERROR` | caller에게 내부 detail을 노출하지 않는 예상 밖 실패 |

Snapshot delivery는 network failure나 `5xx`만 backoff 재시도하고 `4xx`는 contract/configuration
오류로 처리한다. Deferred rate limit이 도입되면 `429`도 retryable status에 추가한다.

### `POST /internal/api/v1/schedule-snapshots`

- request: `schedule-snapshot.v1`
- response: `200`, `schedule-snapshot-result.v1`
- conflicts: `409 EVENT_ID_CONFLICT`, `SOURCE_REVISION_CONFLICT` 또는
  `SOURCE_ITEM_SCOPE_CONFLICT`

### `POST /internal/api/v1/subscriptions`

- request: `subscription-create.v1`의 `{seasonId}`
- 최초 성공: `201 Created`, `subscription-credential.v1`
- response는 `{subscriptionId, token, feedUrl}`이며 token과 URL은 동일 credential을 표현한다.
- snapshot이 아직 없는 season도 provision할 수 있고 이때 public feed는 빈 VCALENDAR다.

### `POST /internal/api/v1/subscriptions/{subscriptionId}/rotate`

- request body 없음
- 최초 성공: `200 OK`, `subscription-credential.v1`
- 현재 token hash의 compare-and-set 교체는 응답 전 한 transaction으로 commit한다.
- 알 수 없거나 폐기된 subscription은 `404 RESOURCE_NOT_FOUND`다.
- concurrent rotate/revoke conflict는 `409 SUBSCRIPTION_CONFLICT`다.

### `DELETE /internal/api/v1/subscriptions/{subscriptionId}`

- active subscription은 revoke 후 `204 No Content`, 이미 revoked subscription은 `204`다.
- 알 수 없는 id는 `404 RESOURCE_NOT_FOUND`다.
- response body가 없고 commit 이후 모든 관련 token lookup은 실패해야 한다.

### `POST /internal/api/v1/projections/seasons/{seasonId}/rebuild`

- request body 없음
- response: `200 OK`, `projection-rebuild-result.v1`
- accepted source state table의 각 item latest full snapshot으로 season projection을
  transactionally 교체한다. stale/conflict payload를 source state로 채택하지 않는다.
- response는 `{seasonId, etag, itemCount}`다.
- rebuild는 subscription과 token row를 건드리지 않고 UID, SEQUENCE, tombstone, bytes,
  ETag와 Last-Modified를 보존한다.

### `GET /calendars/v1/{token}.ics`

- 유효하고 active한 token: `200 OK`
- `Content-Type: text/calendar; charset=utf-8`
- `Cache-Control: no-cache, private`
- `Content-Disposition: inline; filename=\"baton-calendar.ics\"`
- canonical `ETag`와 `Last-Modified`
- 조건부 match: body 없는 `304 Not Modified`
- malformed, unknown, rotated 또는 revoked token은 구분하지 않고 body 없는 `404 Not Found`를
  반환한다.

public route는 read-only다. token lookup, feed rendering 또는 conditional GET이 BATON state를
변경하거나 BATON API를 호출하지 않는다.

## Compatibility와 acceptance fixtures

구현 수용 기준은 다음 golden/contract test다.

1. UTC confirmed creation, higher-revision time/text update와 exact duplicate.
2. zoned local DST 경계와 자정 통과 event. gap reject와 instant/local 비변환 확인.
3. lower unseen revision의 `STALE`, same revision의 different payload `409`, revision gap apply.
4. full cancellation, stable UID, exact SEQUENCE와 indefinite tombstone.
5. comma/semicolon/backslash/newline escaping, UTF-8 NFC, 75-octet folding와 CRLF.
6. deterministic zone/event/property ordering, byte-stable rebuild, ETag와 Last-Modified.
7. `If-None-Match` precedence, bodyless `304`, empty feed와 public generic `404`.
8. token plaintext one-time exposure, digest-only persistence, log redaction, atomic rotate와
   immediate revoke.
9. BATON after-commit delivery, CAL transaction failure, retry와 recovery.

## 현재 구현 상태와 release gate

Kotlin/Spring MVC runtime, PostgreSQL/Flyway persistence, iCal4j projection과 위 HTTP route의
scaffold는 생겼다. 이는 BATON 연동이나 production readiness 완료를 뜻하지 않는다. 다음은
BATON integration 또는 public deployment 전에 닫아야 하는 Deferred 항목이다.

- BATON producer가 이 JSON Schema, global `sourceItemId`, revision과 explicit cancellation을
  준수한다는 consumer-driven contract fixture.
- 현재 UTC golden 외에 zoned local, DST, midnight, cancellation, 추가 escaping/folding와
  empty feed의 canonical `.ics` golden bytes.
- 생성된 Gradle dependency lock과 pin된 Spring Boot/Kotlin/iCal4j zone data를 올릴 때
  golden fixture를 검토하는 update 절차.
- internal bearer secret 발급·회전 절차, public base URL, TLS와 reverse-proxy path redaction 설정.
- Flyway schema, unique/index/transaction 경계, rebuild 동시성 lock과 backup/restore 절차.
- snapshot request body 상한과 `413` mapping, public rate limit 수치와 access audit retention.
  token path 또는 token digest를 access log, rate-limit key, metric label이나 audit payload로
  남기지 않는 redaction 방법.
- 현재 scaffold에서 실제로 실행한 build/test 명령과 운영 runbook.
