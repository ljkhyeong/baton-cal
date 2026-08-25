# 인수인계

`1.0.0-rc.2` 시간 형태 확장 초안 PR #4를 열었고 GitHub Actions가 통과했다. 병합과 불변
사전 릴리스 게시 전이다.

## 현재 구현

- PRD-0002와 ADR-0002가 시즌 단위 MVP 계약과 기술 스택의 기준이다.
- 일정 스냅샷 수신, 중복·낮은 개정 번호·충돌 분류와 원자적 투영 재구축이 구현되어 있다.
- 해시만 저장하는 구독 생성·회전·폐기와 공개 조건부 `.ics` GET이 구현되어 있다.
- iCal4j 모델과 직렬화 도구, PostgreSQL·Flyway와 Testcontainers 통합 테스트를 사용한다.
- 컬렉션·널 처리·문자열 인코딩·Base64·16진수·파일 편의 기능은 Kotlin 표준 라이브러리를 우선하고,
  시간·암호화·URI·네트워크·UUID 같은 JVM 고유 기능만 JDK API를 사용한다.
- 엄격한 타임스탬프 형식과 마이크로초 정규화, Java와 iCal4j 시간대의 교집합을 검증한다.
- UTC·시간대 지정 구간뿐 아니라 `DTEND`가 없는 UTC·시간대 지정 시점과 `VALUE=DATE`인 종일
  날짜 구간을 지원한다. CAL은 임의 지속 시간이나 자정 시각을 만들지 않는다.
- 빈 피드·DST·자정·취소 골든 픽스처, 트랜잭션 롤백·호출자 재전달과 구독 CAS 경쟁을 검증한다.
- 자격 증명 응답은 `no-store`이며 상태·준비 상태 검사와 로컬 PostgreSQL 실행 절차가 준비되어 있다.
- 입력 시각은 단일 Java 엄격 파서가 형식과 날짜 유효성을 함께 검증하고, 내부 경로 필터 등록과
  테스트 DB 초기화는 각각 Spring Boot와 Spring Test 표준 기능을 사용한다.
- 계약 팩은 Draft 2020-12 JSON Schema와 모든 JSON 예시를 자동 검증하며, 시간대 일정의
  활성·변경·취소·정확한 재전달 생명주기를 실제 수신 경로로 실행한다. 일정 수신 결과, 구독
  생성·회전, 투영 재구축과 공통 오류의 실제 MockMvc 응답도 해당 응답 스키마에 직접 대조한다.
- 계약 버전의 단일 원천은 `contracts/VERSION`이며 현재 값은 BATON 생산자 미검증 상태를 나타내는
  `1.0.0-rc.2`다. Gradle 표준 `contractsZip` 작업은 `contracts/**`와 PRD-0002를 파일 시각·
  항목 순서·권한이 고정된 `build/distributions/baton-cal-contracts-1.0.0-rc.2.zip`으로 만든다.
  ZIP 내부 `contracts/VERSION`, 파일명과 예정 태그 `contracts-v1.0.0-rc.2`는 같은 버전을 가리킨다.
  GitHub Actions는 이 파일을 `retention-days: 90` 보존을 요청하는 변경 검토용 임시 산출물로
  업로드한다.
