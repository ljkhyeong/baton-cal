# 표준 API와 불필요한 코드 검토

- 기준: `9ce59be`. 애플리케이션 소스와 의존성 잠금은 `592dc8b` 이후 동일하다.
- 범위: CAL의 Kotlin 운영 코드, 설정, 관련 계약과 기존 테스트.
- 결과: 우선 개선 1건, 소규모 정리 3건. 이번 작업은 검토이며 제품 코드는 수정하지 않았다.

## 1. DB 잠금 오류를 저장소에서 직접 변환 — 우선 개선

[SeasonProjectionLockRepository](../../src/main/kotlin/io/baton/cal/persistence/SeasonProjectionLockRepository.kt)의
52~54행은 `UncategorizedSQLException`의 SQLSTATE `55P03`을 직접 비교해
`CannotAcquireLockException`으로 바꾼다. Spring의 `SQLErrorCodeSQLExceptionTranslator`에는
PostgreSQL의 같은 매핑이 이미 있다.

현재 변환은 시즌 잠금에만 적용된다. [RecoveryManifestRepository](../../src/main/kotlin/io/baton/cal/persistence/RecoveryManifestRepository.kt)의
`lockRecoveryRun()`과 `lockRecoveryState()`에는 없어, 같은 잠금 제한 시간 초과가
`ApiExceptionHandler`의 `503 SERVICE_BUSY` 대신 일반 `500`으로 처리될 수 있다.
이는 코드 경로와 변환기 동작에 근거한 판단이며 실제 복구 HTTP의 잠금 시간 초과는 재현하지 않았다.

**변경안:** 현재 `JdbcTemplate`에 PostgreSQL용 표준 예외 변환기를 설정하고 저장소의 수동 catch와
SQLSTATE 상수를 제거한다. 단순히 catch만 삭제하면 안 된다. 전역 설정 시 다른 SQL 오류의 변환도
바뀔 수 있으므로 기존 중복 키·잠금·쿼리 제한 시간 처리를 확인한다.

**확인한 근거:** 고정된 Spring JDBC 7.0.9로 `SQLException`(`55P03`)을 입력했다.
기본 `SQLExceptionSubclassTranslator`는 변환 결과가 없었고, PostgreSQL용
`SQLErrorCodeSQLExceptionTranslator`는 `CannotAcquireLockException`을 반환했다.
DB 연결 없이 임시 Java 프로그램에서 확인했다.
[Spring 예외 변환 API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/support/SQLErrorCodeSQLExceptionTranslator.html)

**수정 시 검증:** 기존 `PersistenceRepositoryTest`의 시즌 잠금과 `ApiExceptionHandlerTest`,
복구 잠금 제한 시간 초과의 실제 HTTP `503`·`Retry-After` 확인.

## 2. 복구 항목의 수동 ResultSet 매핑 — 소규모 정리

[RecoveryManifestRepository](../../src/main/kotlin/io/baton/cal/persistence/RecoveryManifestRepository.kt)의
76~82행은 UUID·정수·문자열을 하나씩 읽어 `RecoveryItemState`를 만든다. 다른 저장소는 이미
`JdbcClient.query(타입::class.java)`의 기본 행 매핑을 사용한다.

**변경안:** SELECT에서 `inbox.payload_hash AS payload_digest`로 별칭을 지정하고
`.query(RecoveryItemState::class.java)`로 바꾼다. 별도 매퍼 클래스나 공용 래퍼는 필요 없다.
[Spring JdbcClient API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/core/simple/JdbcClient.StatementSpec.html)

타입 기반 `query(Class<T>)`는 `MappedQuerySpec<@Nullable T>`를 반환하므로 뒤의
`requireNoNulls()`는 유지한다. 현재 직접 객체를 만드는 콜백에서만 보면 불필요한 null 검사지만,
표준 매핑으로 바꾸면 필요한 타입 변환이 되므로 별도 삭제 항목으로 세지 않았다.

**수정 시 검증:** 기존 `RecoveryManifestHttpTest`의 빈 시즌·항목 있는 시즌 상태와 다이제스트 비교.

## 3. SQL과 Kotlin의 중복 정렬 — 소규모 정리

