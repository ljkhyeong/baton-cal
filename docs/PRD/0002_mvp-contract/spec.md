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
- UTC·시간대 지정 구간, UTC·시간대 지정 단일 시점과 종일 날짜 구간을 지원한다. 반복 규칙과
  고정 시간대가 없는 현지 시각은 지원하지 않는다.
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

아래 표와 JSON Schema의 길이·형식 규칙은 파싱된 DTO의 개별 필드 제약이다. 공백과 JSON 구조를
포함한 전체 문서의 파서 자원 상한은 모든 JSON 요청에 적용하는 별도 HTTP 경계다.

| 필드 | 의미 |
| --- | --- |
| `eventId` | 전달 봉투의 UUID. 전체 이름 공간에서 유일하며 같은 원본 스냅샷을 새 봉투로 재전송할 때 달라질 수 있다. |
| `occurredAt` | 해당 전달 봉투가 만들어진 RFC 3339 시각. 스냅샷 지문에는 포함하지 않는다. |
| `sourceItemId` | BATON 캘린더 항목의 전역적으로 유일하고 재사용하지 않는 UUID. |
| `seasonId` | 항목이 속한 시즌의 변경 불가능한 UUID. |
| `revision` | `sourceItemId`별 0부터 시작하는 단조 증가 정수. 최댓값은 2,147,483,647이다. |
| `status` | `ACTIVE` 또는 `CANCELLED`. |
| `summary` | 캘린더 제목. Unicode 코드 포인트 기준 1자 이상 512자 이하인 필수 TEXT다. |
| `description` | 선택 설명. 문자열이면 Unicode 코드 포인트 기준 1자 이상 4,096자 이하다. 필드가 없거나 JSON `null`이면 캘린더 속성을 생략한다. |
| `location` | 선택 장소. 문자열이면 Unicode 코드 포인트 기준 1자 이상 512자 이하다. 필드가 없거나 JSON `null`이면 캘린더 속성을 생략한다. |
| `sourceUpdatedAt` | 이 개정 번호를 만든 RFC 3339 시각. 같은 항목의 개정 번호가 전진할 때 반드시 전진한다. |
| `time` | `UTC_INSTANT`, `UTC_POINT`, `ZONED_LOCAL`, `ZONED_LOCAL_POINT`, `ALL_DAY`로 구분하는 합 타입. |

모든 문자열은 Unicode NFC여야 한다. `summary`, `description`, `location`은 LF(`U+000A`),
HTAB(`U+0009`)과 유효한 보조 평면 Unicode를 허용한다. `U+0000..U+0008`,
`U+000B..U+001F`, `U+007F`와 JSON 이스케이프로 표현한 짝이 없는 상위·하위 서로게이트는 거부한다.
이 TEXT에는 토큰, 워크스페이스 키, 세션, 권한 증표, 제공자 자격 증명 또는 권한이 필요한
URL을 넣지 않는다.
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

판정 순서는 이벤트 식별자 충돌, 정확히 같은 중복, 개정 번호 충돌, 낮은 개정 번호 요청, 적용 순이다.
동일 `eventId`를 다른 원본 스냅샷에 쓰거나 동일 `(sourceItemId, revision)`에 다른 원본
스냅샷을 보내면
`409 Conflict`다. 충돌에서는 수신함 최신 상태와 투영을 바꾸지 않는다. 개정 번호
간격 자체는 충돌이 아니다.

## 시간 계약

구간의 종료 시각·날짜는 미포함 경계이며 시작보다 엄격히 뒤에 있어야 한다. 소수점 이하 초를 받아도
비교와 저장은 마이크로초, 정규 iCalendar DATE-TIME은 초 단위로 내림한다. 내림 후 구간의
시작·종료 시각이 같아지면 유효하지 않다. 단일 시점에는 임의 지속 시간을 만들지 않는다.

### `UTC_INSTANT`

