# PRD-0002: BATON CAL MVP 실행 계약

- 상태: 채택됨
- 결정일: 2026-08-11
- 범위: 시즌 전용, 가져오기 전용 iCalendar 투영 MVP
- 상위 기준: PRD-0001, ADR-0001

## 목적과 현재 상태

이 문서는 PRD-0001의 미결정 사항 중 원본 스냅샷, 시즌 범위 구독,
iCalendar 식별자, 취소, 조건부 요청 검증 값과 재구축의 관측 가능한 계약을 확정한다.
충돌하는 경우 이 문서의 더 구체적인 규칙을 따른다.

이 문서는 경로의 관측 계약을 정의한다. 작업 트리의 실행 골격 존재 여부와 별개로 실제
배포, BATON 연동과 운영 준비는 검증 결과 없이 완료된 것으로 간주하지 않는다.

## MVP 경계

- 하나의 구독은 정확히 하나의 `seasonId`만 조회한다.
- 같은 시즌에 여러 구독을 만들 수 있다.
- 팀, 계정 또는 여러 시즌을 합친 피드는 MVP에 포함하지 않는다.
- 시간 지정 이벤트만 지원한다. 종일 일정용 날짜, 반복 규칙과 고정 시간대가 없는 현지 시각은 지원하지 않는다.
- BATON은 권한, 시즌, 일정, 반복 규칙, 회차, 마감과 시간 의미를 확정한다.
- CAL은 BATON이 보낸 완전한 항목 스냅샷을 저장하고 iCalendar로 투영할 뿐 값을 다시
  계산하지 않는다.
- BATON 서비스용 인증 주체만 모든 내부 경로를 호출할 수 있다. 최종 사용자 세션, 작업공간 키,
  멤버십 또는 ROUND 권한 증표를 CAL에 전달하지 않는다.

## 일정 스냅샷 v1

정식 기계 판독 가능한 계약은
`contracts/schemas/schedule-snapshot.v1.schema.json`이다.
`POST /internal/api/v1/schedule-snapshots`는 `application/json`으로 아래 한 항목의 전체
상태를 받는다.

| 필드 | 의미 |
| --- | --- |
| `eventId` | 전달 봉투의 UUID. 전체 이름 공간에서 유일하며 같은 원본 스냅샷을 새 봉투로 재전송할 때 달라질 수 있다. |
| `occurredAt` | 해당 전달 봉투가 만들어진 RFC 3339 시각. 스냅샷 지문에는 포함하지 않는다. |
| `sourceItemId` | BATON 캘린더 항목의 전역적으로 유일하고 재사용하지 않는 UUID. |
| `seasonId` | 항목이 속한 시즌의 변경 불가능한 UUID. |
| `revision` | `sourceItemId`별 0부터 시작하는 단조 증가 정수. 최댓값은 2,147,483,647이다. |
| `status` | `ACTIVE` 또는 `CANCELLED`. |
| `summary` | 캘린더 제목. 필수이며 LF는 iCalendar TEXT 줄 바꿈으로 투영한다. 단독 CR은 허용하지 않는다. |
| `description` | 선택 설명. 필드가 없거나 JSON `null`이면 캘린더 속성을 생략한다. |
| `location` | 선택 장소. 필드가 없거나 JSON `null`이면 캘린더 속성을 생략한다. |
| `sourceUpdatedAt` | 이 개정 번호를 만든 RFC 3339 시각. 같은 항목의 개정 번호가 전진할 때 반드시 전진한다. |
| `time` | `UTC_INSTANT`의 `startInstant/endInstant` 또는 `ZONED_LOCAL`의 `startLocal/endLocal/zoneId`로 구분하는 합 타입. |

모든 문자열은 Unicode NFC여야 한다. `summary`, `description`, `location`에는 토큰,
워크스페이스 키, 세션, 권한 증표, 제공자 자격 증명 또는 권한이 필요한 URL을 넣지 않는다.
CAL은 이를 보강하거나 BATON 원본을 조회하지 않는다. 시각/현지 시각 타임스탬프는 PostgreSQL과
같은 마이크로초 정밀도로 내림한 뒤 비교·지문 생성·저장한다. 원본 스냅샷 지문은
정규화한 값 중 `eventId`와 `occurredAt`을 제외한 유형이 명시된 필드로 만든다. JSON 속성 순서와
공백은 동일성에 영향을 주지 않지만 각 원본 필드 값, `null`과 문자열의 차이는 영향을 준다.

