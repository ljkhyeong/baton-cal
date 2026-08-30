# ADR-0002: Kotlin Spring MVC와 PostgreSQL 기술 스택

- 상태: 채택됨
- 결정일: 2026-08-11
- 관련 문서: ADR-0001, PRD-0002

## 맥락

BATON CAL MVP는 다음 특성을 가진다.

- 커밋 후 최소 한 번 전달되는 HTTP 스냅샷을 짧은 트랜잭션으로 멱등 처리한다.
- 수신함, 마지막으로 채택된 스냅샷, 투영과 해시된 토큰 수명주기에 관계형 제약조건과
  원자적 갱신이 필요하다.
- 공개 캘린더 클라이언트의 반복 GET에 같은 입력이면 항상 같은 iCalendar 바이트와 조건부 요청 검증 값을
  반환한다.
- 반복 일정이나 마감 계산, 제공자 API, 양방향 동기화와 스트리밍 전송은 하지 않는다.

실행 환경 선택은 PRD-0002의 스냅샷, 구독, 재구축과 캘린더 호환성 계약을
보존해야 한다.

## 결정

### 애플리케이션 실행 환경

- Kotlin/JVM 2.4.10과 Gradle 9.7.1 Kotlin DSL을 쓴다.
- 컬렉션, 널 처리, 문자열·바이트 인코딩, Base64, 16진수, UUID 문자열 파싱과 파일 편의 기능에는
  Kotlin 표준 라이브러리를 우선한다. 시간, 암호화, URI와 네트워크 등 필요한 JVM 기능은 JDK API를
  사용한다. JDBC·Spring에 전달하는 UUID는 `java.util.UUID` 타입을 유지한다.
- Java 25 툴체인, JVM 대상과 실행 환경을 기준으로 한다.
- Spring Boot 4.1.1과 동기식 Spring MVC를 쓴다.
- JSON 바인딩과 검증은 Spring MVC의 Jackson/Bean Validation 통합 기능을 쓴다.
- Jackson의 읽기 제약으로 JSON 전체 문서를 128 KiB(131,072바이트), 필드명을 64자, 중첩을
  16단계, 숫자를 10자리, 토큰을 256개로 제한한다. 이는 DTO와 JSON Schema의 개별 필드 제약과
  다른 단일 파서 자원 경계이며, Spring MVC 오류 어댑터가 어느 상한의 초과든 고정된
  `413 REQUEST_TOO_LARGE` API 오류로 변환한다. 별도 요청 본문 필터나 자체 JSON 파서는 두지 않는다.
- 내부 API 인증은 배포 비밀값으로 주입한 CAL 전용 Bearer 토큰을 Spring MVC 필터에서 비교한다.
  필수 현재 값과 회전 창에서만 쓰는 선택적 이전 값으로 최대 두 개를 구성하고, 제시된 값은
  일치 여부와 관계없이 설정된 모든 값과 `MessageDigest.isEqual`로 비교한다. BATON이 새 값으로
  전환하면 이전 값을 제거하며 임의 목록이나 장기 유예를 두지 않는다. 최종 사용자 세션이나 BATON
  계정 토큰을 재사용하지 않는다.
- 시간 의존 애플리케이션 코드에는 `java.time.Clock`을 주입한다. 도메인 코드에서 직접
  `Instant.now()`나 시스템 기본 시간대를 읽지 않는다.

공개 GET, 내부 명령과 PostgreSQL 접근이 모두 동기식이고 요청 하나의 작업이
짧은 트랜잭션 또는 바이트 렌더링으로 끝나므로 WebFlux나 코루틴 기반 영속성을
첫 MVP에 넣지 않는다.

### 영속성

- PostgreSQL 18.6을 유일한 영구 저장소로 쓴다.
- 스키마 마이그레이션의 유일한 기준은 Flyway다.
- 영속성 접근에는 Spring `JdbcClient`와 명시적인 SQL을 쓴다.
- JPA/Hibernate 스키마 생성과 엔티티 수명주기를 사용하지 않는다.
- 수신함 `event_id` 고유성과 하나의 일정 항목에 대한 최신 원본 개정 번호, 구독/토큰 상태를
  데이터베이스 제약조건으로 보호한다. 서로 다른 이벤트 봉투 ID로 온 완전 중복은 모두 수신함에
  남겨야 하므로 `(source_item_id, source_revision)` 동일성은 시즌 잠금 안의 페이로드 해시
  비교로 판정한다.