`startInstant`와 `endInstant`는 명시적인 오프셋이 있는 RFC 3339 시각이다. 초는 `00`부터
`59`까지만 허용하며 윤초는 지원하지 않는다. CAL은 같은 시각을 UTC로 정규화해
`DTSTART:...Z`, `DTEND:...Z`로 투영한다.

### `UTC_POINT`

`atInstant`는 명시적인 오프셋이 있는 RFC 3339 시각이며 `UTC_INSTANT`와 같은 초·윤초 규칙을
따른다. CAL은 같은 시각을 UTC로 정규화한 `DTSTART:...Z`만 투영하고 `DTEND`를 만들지 않는다.

### `ZONED_LOCAL`

`startLocal`과 `endLocal`은 오프셋이나 `Z`가 없는 ISO 현지 날짜-시간이고 `zoneId`는 Java
`ZoneId.getAvailableZoneIds()`에 있고 iCal4j가 같은 식별자로 보존하는 이름이 지정된 TZDB
식별자다. `+09:00` 같은 숫자 오프셋과 다른 정식 식별자로 변환되는 별칭은 허용하지 않는다.
CAL은 현지 시각 필드를 UTC 시각으로 바꾸지 않고 동일한 `TZID` 매개변수로 투영한다.

### `ZONED_LOCAL_POINT`

`atLocal`과 `zoneId`는 `ZONED_LOCAL`과 같은 현지 시각·시간대 규칙을 따른다. CAL은 동일한
`TZID`가 붙은 `DTSTART`만 투영하고 `DTEND`를 만들지 않는다.

### `ALL_DAY`

`startDate`와 `endDate`는 네 자리 연도를 쓰는 ISO 날짜다. `endDate`는 미포함 경계이고
`startDate`보다 뒤에 있어야 한다. CAL은 둘을 `VALUE=DATE`인 `DTSTART`와 `DTEND`로 투영하며 자정 시각이나
시간대를 임의로 만들지 않는다.

BATON은 시간대의 DST 공백에 들어가지 않는 현지 시각 값을 보내야 한다. 중첩 구간에서 특정 시각이
중요하면 `ZONED_LOCAL`·`ZONED_LOCAL_POINT`를 쓰지 않고 UTC 형태를 쓴다. CAL은 시간대 정책,
지속 시간 또는
오프셋을 다시 선택하지 않는다. 피드에는 사용하는 시간대마다 iCal4j 4.2.5
`TimeZoneRegistryFactory`가 만든 레지스트리의 전체 `VTIMEZONE` 하나를 TZID 오름차순으로
포함한다. 같은
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
- 구간 형태는 `DTSTART`와 `DTEND`, 시점 형태는 `DTSTART`만 사용한다. 종일 형태는 두 속성에
  `VALUE=DATE`를 사용한다.

VCALENDAR의 고정 속성은 아래 순서다.

1. `PRODID:-//BATON//BATON CAL//EN`
2. `VERSION:2.0`
3. `CALSCALE:GREGORIAN`
4. `X-WR-CALNAME:BATON season {seasonId}`

그 뒤 `VTIMEZONE`을 `TZID` 오름차순, `VEVENT`를 UID의 Unicode 코드 포인트 오름차순으로
정렬한다. VEVENT 속성은 `UID`, `DTSTAMP`, `LAST-MODIFIED`, `SEQUENCE`, `STATUS`,
`DTSTART`, `DTEND`, `SUMMARY`, `DESCRIPTION`, `LOCATION` 순서다. 입력 TEXT는 위 문자 규칙을
통과해야 하며 iCal4j가 역슬래시, 쉼표, 세미콜론과 LF를 RFC 5545 규칙으로 이스케이프한다.
`CalendarOutputter`의 UTF-8, CRLF와 마지막 CRLF를 사용하며 줄 접기 길이 25로 모든 물리 줄을
75 옥텟 이하로 고정한다.

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
- `feedUrl`은 CAL의 공개 기준 URL을 사용한다. 외부 또는 비루프백 기준 URL은 HTTPS만 허용하고,
  루프백 HTTP는 로컬 개발에서만 허용한다. `prod` 프로필은 `BATON_CAL_PUBLIC_BASE_URL`을 반드시
  명시해야 하며 값이 HTTPS가 아니면 애플리케이션 시작에 실패한다.