[RecoveryManifestRepository](../../src/main/kotlin/io/baton/cal/persistence/RecoveryManifestRepository.kt)의
72행과 155행에서 항목·시즌 목록을 정렬한다. 이어서
[RecoveryManifestDigest](../../src/main/kotlin/io/baton/cal/recovery/RecoveryManifestDigest.kt)의
26행과 41행이 UUID 문자열 기준으로 다시 정렬한다. 현재 운영 호출자는 조회 순서를 별도 계약으로 사용하지 않는다.

**변경안:** 조회의 바깥쪽 `ORDER BY item.source_item_id`, `ORDER BY season_id`를 제거하고
다이제스트 함수가 정렬을 담당하게 한다. LATERAL 안에서 대표 수신 기록을 선택하는
`ORDER BY received_at, event_id LIMIT 1`은 이번 정리 대상이 아니다.

DB 실행 계획에 따라 기존 정렬 비용은 다를 수 있어 성능 개선 수치는 제시하지 않는다.
목적은 정렬 책임을 한 곳에 두는 것이다.

**수정 시 검증:** 여러 항목·시즌의 입력 순서가 달라도 같은 다이제스트가 나오는지 확인.

## 4. 복구 완료 직후 재조회와 같은 값 재검증 — 후순위 정리

[RecoveryManifestService](../../src/main/kotlin/io/baton/cal/recovery/RecoveryManifestService.kt)의
100~101행은 같은 `recoveryId`를 트랜잭션 잠금으로 직렬화하고 기존 완료 여부를 먼저 확인한다.
최초 완료 경로는 검증한 요청으로 행을 만들지만, 저장소의 `insertCompletion()`은 INSERT 후 다시
SELECT하고 서비스는 요청에서 방금 넣은 개수·다이제스트를 다시 비교한다.

현재 운영 코드의 `insertCompletion()` 호출자는 이 경로 하나다. 모든 쓰기가 같은 잠금을 거친다는
전제를 유지하면 최초 저장 뒤 재조회와 같은 값 비교를 줄일 수 있다.

**변경안:** 최초 완료는 일반 INSERT 후 저장한 행으로 응답하고, 이미 완료된 요청의 재시도에서만
저장된 개수·다이제스트를 비교한다. DB 기본키 제약과 최초 완료 시각 보존은 유지한다.
잠금 규칙이 바뀌거나 다른 쓰기 경로가 추가되면 이 제안도 다시 검토한다.

**수정 시 검증:** 최초 완료, 동일 요청 재시도, 다른 내용 재시도, 같은 ID의 동시 완료 요청.

## 유지할 구현

- 엄격한 UUID 파싱: JDK·Jackson 기본 파서와 다른 입력 허용 범위를 계약에 맞추는 코드이며,
  실제 파싱에는 이미 Kotlin `Uuid.parseHexDashOrNull()`을 사용한다.
- 날짜·시각 formatter: 초 필수·4자리 연도·오프셋 형식·마이크로초 정규화가 계약이다.
  기본 ISO formatter로 바꾸면 허용 입력이 달라질 수 있다.
- iCal4j 시간대·줄 접기·명시적 `PropertyList`: 출력 바이트·DST·`DTSTAMP` 재현성을 위한 예외다.
- DB 조회의 `requireNotNull`과 대부분의 `requireNoNulls`: nullable 조회 결과를 도메인 타입으로
  변환하는 데 필요하다. HTTP 검증과 DB 무결성 제약도 실패 책임이 다르다.
- `ensureProjection()`의 잠금 전후 조회, 공개 피드의 메타데이터·본문 재확인: 두 조회 사이의
  동시 변경을 처리하므로 단순 중복이 아니다.
- 토큰 전체 세대 비교·설정 오류의 비밀값 비노출: 일반 문자열 비교나 자동 바인딩 검증으로 단순화하지 않는다.
- 스냅샷·복구 다이제스트의 길이 접두 문자열 인코딩: 버전별 바이트 계약이다. 일반 JSON이나
  `DataOutputStream.writeUTF()`로 바꾸면 다른 값이 된다.

## 검증 범위

소스·호출 관계·고정된 라이브러리·관련 테스트를 확인하고 예외 변환기만 좁게 실행했다.
제품 변경이 없어 전체 Gradle 테스트·DB 통합 테스트·이미지 빌드는 실행하지 않았다.