### 개정 번호와 전달

- BATON은 원본 트랜잭션 커밋 이후 이벤트를 전송한다. CAL 호출 실패가 BATON 원본
  트랜잭션을 롤백해서는 안 된다.
- 전달은 최소 한 번이다. BATON은 응답을 확인하지 못했거나 `429`, `500`, `502`, `503`,
  `504`를 받았을 때 같은 `eventId`를 재사용하거나 새 봉투 식별자로 같은 원본 스냅샷을
  재시도 간격을 두고 재전송할 수 있다.
- 스냅샷은 부분 변경이 아니다. 개정 번호 간격이 있어도 새 스냅샷 하나만으로 항목 전체를
  다시 만들 수 있으므로 CAL은 최신 개정 번호를 적용한다.
- `sourceItemId`는 시즌 사이를 이동하지 않는다. 다른 `seasonId`로 재사용하면 충돌이다.
- 동일 `sourceItemId`에서 투영에 드러나는 값이 바뀌면 개정 번호와 마이크로초로 정규화한
  `sourceUpdatedAt`이 모두 전진해야 한다. 단순 전달 재시도는 둘 다 바꾸지 않는다.
- 더 높은 개정 번호의 `CANCELLED` 항목은 더 높은 개정 번호의 `ACTIVE` 스냅샷으로만
  복구할 수 있다. 이 경우에도 UID는 바뀌지 않는다.

### 수신 결과

성공 응답은 `200 OK`와 `schedule-snapshot-result.v1`이다. CAL은 수신함 기록과 아래 판정을
같은 PostgreSQL 트랜잭션으로 영속화한 뒤 응답한다.

| 처리 결과 | 조건과 효과 |
| --- | --- |
| `APPLIED` | 처음 본 항목이거나 현재보다 높은 개정 번호다. 채택된 스냅샷과 투영을 원자적으로 교체한다. |
| `DUPLICATE` | 동일 `eventId`, 또는 동일 `(sourceItemId, revision)`에 모든 필드의 유형과 값이 같은 내용이 이미 있다. 새 봉투 식별자의 전달 행만 보존하고 채택된 항목과 투영은 바꾸지 않는다. |
| `STALE` | 저장된 동일 개정 번호가 없고 요청 개정 번호가 현재 채택된 개정 번호보다 낮다. 최신 투영을 바꾸지 않는다. |

판정 순서는 이벤트 식별자 충돌, 정확히 같은 중복, 개정 번호 충돌, 오래된 요청, 적용 순이다.
동일 `eventId`를 다른 원본 스냅샷에 쓰거나 동일 `(sourceItemId, revision)`에 다른 원본
스냅샷을 보내면
`409 Conflict`다. 충돌에서는 수신함 최신 상태와 투영을 바꾸지 않는다. 개정 번호
간격 자체는 충돌이 아니다.

## 시간 계약

모든 종료 시각은 미포함 경계이며 시작 시각보다 엄격히 뒤에 있어야 한다. 소수점 이하 초를 받아도 비교와
저장은 마이크로초, 정규 iCalendar DATE-TIME은 초 단위로 내림한다. 내림 후 시작/종료 시각이
같아지면 유효하지 않은 범위다.

### `UTC_INSTANT`

`startInstant`와 `endInstant`는 명시적인 오프셋이 있는 RFC 3339 시각이다. CAL은 같은 시각을
UTC로 정규화해 `DTSTART:...Z`, `DTEND:...Z`로 투영한다.

### `ZONED_LOCAL`

`startLocal`과 `endLocal`은 오프셋이나 `Z`가 없는 ISO 현지 날짜-시간이고 `zoneId`는 Java
`ZoneId.getAvailableZoneIds()`에 있는 이름이 지정된 TZDB 식별자다. `+09:00` 같은 숫자 오프셋은 허용하지
않는다. CAL은 현지 시각 필드를 UTC 시각으로 바꾸지 않고 동일한 `TZID` 매개변수로 투영한다.