- 생성/회전에는 멱등성 키나 동일 응답 재사용이 없다. 생성은 성공할 때마다 새
  구독을 만들고 회전은 호출할 때마다 새 토큰으로 바꾼다.
- `calendar_subscription`은 현재 `token_hash` 하나만 가진다. 회전은 현재 해시를 조건으로
  한 원자적 비교 후 설정(CAS)으로 새 다이제스트를 교체하며 과거 다이제스트 행을 보존하지 않는다.
- 각 구독은 생성 또는 회전 성공 시점의 런타임 구독 세대 UUID를 저장한다. 공개 조회는 구독 상태가
  `ACTIVE`이고 토큰 해시가 일치하며 저장된 세대가 현재 `BATON_CAL_SUBSCRIPTION_GENERATION`과
  일치할 때만 성공한다. 세대가 다른 토큰은 알 수 없거나 폐기된 토큰과 구분하지 않는다.
- 회전 응답 뒤에는 새 토큰만 성공하고 이전 URL은 구분 없는 `404`를 반환한다.
- 생성 응답을 잃은 경우 동일 응답을 안전하게 다시 받는 계약이 없다는 점은 MVP의 명시적인 제한이다.
  BATON은 성공 여부가 불확실한 생성을 맹목적으로 재시도하지 않고 사용자에게 재발급 절차를 안내한다.
- DELETE는 구독과 모든 토큰을 폐기하는 멱등 연산이다. 응답 이후 이전
  URL은 성공할 수 없다.

### 복원 펜스

`BATON_CAL_SUBSCRIPTION_GENERATION`은 비밀이 아닌 외부 런타임 UUID다. 정상 재시작과 일반
배포에서는 같은 값을 유지한다. 호환용 초기값은 `00000000-0000-0000-0000-000000000001`이며,
`prod` 프로필은 외부 환경에서 값을 명시하지 않으면 시작을 거부한다. NIL UUID
`00000000-0000-0000-0000-000000000000`은 허용하지 않는다.

구독 세대를 처음 추가하는 V4는 모든 pre-V4 CAL 인스턴스를 중지한 뒤 호환용 초기값으로 신버전만
시작하는 유지보수 배포다. V4 이후에는 세대 조건을 알지 못하는 pre-V4 바이너리를 실행하거나 그
버전으로 롤백하지 않는다. V4 적용 중 구·신 버전을 함께 서비스하지 않으며, 실패하면 신버전으로
전진 수정하거나 신버전으로 아래 복원 절차를 수행한다.

V5는 더 이상 읽지 않는 `season_feed_projection.item_count`를 제거한다. 모든 pre-V5 CAL
인스턴스를 중지한 뒤 신버전만 시작하는 유지보수 배포로 적용하고, 이후 pre-V5 롤백과 구·신 버전
공존은 금지한다. 실패하면 신버전으로 전진 수정한다.

V6는 시점·종일 일정 열과 시간 형태를 추가한다. 기존 두 구간 형태의 행은 그대로 유효하지만,
pre-V6 인스턴스는 새 열거형을 읽을 수 없다. 모든 pre-V6 인스턴스가 종료되기 전에는 BATON이
새 시간 형태를 보내지 않으며, 새 형태를 수신한 뒤에는 pre-V6 롤백과 구·신 버전 공존을 금지한다.

