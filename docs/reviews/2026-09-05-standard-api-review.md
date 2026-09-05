# 표준 API와 불필요한 코드 검토

- 최초 검토: `9ce59be`. 당시 애플리케이션 소스와 의존성 잠금은 `592dc8b` 이후 동일했다.
- 구현 기준: 검토 커밋 `927dce8` 이후 아래 4건을 반영했다.
- 범위: CAL의 Kotlin 운영 코드, JDBC 설정, 복구 처리와 관련 테스트. API 형식과 DB 스키마는 유지했다.

## 반영한 변경

### 1. DB 잠금 오류를 Spring 표준 예외로 변환

[JdbcConfiguration](../../src/main/kotlin/io/baton/cal/config/JdbcConfiguration.kt)에 PostgreSQL용
`SQLErrorCodeSQLExceptionTranslator` 빈을 등록했다. 고정된 Spring Boot 4.1.1의
`JdbcTemplateConfiguration`이 이 빈을 공통 `JdbcTemplate`에 적용하므로 별도 JDBC 클라이언트나
설정 복제는 필요 없다.

[SeasonProjectionLockRepository](../../src/main/kotlin/io/baton/cal/persistence/SeasonProjectionLockRepository.kt)의
SQLSTATE `55P03` 직접 비교와 수동 예외 변환을 제거했다. 이제 복구 실행 잠금과 전체 상태 잠금도
`CannotAcquireLockException`으로 변환돼 기존 HTTP 예외 처리기가 `503 SERVICE_BUSY`와
`Retry-After: 1`을 반환한다. 실제 PostgreSQL 잠금 시간 초과로 두 경로를 확인했다.
[Spring 예외 변환 API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/support/SQLErrorCodeSQLExceptionTranslator.html)

### 2. 복구 항목을 JdbcClient 기본 기능으로 매핑

[RecoveryManifestRepository](../../src/main/kotlin/io/baton/cal/persistence/RecoveryManifestRepository.kt)의
수동 `ResultSet` 매핑을 `.query(RecoveryItemState::class.java)`로 바꿨다. SQL 열에는
`payload_digest` 별칭을 지정해 Kotlin 속성과 맞췄다.

Spring 7의 타입 기반 조회 결과는 nullable 원소를 허용하므로 `requireNoNulls()`는 유지했다.
[Spring JdbcClient API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/core/simple/JdbcClient.StatementSpec.html)

### 3. SQL과 Kotlin의 중복 정렬 제거

복구 항목·시즌 목록의 바깥쪽 `ORDER BY`를 제거했다.
[RecoveryManifestDigest](../../src/main/kotlin/io/baton/cal/recovery/RecoveryManifestDigest.kt)가 기존대로
UUID 문자열 기준으로 정렬한다. 조회 순서는 API 계약이 아니며, 입력 순서를 뒤집어도 항목·시즌
다이제스트가 같은지 검증했다. 대표 수신 기록을 고르는 LATERAL 내부 정렬은 유지했다.

### 4. 복구 완료 직후 재조회와 같은 값 재검증 제거

[RecoveryManifestService](../../src/main/kotlin/io/baton/cal/recovery/RecoveryManifestService.kt)는 실행 ID별
트랜잭션 잠금 안에서 기존 완료 기록을 먼저 확인한다. 최초 완료는 상태 검증 후 일반 INSERT하고
저장에 사용한 행으로 응답한다. 저장 직후 SELECT와 방금 넣은 개수·다이제스트 비교는 제거했다.

재시도는 저장된 완료 내용과 요청을 비교한다. 다른 내용은 `409 RECOVERY_RUN_CONFLICT`이며 같은
내용은 최초 완료 시각을 반환한다. DB 기본키, 마이크로초 정규화, 전체 상태 검증과 잠금은 유지했다.
완료 저장의 운영 호출자는 이 서비스 하나이며, 다른 쓰기 경로를 추가하면 같은 잠금 규칙을 적용해야 한다.

## 유지한 구현

- 엄격한 UUID·날짜·시각 파싱은 표준 파서와 계약의 입력 허용 범위 차이를 보완한다.
- iCal4j 시간대·줄 접기·명시적 `PropertyList`는 출력 바이트·DST·`DTSTAMP` 재현성에 필요하다.
- 조회의 널 처리, HTTP 입력 검증과 DB 무결성 제약은 각각 실패 책임이 다르다.
- 투영 잠금 전후 조회와 공개 피드 재확인은 조회 사이의 동시 변경을 처리한다.
- 토큰 전체 세대 비교와 설정 오류의 비밀값 비노출은 일반 문자열 비교로 대체하지 않는다.
- 스냅샷·복구 다이제스트의 길이 접두 인코딩은 버전별 바이트 계약이다.

## 검증 결과

`927dce8`에 위 구현·테스트를 추가한 미커밋 상태에서 `./gradlew --no-daemon check`를 실행했다.
Java 25.0.3·PostgreSQL 18.6 Testcontainers 환경에서 일반 테스트 116개를 새로 실행해 모두 통과했다.
계약 ZIP은 기존 산출물을 재사용하고 `verifyContractsZip`은 새로 실행해 통과했다.

추가한 회귀 검증은 복구 잠금 2종의 HTTP `503`, SQL 실행 시간 초과의 표준 예외 변환,
동시 완료 요청, 조회 순서에 따른 다이제스트 일치다. 기존 완료 테스트에는 다른 내용의 재시도 거부와
거부 후 최초 응답 보존을 추가했다. 기존 시즌 잠금·DB 무결성·고정 매니페스트 테스트도 통과했다.
이미지·운영 구성·BATON 생산자·외부 캘린더 앱은 이번에 변경하거나 다시 검증하지 않았다.