BATON은 시간대의 DST 공백에 들어가지 않는 현지 시각 값을 보내야 한다. 중첩 구간에서 특정 시각이
중요하면 `ZONED_LOCAL`을 쓰지 않고 `UTC_INSTANT`를 쓴다. CAL은 시간대 정책, 지속 시간 또는
오프셋을 다시 선택하지 않는다. 피드에는 사용하는 시간대마다 iCal4j 4.2.5
`TimeZoneRegistryImpl`이 제공하는 전체 `VTIMEZONE` 하나를 TZID 오름차순으로 포함한다. 같은
채택된 항목 집합과 고정된 iCal4j 시간대 데이터에서는 구성 요소 바이트가 같아야 한다.

## 취소와 보존

- 삭제, 보관 또는 노출 철회는 BATON이 `status=CANCELLED`인 더 높은 개정 번호의 전체
  스냅샷으로 명시한다. 누락, 전송 중단 또는 빈 피드를 취소로 추론하지 않는다.
- 취소 스냅샷에도 `summary`, 값이 없을 수 있는 설명/장소, 완전한 시간 정보와
  `sourceUpdatedAt`을 모두 보낸다. CAL은 이전 활성 행에 의존하지 않고 취소 표식을 재구축할
  수 있어야 한다.
- 정상적인 시즌 종료는 과거 이벤트의 취소가 아니다. 시즌 삭제나 일정 철회가
  필요하면 BATON이 각 항목의 취소 스냅샷을 보낸다.
- 취소 표식은 MVP에서 자동 만료하지 않는다. 명시적인 후속 보존 계약과
  마이그레이션이 채택되기 전까지 수신함과 피드에 무기한 남긴다.
- 취소 표식은 같은 UID와 새 개정 번호 기반 SEQUENCE를 쓰고 `STATUS:CANCELLED`를 포함한다.

## iCalendar 투영

한 시즌 피드는 그 시즌의 각 `sourceItemId`에 대한 최신 채택 스냅샷 하나를
포함한다. `ACTIVE`와 `CANCELLED` 모두 `VEVENT`다.

### 식별자와 속성 매핑

- `UID` = `{sourceItemId의 정규 소문자 UUID}@cal.baton`. `sourceItemId`는 전역
  유일하므로 시즌 변경이나 CAL 데이터베이스 식별자를 UID에 섞지 않는다.
- `SEQUENCE` = 십진수 `revision`. CAL 측 카운터를 더하거나 재구축 때 증가시키지 않는다.
- `DTSTAMP`와 `LAST-MODIFIED` = `sourceUpdatedAt`을 구분자 없는 UTC DATE-TIME 형식으로 표현한 값이다.
- 원본 `ACTIVE`는 iCalendar `STATUS:CONFIRMED`, 원본 `CANCELLED`는
  `STATUS:CANCELLED`로 투영한다.
- `SUMMARY`, `DESCRIPTION`, `LOCATION`은 iCalendar TEXT 이스케이프 후 투영한다. 값이 없을 수 있는
  속성은 `null`일 때 생략한다.
- 반복 규칙, 주최자, 참석자, 알람, 제공자 URL과 BATON 비공개 위치 식별자는 만들지 않는다.

VCALENDAR의 고정 속성은 아래 순서다.

1. `PRODID:-//BATON//BATON CAL//EN`
2. `VERSION:2.0`
3. `CALSCALE:GREGORIAN`
4. `X-WR-CALNAME:BATON season {seasonId}`

그 뒤 `VTIMEZONE`을 `TZID` 오름차순, `VEVENT`를 UID의 Unicode 코드 포인트 오름차순으로
정렬한다. VEVENT 속성은 `UID`, `DTSTAMP`, `LAST-MODIFIED`, `SEQUENCE`, `STATUS`,
`DTSTART`, `DTEND`, `SUMMARY`, `DESCRIPTION`, `LOCATION` 순서다. 입력 TEXT의 단독 CR은
거부하고 iCal4j가 역슬래시, 쉼표, 세미콜론과 LF를 RFC 5545 규칙으로 이스케이프한다.
`CalendarOutputter`의 UTF-8, CRLF와 마지막 CRLF를 사용하며 줄 접기 길이 25로 모든 물리 줄을
75옥텟 이하로 고정한다.