- 스냅샷 처리 결과 판정과 채택된 스냅샷/투영 교체는 한 트랜잭션이다.
- 타임스탬프는 PostgreSQL 정밀도와 같은 마이크로초로 정규화해 지문값, 비교와
  저장 사이의 정밀도 차이를 막는다.
- 구독 행은 현재 토큰 해시 하나만 보관한다. 회전은 현재 해시를 조건으로 갱신해
  새 다이제스트를 한 트랜잭션에서 교체하며 과거 다이제스트를 남기지 않는다.
- 구독 행에는 생성 또는 회전 시점의 외부 런타임 세대 UUID도 저장한다. 공개 조회 SQL은 `ACTIVE`
  상태, 토큰 해시와 현재 세대를 함께 조건으로 사용한다. 따라서 과거 DB를 복원해도 런타임 세대를 먼저
  교체했다면 복원된 토큰이 다시 유효해지지 않는다.
- V4는 기존 행을 호환용 초기 세대로 승격하고 애플리케이션이 이후 세대를 명시해 쓰도록 열 기본값을
  남기지 않는다. pre-V4 쓰기·조회는 이 경계를 이해하지 못하므로 모든 구버전을 중지한 상태에서
  적용하며 V4 이후 pre-V4 롤백과 구·신 버전 공존을 허용하지 않는다.
- V5는 더 이상 읽지 않는 `season_feed_projection.item_count`를 제거한다. 이 열을 참조하는 pre-V5
  인스턴스를 모두 중지한 유지보수 배포로 적용하며, 이후 pre-V5 롤백·공존 대신 신버전으로 전진 수정한다.
- V6는 UTC·시간대 지정 시점과 종일 날짜 구간을 위한 열과 제약을 추가한다. 기존 구간 행은 그대로
  유효하지만 pre-V6 애플리케이션은 새 열거형을 읽을 수 없으므로, 모든 pre-V6 인스턴스가 종료된 뒤
  BATON이 새 시간 형태를 보내며 이후에는 pre-V6와 공존하거나 롤백하지 않는다.
- V7는 시즌별 표시 이름·원본 개정 번호·채택 시각을 `season_calendar_metadata`에 보관한다.
  이름을 항목·구독마다 복제하지 않으며, 일정과 같은 시즌 잠금 안에서 현재 개정 번호를 비교하고
  저장·재구축을 한 트랜잭션으로 처리한다. 입력 형식은 MVC Bean Validation이 맡고 SQL은 행을 매핑한다.
  과거 이름 이력·봉투 수신함·별도 해시는 필요하지 않아 두지 않는다. 채택 시각은 이름만 있는 첫 피드의
  Last-Modified를 결정한다. pre-V7는 재구축 때 이름을 무시하므로 모든 구버전 종료 뒤 이름을 전달하며,
  이름 수신 뒤 구버전 공존·롤백을 허용하지 않는다.
- 재구축은 시즌 단위 데이터베이스 잠금을 잡고 마지막으로 채택된 전체 스냅샷에서 새 투영을
  만든 뒤 원자적으로 교체한다. 공개 GET이 중간 상태를 관찰하지 않게 한다.