과거 DB 백업을 복원할 때는 CAL 또는 복원 DB를 서비스하기 전에 외부 설정부터 이전에 사용하지 않은
새 세대로 바꿔야 한다. 이전 세대를 재사용하면 복원된 토큰이 다시 유효해질 수 있으므로 금지한다.
복원 DB에 남은 구독은 세대 불일치로 즉시 본문 없는 일반 `404`를 반환한다.

구독 세대는 자격 증명 부활만 막는다. 백업 이후 BATON 원본 변경은 복원 DB에 없으므로, BATON이
모든 시즌의 최신 전체 스냅샷을 다시 전달해야 원본 최신성을 회복한다. 복원 DB에만 활성 상태로 남을
항목의 `CANCELLED` 스냅샷까지 포함해 재전달 완료를 확인하기 전에는 현재 세대 자격 증명을 발급하지
않는다. 그 뒤 기존 `subscriptionId`를 rotate하거나 새 구독을 create한다. CAL 재구축만으로는 백업
이후 상태를 복원할 수 없다.

MVP에는 BATON 전체 시즌의 재전달 완료를 표현하는 매니페스트·완료 신호나 복구 모드가 없다. 따라서
CAL은 재생 전 구독 create·rotate를 자동 차단하지 않으며, BATON 또는 운영 오케스트레이션이 위 순서를
보장해야 한다. 저장소 복원 훈련은 대표 계약 픽스처로 이 절차를 검증하지만 전체 원본 최신성이나
운영 복구 완료를 판정하지 않는다.

## HTTP 경로

### 내부 인증과 공통 오류

모든 `/internal/api/v1/**` 경로는 `Authorization: Bearer {internalToken}`을 요구한다.
`internalToken`은 BATON→CAL 호출만을 위해 배포 비밀값으로 주입하는 32자 이상의 고엔트로피
자격 증명이다. 정상 상태에서는 현재 값 `BATON_CAL_INTERNAL_TOKEN` 하나만 허용한다. 회전 창에서는
이 값과 선택적 이전 값 `BATON_CAL_PREVIOUS_INTERNAL_TOKEN`을 합쳐 최대 두 개만 허용하며, 제시된
자격 증명은 설정된 모든 값과 상수 시간으로 비교한다. 이전 값은 BATON 호출자가 새 값으로 전환한 뒤
즉시 제거하고, 임의 개수의 토큰 목록이나 장기 유예 수단으로 사용하지 않는다. 선택적 이전 값을 빈
문자열로 설정하면 시작을 거부한다. 운영 값은 `openssl rand -hex 32`로 발급한다.

BATON 최종 사용자 Bearer 토큰, 워크스페이스 키 또는 세션을 재사용하지 않는다. 자격 증명을
누락하거나 허용된 값과 모두 다르면 `401`과 `UNAUTHORIZED` 오류를 반환한다.

오류는 달리 명시하지 않으면 `application/json`과 `api-error.v1`의 `{code, message}`를 쓴다.
응답과 애플리케이션 로그에는 토큰이나 피드 URL을 넣지 않는다.

JSON 요청의 전체 문서는 공백과 구조를 포함해 128 KiB(131,072바이트) 이하여야 한다. 이 제한은
DTO·JSON Schema의 개별 필드 제약과 별도로 JSON 파서에서 먼저 적용한다. 초과하면
`{"code":"REQUEST_TOO_LARGE","message":"request body exceeds the maximum size"}`로 고정한
오류 본문을 반환한다.

| 상태 | 코드 | 의미 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | 잘못된 JSON, Bean Validation 또는 시간 형식/도메인 검증 실패 |
| `401` | `UNAUTHORIZED` | 내부 Bearer 자격 증명 누락 또는 불일치 |
| `404` | `RESOURCE_NOT_FOUND` | 내부 구독 자원이 없거나 활성 상태가 아님 |
| `409` | `EVENT_ID_CONFLICT` | 같은 이벤트 식별자가 다른 원본 스냅샷을 가리킴 |
| `409` | `SOURCE_REVISION_CONFLICT` | 같은 항목/개정 번호가 다른 원본 내용을 가리킴 |
| `409` | `SOURCE_ITEM_SCOPE_CONFLICT` | 같은 원본 항목을 다른 시즌에 재사용함 |
| `409` | `SUBSCRIPTION_CONFLICT` | 회전/폐기 원자적 비교 후 설정(CAS)의 동시성 충돌 |
| `413` | `REQUEST_TOO_LARGE` | JSON 전체 문서가 128 KiB(131,072바이트) 상한을 넘음 |
| `500` | `INTERNAL_ERROR` | 호출자에게 내부 세부 정보를 노출하지 않는 예상 밖 실패 |