### 항상 같게 생성되는 검증 값

- 정규 캘린더 바이트가 같으면 바이트 단위로 같은 응답과 검증 값을 만든다.
- `ETag`는 정규 UTF-8 바이트의 SHA-256 소문자 16진수 64자리를 따옴표로 감싼 강한
  태그다.
- `Last-Modified`는 시즌 잠금 안에서 투영 변화마다 전진시키는 CAL `acceptedAt`을
  IMF-fixdate GMT 초 정밀도로 표현한다. 새 투영의 값은 현재 UTC 시계의 초 단위 값과
  이전 투영 값 + 1초 중 큰 값이며 채택된 항목 행에 함께 저장한다. 따라서 원본
  타임스탬프가 가장 최신이 아닌 항목이 바뀌거나 같은 초에 여러 개정 번호가 적용되어도
  `If-Modified-Since`가 새 바이트를 놓치지 않는다. 항목이 하나도 없으면 Unix 시간 원점을 쓴다.
- `If-None-Match`가 있으면 이를 먼저 평가하며 일치할 때 본문 없는 `304 Not Modified`를
  반환한다. 이 헤더가 없을 때만 `If-Modified-Since`를 평가한다.
- `304`에도 `ETag`, `Last-Modified`, `Cache-Control`을 넣고 `Content-Type`과 본문은 넣지 않는다.
- 재구축, 동일 내용 재전달과 낮은 개정 번호 전달은 캘린더 바이트, ETag와 Last-Modified를 바꾸지 않는다.

## 구독 자격 증명 계약

BATON이 최종 사용자 권한과 시즌 범위를 먼저 승인한 뒤 내부 경로를 호출한다. CAL은 계정,
팀 또는 권한 스냅샷을 저장하지 않는다.

- 토큰은 CSPRNG로 만든 무작위 바이트 32개를 패딩 없는 base64url로 인코딩한 43자
  불투명 문자열이다.
- CAL은 43자 base64url 토큰 문자열의 US-ASCII 바이트를 SHA-256으로 계산한 소문자 16진수
  다이제스트만 저장한다. 토큰, 전체 피드 URL 또는 복호화 가능한 형태를 저장소, 로그, 트레이스,
  메트릭 레이블에 저장하지 않는다.
- 토큰과 토큰이 든 `feedUrl`은 각 생성/회전 성공 응답에서 한 번만 반환하며 응답은
  저장소나 애플리케이션 로그에 보관하지 않는다.
- 생성/회전에는 멱등성 키나 동일 응답 재사용이 없다. 생성은 성공할 때마다 새
  구독을 만들고 회전은 호출할 때마다 새 토큰으로 바꾼다.
- `calendar_subscription`은 현재 `token_hash` 하나만 가진다. 회전은 현재 해시를 조건으로
  한 원자적 비교 후 설정(CAS)으로 새 다이제스트를 교체하며 과거 다이제스트 행을 보존하지 않는다.
- 회전 응답 뒤에는 새 토큰만 성공하고 이전 URL은 구분 없는 `404`를 반환한다.
- 생성 응답을 잃은 경우 동일 응답을 안전하게 다시 받는 계약이 없다는 점은 MVP의 명시적인 제한이다.
  BATON은 성공 여부가 불확실한 생성을 맹목적으로 재시도하지 않고 사용자에게 재발급 절차를 안내한다.
- DELETE는 구독과 모든 토큰을 폐기하는 멱등 연산이다. 응답 이후 이전
  URL은 성공할 수 없다.

## HTTP 경로

### 내부 인증과 공통 오류

모든 `/internal/api/v1/**` 경로는 `Authorization: Bearer {internalToken}`을 요구한다.
`internalToken`은 BATON→CAL 호출만을 위해 배포 비밀값으로 주입하는 32자 이상의 고엔트로피
자격 증명이고 상수 시간으로 비교한다. BATON 최종 사용자 Bearer 토큰, 워크스페이스 키 또는
세션을 재사용하지 않는다. 누락하거나 틀리면 `401`과 `UNAUTHORIZED` 오류를 반환한다.

