# CAL 코드 축소 검토

- 기준: `439044e`, 2026-10-05
- 범위: 운영 코드·테스트·스모크 스크립트·Gradle·운영 설정. 보존할 DB 데이터와 운영 배포가 없는 상태에서
  Kotlin·JDK·Spring 표준 API로 대체할 수 있는 직접 구현과 불필요한 코드를 찾았다.

## 반영한 정리

| 항목 | 커밋 |
| --- | --- |
| 마이그레이션 V1~V8을 최종 스키마의 V1 하나로 통합, 업그레이드 경로 테스트 삭제 | `1054255` |
| 시즌 잠금 테이블을 `pg_advisory_xact_lock`으로 대체 | `17201e4` |
| 경로 UUID 래퍼 타입을 `@InitBinder` 편집기로 대체 | `96ad5f1` |
| 내부 API `no-store`를 `WebContentInterceptor`로 일괄 적용 | `974b7b8` |
| 응답 스키마와 필드가 같은 DTO 제거 | `8a497dd` |
| 수신 충돌 판정의 중복 조회 제거 | `7699182` |
| 스모크 스크립트의 폴링·헤더 파싱·JSON 생성을 curl·jq 기능으로 대체 | `064a0d9` |
| 동시성 테스트의 결과 타입·대기를 `Result`·`CyclicBarrier`로 대체, 인증·UUID 검증 집중 | `712b00e`, `a25daac` |
| 번호별 스냅샷 생성 공유, 시간 형태 왕복 테스트 통합 | `1de9883` |

## 검토 후 유지한 구현

| 항목 | 유지 근거 |
| --- | --- |
| 공개 피드의 서블릿 응답 헤더 직접 설정 | Spring 7.0.9 `ResponseEntity`의 자동 조건부 응답은 `304`에 `Content-Type`을 남기고 Unix epoch `Last-Modified`를 생략한다. 실험으로 확인했다. |
| 구독 세대 설정의 문자열 입력 | 엄격한 UUID 변환기가 실패하면 Spring Boot 바인더가 기본 변환으로 되돌아가 `1-1-1-1-1`을 받는다. |
| 설정 검증의 `require` | Bean Validation 실패 출력에 거부된 내부 토큰 값이 포함된다. |
| 내부 Bearer 필터 | Spring Security에는 고정 Bearer 토큰 인증이 없어 변환기·인증 관리자를 따로 만들어야 하고, 기본 보안 헤더가 공개 피드의 캐시 헤더를 바꾼다. |
| 엄격한 시각 파서 | JDK ISO 형식은 초 생략과 5자리 이상 연도를 허용해 계약 스키마보다 느슨하다. |
| iCal4j 시간대 규칙 캐시 | 입력 검증과 캘린더 출력이 같은 iCal4j 시간대 데이터를 쓴다(ADR-0002). |
| 지표 사전 등록 | 알림 규칙이 결과별 시계열을 0부터 집계해야 한다. |
| 일정 행의 명시적 매개변수 바인딩 | 객체 속성 바인딩은 PostgreSQL 드라이버가 `Instant`·enum 값의 SQL 타입을 추론하지 못한다. |
| `verifyContractsZip` | 과거 계약 ZIP에 `LICENSE`가 빠진 사고를 기대 파일 목록으로 막는다. PRD-0002가 이름으로 참조한다. |

## 계약·설계 변경이 필요한 후보

| 후보 | 영향 | 판단 |
| --- | --- | --- |
| 단건 일정 수신 경로를 묶음 경로로 통합 | BATON 운영 클라이언트 `RestClientCalendarClient`가 단건 경로를 호출하고 안정 계약 `1.0.0`에 포함된다. CAL 운영 코드는 약 15줄 줄지만 테스트 호출 57곳, 스모크·알림 규칙·계약 파일을 바꾸고 BATON 전환이 먼저 필요하다. | 보류. BATON이 묶음 전송으로 바꾼 뒤 호환을 깨는 다음 계약 버전(`2.0.0`)에서 다시 판단한다. |
| 일정 시간 열을 JSONB 하나로 저장 | 매핑·제약 약 150줄이 줄지만 시간 형태를 보장하는 DB 제약 73줄이 사라진다. 도메인 검증과 DB 무결성 검증을 따로 유지하는 저장소 지침과 충돌한다. | 채택하지 않는다. |

## 2차 정리 — 2026-10-06

- 기준: `1f6b727`. 범위와 전제는 1차와 같다. 운영 코드·테스트·스모크 스크립트·Gradle·스키마를 7개 관점(웹·설정, 저장소·서비스,
  도메인, 스키마, 테스트, 빌드·스크립트, 미사용 코드)으로 탐색해 후보 62건을 모았다. 후보마다 계약 보존과 고정 버전 실현성
  (Spring Framework 7.0.9·Boot 4.1.1·Jackson 3.1.5·pgjdbc 42.7.13·iCal4j 4.3.0 jar의 `javap` 확인)을 반박 검증했다.
- 운영 코드는 순 313줄, 테스트는 순 500줄, 스모크 스크립트·Compose는 순 98줄 줄었다. HTTP 상태·헤더·오류 `code`,
  iCalendar 바이트·지문과 복구 다이제스트 바이트는 같다. 시즌 이름 짝이 맞지 않는 복구 매니페스트의 `400` `message`만
  Bean Validation 공통 문구(`request is invalid`)로 바뀌었다. V1 스키마는 수신함 열과 제약 표현이 바뀌었다.