- 투영의 ETag가 같으면 기존 Last-Modified를 보존하고 ETag가 달라지면 현재 UTC 시각의 초 단위 값을
  사용한다. 공개 HTTP 경계는 저장값을 요청 처리 시각으로 제한해 응답 Date보다 미래가 되지 않게 한다.
  같은 초의 변경과 시계 역행은 Last-Modified만으로 구분하지 않고 강한 ETag가 정확한 판정을 맡는다.
  이 상한은 [RFC 9110 8.8.2.1절](https://www.rfc-editor.org/rfc/rfc9110.html#section-8.8.2.1)을 따른다.

Redis, 별도 캐시, 메시지 브로커와 BATON 데이터베이스 직접 조회는 MVP에 포함하지 않는다.
조건부 GET은 정규 바이트와 PostgreSQL 투영 상태만으로 처리한다.

### iCalendar

- iCal4j 4.3.0의 `Calendar`, `PropertyList`, `ComponentList`, `VEvent`와 속성 타입으로
  캘린더 모델을 만들고 `CalendarOutputter`로 TEXT 이스케이프, DATE-TIME, UTF-8, CRLF와 마지막
  CRLF를 직렬화한다. 생성 결과를 다시 파싱하는 실행 중 검증은 하지 않는다.
- CAL은 속성과 컴포넌트를 정렬된 목록으로 전달해 UID와 출력 순서를 결정한다. iCal4j 4.3.0의
  줄 접기는 UTF-8 옥텟이 아니라 UTF-16 문자를 세므로 줄 접기 길이를 25로 고정해 연속 줄
  공백을 포함한 모든 물리 줄이 75 옥텟 이하가 되게 한다.
- iCal4j `TimeZoneRegistryFactory`가 만든 레지스트리에서 사용하는 TZID의 전체 `VTIMEZONE`을
  가져온다. JVM `ZoneRules`를 복제해 전환 컴포넌트를 직접 만들지 않는다.
- SHA-256은 JDK `MessageDigest`로 계산하고 소문자 16진수 변환에는 Kotlin `toHexString()`을 쓴다.
- iCal4j 직접 의존 버전, Java 25 툴체인과 전이 의존성은 빌드와 `gradle.lockfile`에서 고정한다.
  `gradle/verification-metadata.xml`의 SHA-256으로 플러그인과 의존성 파일도 검증한다. iCal4j 또는
  내장 Olson 데이터를 올릴 때는 의존성 잠금과 검증 메타데이터, 캘린더 골든 바이트·ETag를 같은
  변경에서 검토하고 의도하지 않은 표현 변경을 자동 승인하지 않는다.
- iCal4j 4.3.0은 만료된 RRULE의 미래 전이 적용, 비반복 전이 누락과 JVM 기본 시간대에 따라 달라지던
  `ZoneRulesBuilder` 동작을 수정했다. CAL은 내장 Olson `2025a` 레지스트리가 원문 TZID를 제공하는지
  한 번 확인하고, 같은 `VTIMEZONE`에서 만든 `ZoneRules`로 DST 공백을 판정한다. Java 런타임 TZDB와
  별도로 교차 검증하거나 RRULE을 직접 해석하지 않는다. 계산한 규칙은 TZID별로 캐시한다.
- 원본 `ZONED_LOCAL`과 `ZONED_LOCAL_POINT` 필드는 iCal4j 내장 Olson `2025a`의 이름 있는 원문
  TZDB ID로 검증하고 원본 로컬 일시를 CAL이 UTC로 변환하지 않는다. 별칭과 숫자 오프셋은 거부한다.
  내장 데이터보다 새로운 TZID와 규칙은 iCal4j 갱신, 의존성 잠금과 골든·ETag 검토를 거친 새 계약
  후보에서 지원한다.
- `UTC_INSTANT`·`ZONED_LOCAL` 구간은 `DTSTART`와 `DTEND`, `UTC_POINT`·`ZONED_LOCAL_POINT`
  단일 시점은 `DTSTART`만 투영한다. `ALL_DAY`는 `VALUE=DATE`인 배타적 시작·종료 날짜를 사용한다.
  CAL은 시점에 임의 지속 시간을, 종일 일정에 자정 시각이나 시간대를 만들지 않는다.

### 패키징

첫 버전은 배포 가능한 Spring Boot 애플리케이션 하나와 Gradle 모듈 하나로 시작한다.
현재 패키지 책임은 다음과 같다.

1. `calendar`: 일정 항목/시간 불변식과 정규 iCalendar 렌더링
2. `snapshot`: 지문값, 멱등 수신과 채택한 항목 상태 조회 사용 사례
3. `subscription`: 토큰 인코딩과 생성/회전/폐기/구독 상태·캘린더 피드 조회 사용 사례
4. `projection`: 시즌 표시 이름 수신, 투영 재구축과 잠금 조정
5. `persistence`: JdbcClient SQL 행과 리포지토리
6. `web`: 내부/공개 MVC 경로, DTO, 인증 필터와 오류 매핑
7. `config`: 타입이 지정된 실행 환경 설정과 `Clock`

별도 Gradle 모듈은 실제 결합도나 빌드 필요가 확인될 때 ADR로 결정한다.

운영 전달 단위는 Spring Boot Gradle 플러그인의 `bootBuildImage`가 Cloud Native Buildpacks로 만드는
OCI 이미지다. Spring Boot가 Java 대상 버전을 기본 Paketo builder에 전달하고 프로젝트명·버전으로
이미지 이름을 정하므로 같은 값을 별도 Gradle 설정으로 반복하지 않는다. builder metadata가 호환
run image를 선택하게 하고, 별도 Dockerfile이나 JRE 조립은 buildpack으로 실행 계약을 만족할 수
없을 때만 새 결정으로 도입한다.

이미지 레지스트리는 GitHub Container Registry를 사용한다. GitHub Actions는 풀 리퀘스트에서 이미지를
로컬 검증만 하고, `main` 푸시에서 스모크를 통과한 같은 이미지를
`ghcr.io/ljkhyeong/baton-cal:{전체 Git 커밋 SHA}`로 게시한다. 가변 `latest` 태그를 만들지 않고 실제
배포는 전체 SHA 태그 또는 레지스트리 digest를 고정한다.

언어 중립 계약 버전의 단일 원천은 `contracts/VERSION`이다. Gradle 표준 `Zip` 작업
`contractsZip`은 이 값에서 `baton-cal-contracts-{version}.zip`을 만든다. ZIP은 `contracts/**`
전체, 루트 MIT `LICENSE`와 JSON Schema 밖의 필드 간 의미, HTTP 상태, 토큰·iCalendar 규칙을
소유하는 PRD-0002만 포함한다. ZIP 내부 `contracts/VERSION`, 파일명의 버전과
`contracts-v{version}` 태그는 같은 버전을 가리킨다. Gradle 아카이브 설정으로 파일 시각 비보존,
재현 가능한 항목 순서와 디렉터리 `0755`·파일 `0644` 권한을 명시해 같은 입력에서 같은 ZIP 바이트를
만든다. `verifyContractsZip`은 생성된 ZIP의 파일명, 내부 버전과 포함 파일 목록을 직접 검증한다.

GitHub Actions의 `upload-artifact`는 이 단일 ZIP에 `retention-days: 90` 보존을 요청하는 변경
검토용 임시 배포 경계다. 실제 만료는 저장소·조직 정책을 따른다. 안정적인 생산자 의존성은 릴리스
대상 변경을 검토해 풀 리퀘스트의 CI를 통과시켜 `main`에 반영한 다음, `main`의 깨끗한
체크아웃에서 저장소의 `Enable release immutability`를 활성화하고 초안 릴리스에 같은 ZIP을 첨부해
사전 릴리스로 게시할 때 성립한다. BATON은 릴리스 증명과 자산을
`gh release verify`, `gh release verify-asset`으로 확인한 사전 릴리스를 고정한다. 생산자 검증
전에는 사전 릴리스로 게시한다. 검증 결과 버전 표식 외 계약 의미를 바꿀 필요가 없으면
`contracts/VERSION`을 안정 버전으로 올려 새 ZIP과 태그를 만들고, 의미 변경이 필요하면 게시된
사전 릴리스를 교체하지 않고 다음 사전 릴리스를 만든다. CI 산출물만으로 생산자 연동이 완료됐다고
판단하지 않는다.

이미 게시된 `contracts-v1.0.0` 자산에 루트 `LICENSE`가 빠진 경우 같은 태그와 자산을 교체하지
않는다. 스키마, 예시, 골든과 PRD 의미를 유지한 `1.0.1` 호환 보완판을 과거 안정 태그에서 별도로
만들고 BATON이 새 태그·자산·SHA-256을 다시 고정한다. 이 브랜치는 `main`에 병합하지 않으므로
풀 리퀘스트의 합성 merge commit 검증과 별도로 정확한 브랜치 HEAD의 계약 ZIP을 검증한다. 새 의미를
담은 `1.1.0-rc.1` 검증은 이 재포장과 분리한다.

서비스 사이에 공유 DTO JAR은 두지 않는다. BATON 구현을 CAL의 Kotlin/JVM 타입과 릴리스 주기에
결합하지 않고 JSON Schema와 예시를 언어 중립 기준으로 유지하기 위해서다. 별도 압축 스크립트,
체크섬 파일이나 자체 매니페스트도 만들지 않고 Gradle 아카이브와 GitHub Actions의 artifact digest를
사용한다.

### 운영 프로필과 로그 경계

- 로컬 실행에는 개발용 PostgreSQL URL·사용자명·비밀번호 기본값을 제공한다. `prod` 프로필은
  `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`를 모두 외부에서 명시하도록 요구해
  개발 연결값으로 운영 애플리케이션이 시작되는 경로를 닫는다.
- 구독 세대는 비밀이 아닌 타입 지정 UUID 설정 `subscriptionGeneration`으로 주입한다. 정상
  재시작에는 같은 값을 유지하고 과거 DB 복원 전에만 새로운 non-NIL UUID로 바꾼다. 호환용 초기값은
  `00000000-0000-0000-0000-000000000001`이고 `prod`는
  `BATON_CAL_SUBSCRIPTION_GENERATION`을 명시적으로 요구한다. UUID 문자열은 Kotlin의
  `Uuid.parseHexDashOrNull`로 36자 표준 형식을 검사하고 NIL 여부는 설정 경계에서 한 번만 검증한다.
  별도 UUID 생성 로직은 애플리케이션에 두지 않는다.
- 복구 모드는 기본 `false`인 `recoveryMode`를 `BATON_CAL_RECOVERY_MODE`에서 Spring Boot 설정
  바인딩으로 읽는다. 구독 서비스의 생성·회전 진입점에서만 검사하고 기존 `ApiException` 처리로
  `503 RECOVERY_IN_PROGRESS`를 반환한다. 복원되는 DB에는 모드를 저장하지 않으며 별도 필터,
  상태 관리 테이블이나 자동 해제 작업은 두지 않는다. 수신·재구축·폐기·공개 조회와 readiness는
  유지한다. 복원 전 모든 인스턴스를 중지하고 새 구독 세대와 모드를 적용하며, 운영자가 전체
  재전달 완료를 확인한 뒤 같은 세대를 유지한 채 모드만 해제해 모든 인스턴스를 재시작한다.
- 공개 피드 URL의 기준 주소는 타입이 지정된 `publicBaseUrl` 설정으로 주입한다. 외부 또는
  비루프백 주소는 HTTPS만 허용하고 루프백 HTTP는 로컬 개발에서만 허용한다.
- `prod` 프로필은 `BATON_CAL_PUBLIC_BASE_URL`을 명시적으로 요구하며 HTTPS가 아니면 시작을
  거부한다. 개발 기본값이 운영으로 승격되지 않게 시작 시점에 불변식을 확인한다.
- Tomcat 접근 로그는 기본적으로 비활성화한다. 설정에 둔 안전 패턴도 요청 경로·쿼리·헤더를
  포함하지 않아, 나중에 접근 로그를 켜더라도 피드 토큰을 기본 형식으로 남기지 않는다.
- JDBC 값 바인딩을 TRACE에서 노출할 수 있는 `StatementCreatorUtils` 로거는 `prod`에서 `OFF`로
  고정한다.
- Hikari 연결 초기 SQL로 PostgreSQL `lock_timeout`을 기본 5초, `statement_timeout`을 기본 30초로
  설정하고 Spring 트랜잭션 기본 제한 시간도 30초로 둔다. 환경 변수로 조정하되 자체 타이머나
  스레드 중단 코드를 만들지 않는다. 잠금·쿼리·트랜잭션 제한 시간 예외는 Spring 예외 계층에서
  `503 SERVICE_BUSY`와 `Retry-After: 1`로 변환한다.
- Spring MVC 서버 요청 관측 규약은 표준 관측 규약의 URL 계산 지점만 확장한다. 공개
  `/calendars/v1/**`의 고카디널리티 `http.url`은 정상·실패 여부와 관계없이
  `/calendars/v1/{token}.ics`로 치환하고, 나머지 표준 관측 태그와 공개 경로가 아닌 URL은 Spring의
  기본 동작을 유지한다.
- Micrometer로 일정 수신 결과, 내부 인증 결과, 투영 재구축 시간·항목 수·표현 바이트와 시즌 잠금
  획득 시간을 기록한다. 태그는 `applied`·`duplicate`·`stale`, `current`·`previous`·`unauthorized`
  처럼 값의 종류가 제한된 결과만 사용하고 토큰·시즌·항목 식별자는 넣지 않는다. 표준 HTTP 서버
  지표로 이미 구분할 수 있는 공개 피드 상태를 별도 카운터로 중복 구현하지 않는다.
- Spring Boot 자동 설정과 Micrometer Prometheus 레지스트리로 `/actuator/prometheus`를 제공한다.
  `prod` 프로필은 관리 서버를 기본 `8081` 포트로 분리한다. 공개 역방향 프록시는 관리 포트를
  라우팅하지 않고 관측 수집기만 내부 네트워크에서 접근하게 한다. 자체 메트릭 직렬화기나 전송기를
  만들지 않는다.
- 애플리케이션 설정은 실제 역방향 프록시와 추적 내보내기의 동작을 증명하지 않는다. 두 경계에서
  경로·쿼리·헤더 삭제 처리를 확인하는 실제 환경 검증은 공개 배포 조건으로 남긴다.

### 검증 전략

- 현재 기본 구성은 Kotlin/JUnit Platform, Spring MVC 테스트와 Testcontainers PostgreSQL을 쓴다.
- Draft 2020-12 계약 산출물은 테스트 범위의 NetworkNT JSON Schema Validator 3.0.6으로 검증한다.
  이는 Jackson 3을 공유하지만 런타임 요청 검증에는 넣지 않는다.
- PostgreSQL 트랜잭션, 고유성, 중복/구버전/충돌, 회전과 재구축은 실제
  PostgreSQL 통합 테스트로 검증한다.
- 시계 의존 테스트는 고정된 `Clock`과 명시적인 IANA 시간대를 사용하며 시스템 기본 시간대에
  의존하지 않는다.
- UTC, 빈 피드, 시간대 지정 취소와 Unicode 줄 접기의 정규 바이트를 Base64 골든으로 비교한다.
  JSON 예시는 JSON Schema로 자동 검증하고 실제 CAL HTTP 경로에서도 취소 후 재활성화를 포함한
  일정 생명주기를 실행한다.
  일정 수신 결과, 구독 생성·회전, 투영 재구축과 공통 오류의 실제 MockMvc 응답도 각 응답 스키마에
  직접 대조해 직렬화 결과의 필드 누락과 예고 없는 추가를 막는다.
- 고정 Clock을 사용해 같은 ETag의 Last-Modified 보존, 다른 ETag의 현재 시각 적용과 공개 응답의
  미래 값 제한을 검증한다. 예상 밖
  예외는 고정 `500` 응답을 반환하고 예외 메시지의 비밀값을 응답·로그에 남기지 않는지 검증한다.
- JSON 문서 상한과 오류 매핑, 공개 기준 URL과 `prod` 시작 불변식은 애플리케이션 테스트로
  검증한다. Tomcat 접근 로그의 기본값·안전 패턴과 `prod`의 `StatementCreatorUtils` 비활성은
  설정 계약으로 고정한다.
- 내부 Bearer의 현재 값·이전 값 허용과 그 밖의 값 거부를 HTTP 테스트로 검증한다. 공개 캘린더의
  정상 경로와 대체 `404` 경로를 실제 서버 요청 관측으로 실행해 고카디널리티 `http.url`에 토큰이
  없고 템플릿만 남는지 검증한다.
- JSON UUID는 스키마와 같은 36자 표준 문자열만 허용하고, 내부 Bearer 설정은 RFC 6750 `b64token`
  문자 범위를 벗어나면 애플리케이션 시작 시 거부한다.
- 애플리케이션 테스트에서 PostgreSQL 잠금·SQL 제한 시간과 Spring 트랜잭션 제한 시간 기본값을
  확인하고, Spring의 쿼리 제한 시간 예외가 고정된 `503 SERVICE_BUSY`와 `Retry-After: 1`로
  변환되는지 검증한다. Micrometer 지표는 기존 성공·중복·역순·인증·동시 잠금 시나리오에서
  증가량만 확인해 같은 도메인 흐름을 중복 구현하지 않는다.
- JUnit `load` 태그의 `projectionLoadTest`는 기본 `test`에서 제외하고, 실제 PostgreSQL에서
  500·1,000·5,000·10,000개 시즌의 전체 투영 재구축 시간과 표현 크기를 필요할 때 반복 측정한다.
  개발 기준과 운영 SLO를 구분하며 런타임·DB·iCal4j가 바뀌면 다시 측정한다.
- 구독 생성·회전이 현재 런타임 세대를 저장하고, 정상 재시작의 같은 세대는 기존 토큰을 유지하며,
  세대 변경 뒤 과거 토큰은 일반 `404`가 되는지 PostgreSQL 통합 테스트로 검증한다.
- 실제 역방향 프록시와 추적 내보내기의 경로·쿼리·헤더 삭제 처리는 배포 환경에서 검증한다.
- GitHub Actions는 `main` 푸시와 풀 리퀘스트에서 Java 25로 테스트와 OCI 이미지를 만든다. 실제
  이미지에 `prod` 설정과 PostgreSQL을 연결해 Java 25, Flyway V1~V7, DB 포함 준비 상태,
  Prometheus 메트릭, 비루트 실행과 35초 유예 안의 SIGTERM 정상 종료를 스모크 검증한다. 종료 상태와
  `OOMKilled=false`, 종료 코드 `0` 또는 `143`을 확인하고 SIGKILL 종료 코드 `137`은 거부한다. 같은 외부
  구독 세대로 컨테이너를 강제 재생성한 뒤 기존 공개 피드가 계속 `200`인지도 확인한다.
- 풀 리퀘스트의 이미지는 게시하지 않는다. `main` 푸시는 스모크를 통과한 동일 이미지만 전체 Git
  커밋 SHA 태그로 GHCR에 게시한다.
- 워크플로의 기본 권한은 저장소 읽기이며 `packages: write`는 `main` 게시 작업에만 부여한다.
  외부 액션은 전체 커밋 SHA로 고정하고 메이저 버전 주석을 함께 둔다. Dependabot은 Gradle,
  GitHub Actions와 Docker Compose 갱신을 매주 제안하며, 자동 병합하지 않는다.
- 같은 스모크는 세대 A의 데이터를 `pg_dump -Fc`로 백업해 아카이브를 확인하고, 애플리케이션을
  중지한 상태에서 세대 B로 먼저 바꾼 뒤 `pg_restore --clean --create --exit-on-error`로 실제
  복원한다. 복원 토큰의 본문 없는 일반 `404`, 대표 계약 픽스처의 최신 변경·취소 재전달,
  재전달 뒤 기존 구독 rotate와 새 토큰 피드의 `STATUS:CANCELLED`·`SEQUENCE:3`을 검증한다.
- 이 저장소 훈련은 대표 픽스처와 절차 순서의 회귀 검증이다. 실제 BATON 전체 매니페스트·재전달
  완료 신호, 운영 RTO/RPO, 백업 저장소·암호화, 비밀 관리 시스템과 실제 환경 복원 훈련은 별도
  운영 경계다.
- 시즌 이름은 실제 HTTP 응답 스키마, 중복·역순·충돌, 이름만 있는 빈 피드와 일정 식별자 보존을
  검증한다. 같은 시즌의 이름·일정 동시 갱신과 렌더링 실패 시 롤백도 확인한다. 복원 훈련은 이름의
  백업 시점 개정 번호 복원과 최신 이름 재전달을 포함한다.

실행 명령은 저장소에 실제 Gradle 작업이 있고 해당 작업 트리에서 검증한 것만 별도 운영
문서에 기록한다.

## 결과

장점:

- MVC와 JdbcClient의 동기식 모델이 단순하고 트랜잭션 경계가 분명하다.
- PostgreSQL 제약조건과 Flyway 변경 이력이 멱등성, 회전과 재구축을 영속적으로
  만든다.
- 외부 런타임 세대를 DB 구독 세대와 함께 비교해 백업 복원으로 폐기·회전된 공개 자격 증명이
  부활하는 경로를 별도 토큰 이력 저장 없이 차단한다.
- 실제 PostgreSQL 논리 백업·복원 스모크가 외부 세대 선교체와 대표 원본 재전달 뒤 자격 증명
  재발급 순서를 OCI 실행 경계에서 반복 검증한다.
- ORM 없이 수신함/원본 개정 번호 충돌과 토큰 다이제스트 조회 SQL을 명시적으로 검토할 수 있다.
- iCal4j 모델/직렬화기가 RFC 표현을 맡고 CAL이 정렬과 고정된 출력기 설정을 맡아 수동
  이스케이프, DATE-TIME, 시간대 직렬화기를 유지하지 않는다.
- Java 25와 Spring Boot 4.1의 단일 실행 환경 기준선으로 운영 조합을 줄인다.
- PostgreSQL과 Spring이 제공하는 제한 시간 및 예외 추상화를 사용해 자체 취소·감시 코드를 두지
  않으면서도 내부 호출자에게 재시도 가능한 일시 실패를 일관되게 알린다.
- 저카디널리티 지표로 중복·역순 수신, 이전 내부 Bearer 잔존과 투영 비용을 식별자 노출 없이
  확인할 수 있다.
- Cloud Native Buildpacks가 JRE 선택, 계층화와 non-root 이미지를 맡아 수동 Dockerfile과 JRE 조립
  책임을 두지 않는다.
- Micrometer Prometheus 레지스트리와 Spring Boot 관리 포트 분리가 메트릭 형식과 공개 경계 분리를
  맡아 자체 내보내기 코드 없이 외부 관측 백엔드를 연결할 수 있다.
- Gradle 표준 아카이브가 재현 가능한 계약 팩 생성을 맡고 JSON Schema가 언어 중립 경계를 유지해,
  공유 DTO JAR·별도 압축기·자체 체크섬 매니페스트의 중복 책임이 생기지 않는다.

비용:

- 명시적 SQL과 행 매핑을 직접 유지해야 한다.
- 정상 배포에는 구독 세대를 안정적으로 보존하고 과거 DB 복원에는 DB보다 먼저 새 세대를 배포해야
  하므로 운영 설정 수명주기를 관리해야 한다.
- 줄 접기 길이 25는 ASCII 줄도 일찍 접으므로 사람이 읽는 캘린더 피드는 다소 장황할 수 있다.
- 고정된 iCal4j 시간대 데이터나 직렬화기를 올릴 때 캘린더 바이트와 ETag가 바뀌는지 검토해야 한다.
- 동기식 재구축이 커지는 시점에는 작업 상태와 일괄 처리 전략을 새 계약으로 도입해야 한다.
- V4는 복원 펜스를 모르는 구버전과, V5는 제거된 투영 열을 참조하는 구버전과 롤링 호환되지 않는다.
  V6의 새 시간 형태도 pre-V6가 해석하지 못하므로 각 호환성 관문과 전진 수정 원칙이 필요하다.
- builder와 run image를 내려받는 컨테이너 검증 때문에 CI 시간이 늘고 공급 이미지 갱신을 별도로
  검토해야 한다.
- 90일 보존을 요청한 CI 계약 팩은 안정적인 생산자 의존성이 아니므로, 릴리스 불변성을 활성화한 뒤
  버전이 일치하는 태그와 검증 가능한 GitHub 릴리스 자산을 별도로 운영해야 한다.
- CAL은 BATON 전체 재전달 완료를 판단하는 매니페스트나 완료 신호가 없다. 복구 모드를 켜면
  create·rotate를 차단하지만, 모드 설정과 전체 재전달 완료 확인 뒤의 해제는 BATON 또는 운영
  오케스트레이션이 보장해야 한다.

## 보류한 대안

- Spring WebFlux/R2DBC: 스트리밍이나 높은 동시성의 비동기 의존성이 없고
  트랜잭션/멱등성 복잡도만 늘려 선택하지 않았다.
- Spring Data JPA: 집합체 CRUD에는 편하지만 수신함 충돌, 토큰 다이제스트와 원자적 재구축의
  SQL/잠금을 감추므로 첫 버전에는 사용하지 않는다.
- 인메모리/H2: PostgreSQL 제약조건, 잠금, 다이제스트와 마이그레이션을 운영 환경과 다르게
  만들어 실행 환경이나 통합 테스트 저장소로 사용하지 않는다.
- Redis 응답 캐시: 즉시 토큰 폐기와 검증 값 무효화 경로가 하나 더 생겨
  MVP에서는 사용하지 않는다.
- 수동 iCalendar 출력기: 정확한 75 옥텟 채우기와 이벤트 연도에 한정한 시간대는 가능하지만
  TEXT 이스케이프, DATE-TIME, CRLF와 시간대 전환 구현을 중복하므로 선택하지 않았다.
- 수동 Dockerfile/JRE 조립: 현재 실행 계약은 `bootBuildImage`로 충족되며 JRE 선택, 계층화와
  non-root 사용자 구성을 중복하므로 도입하지 않았다.
- 공유 DTO JAR: 컴파일 시점 편의보다 언어·프레임워크와 릴리스 주기의 서비스 간 결합이 크므로
  두지 않고 JSON Schema와 예시를 계약 기준으로 사용한다.
- 수동 계약 압축·체크섬·매니페스트: Gradle `Zip`과 GitHub Actions의 artifact digest가
  생성·무결성 책임을 맡으므로 별도 형식과 구현을 유지하지 않는다.
- 메시지 브로커 전달: 향후 전송 수단이 될 수 있지만 커밋 후 HTTP 재시도와 영속적인
  수신함만으로 첫 계약을 만족하므로 보류한다.