오류는 달리 명시하지 않으면 `application/json`과 `api-error.v1`의 `{code, message}`를 쓴다.
응답과 애플리케이션 로그에는 토큰이나 피드 URL을 넣지 않는다.

| 상태 | 코드 | 의미 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | 잘못된 JSON, Bean Validation 또는 시간 형식/도메인 검증 실패 |
| `401` | `UNAUTHORIZED` | 내부 Bearer 자격 증명 누락 또는 불일치 |
| `404` | `RESOURCE_NOT_FOUND` | 내부 구독 자원이 없거나 활성 상태가 아님 |
| `409` | `EVENT_ID_CONFLICT` | 같은 이벤트 식별자가 다른 원본 스냅샷을 가리킴 |
| `409` | `SOURCE_REVISION_CONFLICT` | 같은 항목/개정 번호가 다른 원본 내용을 가리킴 |
| `409` | `SOURCE_ITEM_SCOPE_CONFLICT` | 같은 원본 항목을 다른 시즌에 재사용함 |
| `409` | `SUBSCRIPTION_CONFLICT` | 회전/폐기 원자적 비교 후 설정(CAS)의 동시성 충돌 |
| `500` | `INTERNAL_ERROR` | 호출자에게 내부 세부 정보를 노출하지 않는 예상 밖 실패 |

스냅샷 전달은 네트워크 실패나 `5xx`만 재시도 간격을 두고 재시도하며 `4xx`는 계약/설정
오류로 처리한다. 보류한 요청 제한을 도입하면 `429`도 재시도 가능한 상태에 추가한다.

### `POST /internal/api/v1/schedule-snapshots`

- 요청: `schedule-snapshot.v1`
- 응답: `200`, `schedule-snapshot-result.v1`
- 충돌: `409 EVENT_ID_CONFLICT`, `SOURCE_REVISION_CONFLICT` 또는
  `SOURCE_ITEM_SCOPE_CONFLICT`

### `POST /internal/api/v1/subscriptions`

- 요청: `subscription-create.v1`의 `{seasonId}`
- 최초 성공: `201 Created`, `subscription-credential.v1`
- 응답은 `{subscriptionId, token, feedUrl}`이며 토큰과 URL은 동일한 자격 증명을 표현한다.
- 스냅샷이 아직 없는 시즌도 구독을 생성할 수 있고 이때 공개 피드는 빈 VCALENDAR다.

### `POST /internal/api/v1/subscriptions/{subscriptionId}/rotate`

- 요청 본문 없음
- 최초 성공: `200 OK`, `subscription-credential.v1`
- 현재 토큰 해시의 원자적 비교 후 설정(CAS) 교체는 응답 전 한 트랜잭션으로 커밋한다.
- 알 수 없거나 폐기된 구독은 `404 RESOURCE_NOT_FOUND`다.
- 동시 회전/폐기 충돌은 `409 SUBSCRIPTION_CONFLICT`다.

### `DELETE /internal/api/v1/subscriptions/{subscriptionId}`

- 활성 구독은 폐기 후 `204 No Content`, 이미 폐기된 구독은 `204`다.
- 알 수 없는 식별자는 `404 RESOURCE_NOT_FOUND`다.
- 응답 본문이 없고 커밋 이후 모든 관련 토큰 조회는 실패해야 한다.

### `POST /internal/api/v1/projections/seasons/{seasonId}/rebuild`

- 요청 본문 없음
- 응답: `200 OK`, `projection-rebuild-result.v1`
- 채택된 원본 상태를 담은 테이블에서 각 항목의 최신 전체 스냅샷으로 시즌 투영을
  트랜잭션으로 교체한다. 오래되거나 충돌한 내용을 원본 상태로 채택하지 않는다.
- 응답은 `{seasonId, etag, itemCount}`다.
- 재구축은 구독과 토큰 행을 건드리지 않고 UID, SEQUENCE, 취소 표식, 바이트,
  ETag와 Last-Modified를 보존한다.