- GitHub의 release immutability를 활성화한 뒤 [불변 사전 릴리스
  `contracts-v1.0.0-rc.1`](https://github.com/ljkhyeong/baton-cal/releases/tag/contracts-v1.0.0-rc.1)을
  게시했다. 태그는 `ce613f2ed72aa7ada61592664ca8feb8077eac75`를 가리키며, 자산 SHA-256
  `0ca23e9e5189d41383d21c334005870446aa52a80e7bcb23e9183d8869acc546`은
  `gh release verify`와 `gh release verify-asset` 검증을 통과했다.
- `rc.1`에는 BATON 원본의 단일 마감 시각과 날짜 단위 회차를 손실 없이 표현하는 형태가 없어
  변경하지 않고 보존한다. 새 BATON 연동 대상은 세 시간 형태를 추가한 `rc.2`다.
- RFC 5545 TEXT의 LF·HTAB과 정상 Unicode는 보존하고 금지 제어 문자와 짝이 없는 서로게이트는
  HTTP 경계와 JSON Schema에서 거부한다. Unicode 이스케이프와 4바이트 문자 줄 접기 경계는
  정규 골든으로 고정했고 의존성과 골든의 수동 검토 절차도 문서화했다.
- 투영 `Last-Modified`의 단조 증가 계산과 내부 토큰 설정 바인딩 실패의 비노출을 회귀 테스트로
  검증한다.
- 개별 DTO·JSON Schema 필드 제약과 별도로 JSON 전체 문서를 128 KiB(131,072바이트)로 제한하고,
  초과 요청을 `413 REQUEST_TOO_LARGE` 고정 오류로 변환한다.
- 공개 기준 URL은 외부·비루프백 HTTPS 또는 로컬 개발용 루프백 HTTP만 허용한다. `prod` 프로필은
  명시적인 `BATON_CAL_PUBLIC_BASE_URL`이 없거나 HTTPS가 아니면 시작을 거부한다.
- Tomcat 접근 로그는 기본 비활성이고 안전 패턴에도 경로·쿼리·헤더가 없다. `prod`에서는
  `StatementCreatorUtils` 로그를 끈다.
- 내부 Bearer는 현재 값과 회전 창의 선택적 이전 값만 허용하고, 제시된 값을 설정된 모든 값과
  상수 시간으로 비교한다. 공개 `/calendars/v1/**`의 고카디널리티 `http.url`은 실제 토큰이 없는
  `/calendars/v1/{token}.ics`로 기록한다.
- 구독은 비밀이 아닌 외부 런타임 UUID 세대를 저장하며 공개 조회에서 `ACTIVE` 상태·토큰 해시·세대가
  모두 일치해야 한다. 호환용 초기 세대가 있고 `prod`는 명시적인 세대를 요구하므로, 과거 DB를
  복원하기 전에 새 세대로 바꾸면 복원된 피드 URL이 즉시 일반 `404`로 무효화된다.
- V4 최초 적용은 모든 pre-V4 인스턴스를 중지하고 호환 초기 세대로 신버전만 시작하는 유지보수
  배포이며, 적용 뒤 pre-V4 롤백과 구·신 버전 공존은 금지한다.
- V5는 더 이상 읽지 않는 투영 `item_count`를 제거하므로 모든 pre-V5 인스턴스를 중지한 유지보수
  배포로 적용한다. 적용 뒤 pre-V5 롤백·공존은 금지하고 문제는 신버전으로 전진 수정한다.
- V6는 시점·종일 일정 열과 제약을 추가한다. 모든 pre-V6 인스턴스를 종료하기 전에는 BATON이
  새 형태를 보내지 않으며, 새 형태를 수신한 뒤에는 pre-V6 롤백·공존을 금지한다.
- Spring Boot `bootBuildImage`가 Java 25 OCI 이미지를 만들고, 격리된 PostgreSQL과 `prod` 프로필의
  컨테이너 스모크가 Flyway V1~V6, 준비 상태, 비루트 실행과 SIGTERM 종료 코드 143을 검증한다.
  같은 구독 세대로 컨테이너를 강제 재생성한 뒤에도 기존 공개 피드가 `200`인지 확인한다.
- 같은 스모크는 `pg_dump -Fc` 아카이브 확인과 `pg_restore --clean --create --exit-on-error` 실제
  복원, 애플리케이션 시작 전 구독 세대 교체, 복원 토큰의 본문 없는 일반 `404`, 대표 최신 변경·취소
  재전달 뒤 기존 구독 rotate와 새 피드의 `STATUS:CANCELLED`·`SEQUENCE:3`까지 검증한다.
- GitHub Actions는 `main` 푸시와 풀 리퀘스트에서 테스트, 계약 팩과 OCI 이미지 생성, 같은
  스모크·대표 복원 훈련을 실행한다.
- 사람이 읽는 저장소 문서와 주석은 한글로 작성하며 코드 식별자와 표준명은 원문을 유지한다.

## 검증

- `./gradlew --no-daemon test`
- `./gradlew --no-daemon contractsZip`
- `./gradlew --no-daemon bootJar`
- `./gradlew --no-daemon bootBuildImage --imageName=baton-cal:smoke`
- `./scripts/smoke-oci-image.sh baton-cal:smoke`

## 다음 작업

1. CAL `rc.2` 변경을 검토·병합한 뒤 불변 `contracts-v1.0.0-rc.2`를 게시하고 검증한다. BATON 발행
   측은 이 버전을 고정해 실제 직렬화기·개정 번호·취소·커밋 후 발행을 검증하는 생산자 테스트를 연결한다.
   검증 결과 버전 표식 외 계약 의미를 바꿀 필요가 없으면 동일한 계약 의미의 안정 버전 `1.0.0`으로
   승격하고, 의미 변경이 필요하면 게시된 RC를 교체하지 않고 다음 RC를 만든다.
2. 실제 비밀 관리 시스템에 내부 Bearer를 연결하고 현재 값·이전 값 회전 절차를 운영 환경에서
   훈련하며, 실제 운영 HTTPS 인증서·종단 설정을 확정한다.
3. 실제 역방향 프록시와 tracing exporter에서 토큰 경로·쿼리·헤더를 삭제하는지 검증하고,
   공개 요청 제한 수치와 접근 감사 기록 보존 정책을 정한다.
4. 실제 BATON 전체 시즌의 매니페스트·재전달 완료 신호와 필요 시 재생 전 create·rotate 자동 차단
   계약을 정하고, 운영 RTO/RPO·백업 저장소·암호화·비밀 관리 시스템을 포함한 실제 환경 복원 훈련을
   수행한다.
5. 실제 배포 대상과 이미지 레지스트리를 정하고 불변 이미지 식별자, builder 갱신과 배포 절차를
   확정한다.

## 현재 제한

- BATON 발행 측과 아직 연결하지 않았다.
- CI 계약 팩은 90일 보존을 요청하는 변경 검토용 임시 산출물이다. 저장소·조직 정책에 따라 실제
  만료 시점은 달라질 수 있다. 검증 가능한 불변 사전 릴리스는 게시했지만 BATON 생산자 고정·
  직렬화 테스트는 아직 완료하지 않았다.
- OCI 이미지의 로컬·CI 실행 검증은 준비됐지만 레지스트리 게시와 공개 배포, 운영 비밀 수명주기,
  호출량 제한과 외부 관측 경로의 비노출 검증은 준비되지 않았다.
- 저장소 복원 훈련은 대표 계약 픽스처와 절차 순서만 검증한다. 실제 BATON 전체 재전달 완료를
  증명하지 않으며 CAL은 재생 전에 구독 create·rotate를 자동 차단하지 않는다. 실제 운영 RTO/RPO,
  백업 저장소·암호화·비밀 관리 시스템과 배포 환경 복원 훈련은 아직 필요하다.
- 현재 구현은 MVP이며 운영 준비 완료를 뜻하지 않는다.

## 저장소

- 공개 원격 저장소: <https://github.com/ljkhyeong/baton-cal>
