# ADR-0002: Kotlin Spring MVC와 PostgreSQL 기술 스택

- 상태: Accepted
- 결정일: 2026-08-11
- 관련 문서: ADR-0001, PRD-0002

## 맥락

BATON CAL MVP는 다음 성격을 가진다.

- after-commit, at-least-once HTTP snapshot을 짧은 transaction으로 멱등 처리한다.
- inbox, latest accepted snapshot, projection과 hashed token lifecycle에 관계형 constraint와
  원자적 갱신이 필요하다.
- public calendar client의 반복 GET에 deterministic iCalendar bytes와 conditional validator를
  반환한다.
- recurrence나 deadline 계산, provider API, 양방향 sync와 streaming transport는 하지 않는다.

runtime 선택은 PRD-0002의 snapshot, subscription, rebuild와 calendar compatibility 계약을
보존해야 한다.

## 결정

### Application runtime

- Kotlin/JVM 2.3.21과 Gradle 9.6.1 Kotlin DSL을 쓴다.
- Java 25 toolchain, JVM target과 runtime을 기준으로 한다.
- Spring Boot 4.1.0과 blocking Spring MVC를 쓴다.
- JSON binding과 validation은 Spring MVC의 Jackson/Bean Validation integration을 쓴다.
- internal API 인증은 deployment secret으로 주입한 CAL 전용 bearer token을 Spring MVC
  filter에서 constant-time 비교한다. end-user session이나 BATON account token을 재사용하지
  않는다.
- 시간 의존 application code에는 `java.time.Clock`을 주입한다. domain code에서 직접
  `Instant.now()`나 system default timezone을 읽지 않는다.

public GET, internal command와 PostgreSQL access가 모두 blocking이고 request 하나의 작업이
짧은 transaction 또는 byte rendering으로 끝나므로 WebFlux나 coroutine 기반 persistence를
첫 MVP에 넣지 않는다.

### Persistence

- PostgreSQL 18.4를 유일한 durable store로 쓴다.
- schema migration의 유일한 authority는 Flyway다.
- persistence access는 Spring `JdbcClient`와 명시적인 SQL을 쓴다.
- JPA/Hibernate schema generation과 entity lifecycle을 사용하지 않는다.
- inbox `event_id` uniqueness와 하나의 item에 대한 latest revision, subscription/token state를
  database constraint로 보호한다. 서로 다른 envelope id로 온 exact duplicate는 모두 inbox에
  남겨야 하므로 `(source_item_id, source_revision)` 동일성은 season lock 안의 payload hash
  비교로 판정한다.
- snapshot disposition 판정과 accepted snapshot/projection 교체는 한 transaction이다.
- timestamp는 PostgreSQL 정밀도와 같은 microsecond로 canonicalize해 fingerprint, 비교와
  저장 사이의 precision drift를 막는다.
- subscription row는 현재 token hash 하나만 보관한다. rotation은 현재 hash를 조건으로 한
  compare-and-set update로 새 digest를 한 transaction에서 교체하며 과거 digest를 남기지 않는다.
- rebuild는 season 단위 database lock을 잡고 latest accepted full snapshot에서 새 projection을
  만든 뒤 원자적으로 교체한다. public GET이 중간 상태를 관찰하지 않게 한다.

Redis, 별도 cache, message broker와 BATON database 직접 조회는 MVP에 포함하지 않는다.
conditional GET은 canonical bytes와 PostgreSQL projection state만으로 처리한다.

### iCalendar

- iCal4j 4.2.5의 `Calendar`, `PropertyList`, `ComponentList`, `VEvent`와 property type으로
  calendar model을 만들고 `CalendarOutputter`로 TEXT escaping, DATE-TIME, UTF-8, CRLF와 마지막
  CRLF를 직렬화한다. 생성 결과를 다시 parse하는 runtime validation은 하지 않는다.
- CAL은 property/component를 정렬된 list로 전달해 UID와 출력 순서를 결정한다. iCal4j 4.2.5의
  folding은 UTF-8 octet이 아니라 UTF-16 문자를 세므로 fold length를 25로 고정해 continuation
  space를 포함한 모든 물리 줄이 75 octet 이하가 되게 한다.
- `TimeZoneRegistryImpl`이 pin된 iCal4j zone definition으로 사용하는 TZID의 전체 `VTIMEZONE`을
  만든다. JVM `ZoneRules`를 복제해 transition component를 직접 만들지 않는다.