스냅샷 전달은 네트워크 실패나 `5xx`만 재시도 간격을 두고 재시도하며 `4xx`는 계약/설정
오류로 처리한다. `413`은 요청을 상한 이하로 줄이기 전에는 재시도하지 않는다. 보류한 요청
제한을 도입하면 `429`도 재시도 가능한 상태에 추가한다.

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

- `ACTIVE` 상태·토큰 해시·현재 구독 세대가 모두 일치하는 토큰: `200 OK`
- `Content-Type: text/calendar; charset=utf-8`
- `Cache-Control: no-cache, private`
- `Content-Disposition: inline; filename=\"baton-calendar.ics\"`
- 정규 `ETag`와 `Last-Modified`
- 조건부 일치: 본문 없는 `304 Not Modified`
- 형식이 잘못되었거나, 알 수 없거나, 회전·폐기되었거나 현재 구독 세대와 다른 토큰은 구분하지 않고
  본문 없는 `404 Not Found`를 반환한다.

공개 경로는 읽기 전용이다. 토큰 조회, 피드 렌더링 또는 조건부 GET이 BATON 상태를
변경하거나 BATON API를 호출하지 않는다.

관측 시스템의 고카디널리티 `http.url`에는 실제 요청 토큰을 넣지 않는다. 정상 조회뿐 아니라 형식이
잘못되었거나 알 수 없는 `/calendars/v1/**` 요청도 `/calendars/v1/{token}.ics` 템플릿으로 기록한다.

## 호환성 및 수용 기준 픽스처

구현 수용 기준은 다음 골든/계약 테스트다.

1. UTC 확정 일정 생성, 더 높은 개정 번호의 시간/텍스트 갱신과 정확히 같은 중복.
2. 시간대가 있는 현지 시각의 DST 경계와 자정을 지나는 이벤트. 공백 구간 거부와 UTC 시각/현지 시각 비변환 확인.
3. UTC·시간대 지정 시점은 `DTSTART`만, 종일 일정은 배타적 종료 날짜와 `VALUE=DATE`를 사용하고
   임의 지속 시간이나 자정 시각을 만들지 않는다.
4. 보지 못한 더 낮은 개정 번호의 `STALE`, 같은 개정 번호의 다른 내용에 대한 `409`, 개정 번호 간격 적용.
5. 전체 취소, 안정적인 UID, 정확한 SEQUENCE와 무기한 취소 표식.
6. 쉼표/세미콜론/역슬래시/줄 바꿈 이스케이프, UTF-8 NFC, 75 옥텟 줄 접기와 CRLF.
7. 같은 입력에서 항상 같은 시간대/이벤트/속성 순서, 바이트가 안정적인 재구축, ETag와 Last-Modified.
8. `If-None-Match` 우선순위, 본문 없는 `304`, 빈 피드와 공개 경로의 구분 없는 `404`.
9. 토큰 원문의 일회성 노출, 다이제스트만 저장, 로그 비노출 처리, 원자적 회전과
   즉시 폐기.
10. 외부 구독 세대를 유지한 정상 재시작, 새 세대의 생성·회전, 과거 세대 토큰의 일반 `404`와
   현재 세대 토큰 재발급.