### 반영한 정리

| 항목 | 커밋 |
| --- | --- |
| `ApiException` 하위 클래스를 단일 클래스와 팩토리 함수로 평탄화, 내부 오류의 `no-store`를 `WebContentInterceptor` 한 곳에 둠 | `bb5259c` |
| 복구 매니페스트 시즌 이름 짝 검증을 Bean Validation `@AssertTrue`로 이동, 재시도 가능 DB 실패를 `TransientDataAccessException`으로 처리 | `bb5259c` |
| 결과별 카운터 사전 등록 공유, 쿼리 한 문장 조회의 읽기 전용 트랜잭션 제거, UUID 역직렬화의 예외 2단 변환 제거 | `bb5259c` |
| 시간 형태 이름을 도메인 `ScheduleTimeType` 하나로 두고 지문·행 변환이 `ScheduleWindow.type`을 사용 | `303e27e` |
| `CalendarItemRow` 시간 열 기본값과 `copy` 변환, 렌더러의 시간대 조회를 iCal4j 레지스트리로 일원화 | `303e27e` |
| 반영 결과 enum·`applyIfNewer` 중계 제거, advisory lock 3단 구조를 `AdvisoryLockRepository` 하나로 통합 | `303e27e` |
| 수신함의 읽지 않는 `season_id`·`occurred_at` 열과 전달 객체 제거, 시간 형태 CHECK를 `num_nonnulls`·`CASE`로 축약 | `303e27e` |
| 복구 매니페스트 짝 CHECK가 한쪽 `NULL`을 통과시키던 결함 수정 | `4b3389a` |
| 쓰지 않는 Testcontainers JUnit 확장 제거, TLS 테스트의 공유 컨테이너 사용 | `a5c3726` |
| 스파이 빈을 공유 통합 설정의 타입 수준 `@MockitoSpyBean`으로 올리고 전역 Jackson 설정의 반복 검증 정리 | `8714243` |
| Boot 기본값·환경 변수 relaxed binding과 같은 설정 7줄 제거 | `d698d82` |
| OCI 스모크가 운영 Compose 정의를 쓰고, 스모크 대기 루프를 curl 재시도와 `eventually` 함수로 정리 | `c0cbb7d`, `e8a957a`, `b7ad23e` |

### 검토 후 제외한 후보

| 후보 | 제외 근거 |
| --- | --- |
| 구독 INSERT를 `paramSource(row)`와 `'ACTIVE'` 리터럴로 바인딩 | `row.status`를 조용히 무시하게 되어 메서드 의미가 약해진다. |
| `.optional().getOrNull()`을 `.list().singleOrNull()`로 대체 | `optional()`이 `JdbcClient`의 표준 API이고 2행 이상 결과의 방어를 잃는다. |
| `currentDataSeasonIds`의 `.set()` 사용 | 반환 타입이 `Set<UUID?>`로 넓어지고 조회 결과의 널 처리 유지 결정과 충돌한다. |
| Repository 메서드의 `MANDATORY` 가드 제거 | 트랜잭션 밖 호출을 막는 검증이라 잠금 획득 가드와 실패 책임이 다르다. |
| 예외 처리기 로그 테스트를 `OutputCaptureExtension`으로 대체 | 독립 MockMvc 테스트를 단독 실행하면 Logback 기본 DEBUG에서 Spring이 예외 메시지를 콘솔에 남겨 거짓 실패가 난다. |
| 골든 iCalendar 형식 검사를 계약 산출물 테스트로 이동 | 골든이 없는 시즌 이름 테스트가 같은 검사를 계속 써서 코드가 줄지 않는다. |
| 렌더러 `displayName` 기본값과 공개 피드 행의 `seasonId` 제거 | 운영 코드가 줄지 않고 테스트 호출만 바뀐다. |
| 투영 Last-Modified 판정을 조건부 upsert SQL로 이동 | 규칙이 Kotlin과 SQL로 나뉜다. 서비스 인라인과 갱신 조건 축약으로 대신했다. |
| 수신함 INSERT와 중복 조회를 데이터 변경 CTE로 통합 | 자기 행 제외가 CTE 스냅샷 가시성이라는 암묵적 DB 의미에 기댄다. |
| 반복 CHECK를 `CREATE DOMAIN`으로 묶기 | 순감소가 4~5줄이고 DB 타입이라는 개념이 늘어난다. |
| 계약 ZIP의 시각·순서·권한 설정을 Gradle 기본값에 맡기기 | ZIP에 들어가는 `contracts/README.md`가 설정 명시를 설명하고, 권한은 기계별 Gradle 속성의 영향을 받는다. |
| health 상세 숨김·접근 로그 비활성화 설정 제거 | 기본값과 같지만 보안 의도를 드러내는 설정으로 ADR-0002가 기록한다. |
| 완료 후 매니페스트 재시도의 단건 조회를 실행 전체 목록 조회로 대체 | PR #35 리뷰 지적. 시즌별 재전달마다 실행의 매니페스트 전체를 읽어 O(n²)이 되고 복구 실행 잠금 보유 시간이 늘어 기본 키 단건 조회를 유지한다. |
| 구독 생성의 저장 전·후 조회 통합 | 사전 조회가 없으면 다른 시즌의 ID 재사용이 캘린더 생성 실패에서 `409` 대신 다른 오류가 된다. |