- SHA-256 lowercase hex는 Java `MessageDigest`와 `HexFormat`을 쓴다.
- iCal4j direct version, Java 25 toolchain과 transitive dependency는 build와
  `gradle.lockfile`에서 pin한다. TZDB update/golden fixture 절차는 production release 전
  Deferred 항목이다.
- source `ZONED_LOCAL` field는 Java `ZoneId.getAvailableZoneIds()`의 named TZDB id로 검증하고
  source local time을 CAL이 UTC로 변환하지 않는다.
- source `UTC_INSTANT` field는 같은 instant의 UTC DATE-TIME 초 단위로 정규화해 투영한다.

### Packaging

첫 버전은 하나의 deployable Spring Boot application과 하나의 Gradle module로 시작한다.
현재 package 책임은 다음과 같다.

1. `calendar`: item/time invariant와 canonical iCalendar rendering
2. `snapshot`: fingerprint와 idempotent ingest use case
3. `subscription`: token codec과 create/rotate/revoke/feed lookup use case
4. `projection`: season projection rebuild와 locking orchestration
5. `persistence`: JdbcClient SQL row/repository
6. `web`: internal/public MVC route, DTO, authentication filter와 error mapping
7. `config`: typed runtime configuration과 `Clock`

별도 Gradle module은 실제 결합도나 build 필요가 확인될 때 ADR로 결정한다.

### Verification strategy

- 현재 scaffold는 Kotlin/JUnit platform, Spring MVC test와 Testcontainers PostgreSQL을 쓴다.
- PostgreSQL transaction, uniqueness, duplicate/stale/conflict, rotation과 rebuild는 실제
  PostgreSQL integration test로 검증한다.
- clock-dependent test는 fixed `Clock`과 명시적인 IANA zone을 사용하며 system default timezone에
  의존하지 않는다.
- UTC canonical bytes의 Base64 golden 비교는 현재 scaffold에 있다. JSON example의 schema 자동 검증,
  추가 zoned/cancellation golden과 request/SQL/access log capture 기반 secret redaction test는
  production release 전 Deferred다.

실행 명령은 repository에 실제 Gradle task가 있고 해당 worktree에서 검증한 것만 별도 운영
문서에 기록한다.

## 결과

장점:

- MVC와 JdbcClient의 blocking model이 단순하고 transaction boundary가 분명하다.
- PostgreSQL constraint와 Flyway history가 idempotency, rotation과 rebuild를 durable하게
  만든다.
- ORM 없이 inbox/revision conflict와 token digest lookup SQL을 명시적으로 검토할 수 있다.
- iCal4j model/serializer가 RFC 표현을 맡고 CAL이 정렬과 pin된 writer 설정을 맡아 수동
  escaping, DATE-TIME, timezone serializer를 유지하지 않는다.
- Java 25와 Spring Boot 4.1의 하나의 runtime baseline으로 운영 조합을 줄인다.

비용:

- explicit SQL과 row mapping을 직접 유지해야 한다.
- fold length 25는 ASCII line도 일찍 접으므로 사람이 읽는 feed는 다소 장황할 수 있다.
- pin된 iCal4j zone data나 serializer를 올릴 때 calendar bytes와 ETag가 바뀌는지 검토해야 한다.
- synchronous rebuild가 커지는 시점에는 job 상태와 batch 전략을 새 계약으로 도입해야 한다.

## 보류한 대안

- Spring WebFlux/R2DBC: streaming이나 높은 동시성의 non-blocking dependency가 없고
  transaction/idempotency 복잡도만 늘려 선택하지 않았다.
- Spring Data JPA: aggregate CRUD에는 편하지만 inbox conflict, token digest와 atomic rebuild의
  SQL/locking을 감추므로 첫 버전에는 사용하지 않는다.
- in-memory/H2: PostgreSQL constraint, locking, digest와 migration을 production과 다르게
  만들어 runtime이나 integration test store로 사용하지 않는다.
- Redis response cache: immediate token revocation과 validator invalidation 경로가 하나 더 생겨
  MVP에서는 사용하지 않는다.
- 수동 iCalendar writer: exact 75-octet packing과 event year에 한정한 timezone은 가능하지만
  TEXT escaping, DATE-TIME, CRLF와 zone transition 구현을 중복하므로 선택하지 않았다.
- message broker delivery: 향후 transport가 될 수 있지만 after-commit HTTP retry와 durable
  inbox만으로 첫 계약을 만족하므로 보류한다.