11. BATON 커밋 이후 전달, CAL 트랜잭션 실패, 재시도와 복구.
12. DTO·JSON Schema의 필드 제약 위반과 JSON 전체 문서 128 KiB 초과를 구분하고, 초과 문서에
    고정된 `413 REQUEST_TOO_LARGE` 오류를 반환한다.
13. 공개 기준 URL의 HTTPS·루프백 규칙과 `prod` 시작 실패를 검증하고, Tomcat 접근 로그의
    기본 비활성·안전 패턴, `prod`의 `StatementCreatorUtils` 비활성과 공개 경로 `http.url`의
    토큰 비노출을 고정한다.
14. GitHub Actions가 `main` 푸시와 풀 리퀘스트에서 Java 25로 테스트와 OCI 이미지를 만들고,
    `prod` 프로필·PostgreSQL·Flyway V1~V6·DB 포함 준비 상태·비루트 실행·SIGTERM 종료 코드 143을
    실제 컨테이너로 검증한다. 같은 외부 구독 세대로 컨테이너를 강제 재생성한 뒤에도 기존 공개
    피드가 `200`인지 검증한다.
15. 같은 OCI 스모크가 세대 A의 DB를 `pg_dump -Fc`로 백업해 아카이브를 확인하고, 애플리케이션이
    중지된 상태에서 세대 B를 먼저 설정한 뒤 `pg_restore --clean --create --exit-on-error`로 복원한다.
    복원된 토큰은 본문 없는 일반 `404`이며, 대표 최신 변경·취소 픽스처를 다시 받은 뒤에만 기존
    구독을 rotate한다. 새 토큰은 `200`과 `STATUS:CANCELLED`·`SEQUENCE:3`을 반환한다.
16. 일정 수신 결과, 구독 생성·회전, 투영 재구축과 공통 오류의 실제 MockMvc 응답 JSON을 각
    Draft 2020-12 응답 스키마에 직접 대조한다. 예제뿐 아니라 실제 직렬화 결과의 필드 누락과
    예고 없는 추가도 실패로 처리한다.
17. `contracts/VERSION`의 `1.0.0`을 단일 버전 원천으로 사용해 Gradle 표준 `contractsZip`
    작업이 `contracts/**`와 이 PRD를 파일 시각·항목 순서·권한이 고정된
    `baton-cal-contracts-1.0.0.zip`으로 만든다. ZIP 내부 `contracts/VERSION`, 파일명과
    태그 `contracts-v1.0.0`은 같은 버전을 가리킨다. 별도 체크섬이나 자체 매니페스트는 만들지
    않는다. GitHub Actions는 `retention-days: 90` 보존을 요청하는 변경 검토용 임시 산출물로
    업로드하며, 실제 만료는 저장소·조직 정책을 따른다.

## 현재 구현 상태와 출시 조건