### `GET /calendars/v1/{token}.ics`

- 유효하고 활성 상태인 토큰: `200 OK`
- `Content-Type: text/calendar; charset=utf-8`
- `Cache-Control: no-cache, private`
- `Content-Disposition: inline; filename=\"baton-calendar.ics\"`
- 정규 `ETag`와 `Last-Modified`
- 조건부 일치: 본문 없는 `304 Not Modified`
- 형식이 잘못되었거나, 알 수 없거나, 회전 또는 폐기된 토큰은 구분하지 않고 본문 없는 `404 Not Found`를
  반환한다.

공개 경로는 읽기 전용이다. 토큰 조회, 피드 렌더링 또는 조건부 GET이 BATON 상태를
변경하거나 BATON API를 호출하지 않는다.

## 호환성 및 수용 기준 픽스처

구현 수용 기준은 다음 골든/계약 테스트다.

1. UTC 확정 일정 생성, 더 높은 개정 번호의 시간/텍스트 갱신과 정확히 같은 중복.
2. 시간대가 있는 현지 시각의 DST 경계와 자정을 지나는 이벤트. 공백 구간 거부와 UTC 시각/현지 시각 비변환 확인.
3. 보지 못한 더 낮은 개정 번호의 `STALE`, 같은 개정 번호의 다른 내용에 대한 `409`, 개정 번호 간격 적용.
4. 전체 취소, 안정적인 UID, 정확한 SEQUENCE와 무기한 취소 표식.
5. 쉼표/세미콜론/역슬래시/줄 바꿈 이스케이프, UTF-8 NFC, 75옥텟 줄 접기와 CRLF.
6. 같은 입력에서 항상 같은 시간대/이벤트/속성 순서, 바이트가 안정적인 재구축, ETag와 Last-Modified.
7. `If-None-Match` 우선순위, 본문 없는 `304`, 빈 피드와 공개 경로의 구분 없는 `404`.
8. 토큰 원문의 일회성 노출, 다이제스트만 저장, 로그 비노출 처리, 원자적 회전과
   즉시 폐기.
9. BATON 커밋 이후 전달, CAL 트랜잭션 실패, 재시도와 복구.

## 현재 구현 상태와 출시 조건

Kotlin/Spring MVC 실행 기반, PostgreSQL/Flyway 영속성 계층, iCal4j 투영과 위 HTTP 경로의
골격은 생겼다. 이는 BATON 연동이나 운영 준비 완료를 뜻하지 않는다. 다음은
BATON 연동 또는 공개 배포 전에 해결해야 하는 보류 항목이다.

- BATON 생산자가 이 JSON Schema, 전역 `sourceItemId`, 개정 번호와 명시적인 취소를
  준수한다는 소비자 주도 계약 픽스처.
- 추가 이스케이프/줄 접기 경계 사례의 정규 `.ics` 골든 바이트. UTC, 시간대가 있는 현지 시각의 DST와
  자정 취소, 빈 피드 픽스처는 구현되어 있다.
- 생성된 Gradle 의존성 잠금과 고정된 Spring Boot/Kotlin/iCal4j 시간대 데이터를 올릴 때
  골든 픽스처를 검토하는 갱신 절차.
- 내부 Bearer 비밀값 발급·회전 절차, 공개 기본 URL, TLS와 역방향 프록시 경로 삭제 처리 설정.
- 백업/복원 절차와 실제 환경의 복원 훈련. Flyway 스키마, 수신함/항목/투영
  트랜잭션 롤백, 재구축 잠금과 구독 CAS는 Testcontainers로 검증한다.
- 스냅샷 요청 본문 상한과 `413` 매핑, 공개 요청 제한 수치와 접근 감사 기록 보존.
  토큰 경로 또는 토큰 다이제스트를 접근 로그, 요청 제한 키, 메트릭 레이블이나 감사 내용으로
  남기지 않는 삭제·비노출 처리 방법.
- 실제 배포 환경의 운영 절차서. 로컬 PostgreSQL 실행, 상태 확인과 빌드/테스트 명령은
  README에 기록되어 있다.