Kotlin/Spring MVC 실행 기반, PostgreSQL/Flyway 영속성 계층, iCal4j 투영과 위 HTTP 경로가
구현되어 있다. CAL 저장소는 JSON Schema와 모든 예시를 자동 검증하고 실제 MockMvc 응답을 각
응답 스키마에 직접 대조한다. 시간대 일정의
`ACTIVE` 개정 번호 0 → `ACTIVE` 개정 번호 2 → `CANCELLED` 개정 번호 3 생명주기를 실제
수신 경로로 실행한다. UTC·시간대 지정 시점과 종일 일정도 같은 경로로 수신하고 PostgreSQL 왕복과
정규 iCalendar 표현을 검증한다. TEXT 이스케이프와 4바이트 Unicode 줄 접기 경계의 정규 `.ics` 골든,
의존성 잠금과 골든 검토 절차도 갖춘다. JSON 전체 문서의 128 KiB 파서 상한과 고정 `413` 오류,
공개 기준 URL의 HTTPS·루프백 규칙, `prod` 시작 검증과 안전한 애플리케이션 로그 기본값도
구현되어 있다. 내부 Bearer는 현재 값과 회전 창의 선택적 이전 값만 허용하고, 공개 캘린더 경로의
고카디널리티 `http.url`은 토큰이 없는 템플릿으로 기록한다. 공개 구독은 현재 외부 런타임 세대와
일치해야 하므로 과거 DB 복원으로 이전 토큰이 되살아나지 않는다. GitHub Actions는 Java 25로
테스트와 OCI 이미지를 만들고 실제 `prod` 컨테이너의 PostgreSQL 연결, Flyway V1~V6, 준비 상태,
비루트 실행과 종료, 같은 구독 세대의 컨테이너 재생성 뒤 기존 공개 피드 유지를 검증한다. 같은
스모크는 `pg_dump -Fc` 아카이브와
`pg_restore --clean --create --exit-on-error` 실제 복원, 시작 전 세대 교체, 복원 토큰의 일반
`404`, 대표 최신 변경·취소 재전달 뒤 새 토큰의 취소 피드까지 실행한다. Gradle은
`contracts/VERSION`의 `1.0.0`을 단일 원천으로 사용해 `contracts/**`와 이 PRD를 같은 입력에서
같은 바이트가 되는 `baton-cal-contracts-1.0.0.zip`으로 만들며, GitHub Actions는
`retention-days: 90`으로 변경 검토용 보존을 요청한다. 실제 만료는 저장소·조직 정책을 따르며,
이 임시 산출물은 안정적인 BATON 의존성이 아니다. 불변 `rc.2` 계약을 고정한 BATON 운영
직렬화기와 실제 CAL 컨테이너 교차 서비스 테스트가 계약 의미 변경 없이 통과해 안정 버전으로
승격했다.

이는 실제 운영 활성화나 운영 준비 완료를 뜻하지 않는다. 다음은 공개 배포 전에 해결해야 하는
보류 항목이다.

- 불변 `contracts-v1.0.0-rc.1`은 게시됐지만 BATON 원본에 필요한 시점·종일 표현이 없어 변경하지
  않는다. 불변 `contracts-v1.0.0-rc.2`는 병합 커밋
  `730ae49a8b8eccf10e8f84f93b8a6a9d0fd24549`와 자산 SHA-256
  `75120a7d21b6ea78c1e8bdab60829899525c1607262119053ea5904b57bd1eaf`에 고정되어 있고,
  `gh release verify`, `gh release verify-asset` 검증을 통과했다. BATON은 이 버전을 고정해 실제
  직렬화기, 전역 `sourceItemId` 비재사용, 개정 번호·원본 갱신 시각 전진, 명시적인 취소와 원본
  커밋 이후 발행을 생산자 테스트와 실제 CAL 컨테이너로 검증했다. 동일한 계약 의미의 안정
  `contracts-v1.0.0` 릴리스 게시와 BATON의 안정 자산 고정은 아직 남아 있다.
- 실제 비밀 관리 시스템에 내부 Bearer를 연결하고 위의 두 값 회전 절차를 배포 환경에서 훈련하는
  작업과 실제 운영 HTTPS 인증서·종단 설정.
- 실제 BATON 전체 시즌의 매니페스트·재전달 완료 신호와 필요 시 재생 전 create·rotate 자동 차단
  계약. 운영 RTO/RPO, 백업 저장소와 암호화, 비밀 관리 시스템을 포함한 실제 배포 환경 복원 훈련.
- 공개 요청 제한 수치와 접근 감사 기록 보존 정책. 애플리케이션 관측 규약과 별개로 실제 역방향
  프록시와 추적 내보내기가 토큰 경로·쿼리·헤더를 남기지 않는지 확인하는 배포 환경 비노출 검증.
- 실제 이미지 레지스트리와 배포 환경의 운영 절차서. 로컬 PostgreSQL 실행, 상태 확인,
  빌드/테스트와 OCI 이미지 스모크·대표 복원 훈련 명령은 README에 기록되어 있다.
