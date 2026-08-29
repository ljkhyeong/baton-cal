# 인수인계

## 현재 상태

- 시즌 단위 MVP와 계약·영속성·HTTP·iCalendar·OCI 스모크 테스트가 구현되어 있다.
- BATON 생산자 연동은 수동 회차 `ALL_DAY`, 자동 회차 `ZONED_LOCAL_POINT`, 루틴 마감
  `UTC_POINT`와 변경·취소·중복·역순 전달까지 검증했다.
- 현재 생산자 기준은 [불변 안정 릴리스 `contracts-v1.0.0`](https://github.com/ljkhyeong/baton-cal/releases/tag/contracts-v1.0.0)이다.
  태그 커밋은 `fd081a742b7c09a7ace53bb445ce1380c533c19e`, 자산
  `baton-cal-contracts-1.0.0.zip`의 SHA-256은
  `b1aea8fed42c7b3f38320e1e0d883bd99c4d78e09d5b1dbddd4c90b2154146a7`이며 BATON 고정을 완료했다.
- 다음 작업 후보 `1.1.0-rc.1`은 기존 128 KiB 문서 상한에 JSON 구조 자원 제한을 추가하고 `prod`
  데이터베이스가 로컬 기본값을 상속하지 않게 한다. 표현 바이트 변경 시 Last-Modified 전진,
  취소 뒤 재활성화, 예상 밖 `500` 비밀 비노출과 데이터베이스 제한 시간의 `503 SERVICE_BUSY` 회귀
  검증, iCal4j 4.3.0 단일 시간대 규칙 권위도 포함한다. 로컬 기본 테스트, 실행 JAR, 계약 ZIP과
  별도 투영 부하 테스트를 통과했지만 아직 게시하지 않았고 BATON 생산자 기준은 계속 `1.0.0`이다.
- 공개 구독은 `ACTIVE` 상태·토큰 해시·구독 세대가 모두 일치할 때만 조회된다. V4~V6 최초 적용은
  구버전을 모두 중지한 유지보수 배포이며, 적용 뒤 이전 버전 롤백과 구·신 버전 공존을 금지한다.
- OCI 스모크는 Java 25 비루트 이미지, Flyway V1~V6, 준비 상태, SIGTERM 종료, 동일 세대 재시작,
  PostgreSQL 논리 백업·복원과 복원 세대 펜스를 검증한다.
- 일정 수신·내부 인증·투영 재구축·시즌 잠금은 식별자와 비밀값이 없는 Micrometer 지표를 남긴다.
  Prometheus 형식은 `/actuator/prometheus`로 제공하며 `prod`에서는 기본 `8081` 관리 포트로
  분리한다. 공개 프록시는 관리 포트를 노출하지 않아야 한다.
  PostgreSQL 잠금 대기는 기본 5초, SQL 실행과 Spring 트랜잭션은 기본 30초이며, 제한 시간 초과는
  `503 SERVICE_BUSY`와 `Retry-After: 1`로 반환한다.
- CI는 풀 리퀘스트에서 읽기 권한으로 OCI 이미지를 검증만 하고, `main` 푸시 작업에만 패키지 쓰기
  권한을 부여해 스모크를 통과한 같은 이미지를
  `ghcr.io/ljkhyeong/baton-cal:{전체 Git 커밋 SHA}`로 게시한다. 외부 액션은 전체 커밋 SHA로
  고정했고 Gradle 의존성은 잠금 파일과 SHA-256 검증 메타데이터를 함께 검사한다. Gradle,
  GitHub Actions와 Docker Compose 갱신은 Dependabot이 매주 제안하도록 구성되어 있다.
- 시즌 전체 투영 수동 부하 측정은 PostgreSQL 18.4에서 500~10,000개를 각 5회 실행했다. 10,000개는
  676~689밀리초, 2,459,060바이트였으며 [성능 기준](docs/performance-baseline.md)에 기록했다.
- 시간대 지정 현지 시각은 iCal4j 4.3.0 내장 Olson `2025a`의 원문 TZID만 허용한다. DST 공백도
  같은 `VTIMEZONE`에서 만든 규칙으로 판정해 Java 런타임 TZDB와 규칙 권위를 섞지 않는다.
- 구현 계약과 운영 절차는 [PRD-0002](docs/PRD/0002_mvp-contract/spec.md), 기술 선택은
  [ADR-0002](docs/ADR/0002_technology-stack/adr.md), 계약 게시 이력은
  [계약 릴리스 현황](docs/contract-release-history.md)을 기준으로 한다.

## 검증 명령

```shell
./gradlew --no-daemon test bootJar contractsZip
./gradlew --no-daemon projectionLoadTest
./gradlew --no-daemon bootBuildImage --imageName=baton-cal:smoke
./scripts/smoke-oci-image.sh baton-cal:smoke
```

`projectionLoadTest`는 기본 테스트에서 제외한 수동 성능 회귀 측정이다.

안정 계약 자산은 다음 명령으로 확인한다.

```shell
gh release verify contracts-v1.0.0
gh release verify-asset contracts-v1.0.0 baton-cal-contracts-1.0.0.zip
```

## 다음 작업

1. 내부 Bearer를 실제 비밀 관리 시스템에 연결하고 현재 값·이전 값 회전을 운영 환경에서 훈련한다.
2. 운영 HTTPS 인증서와 종단 구성을 확정하고 Prometheus 관측 백엔드를 연결한다. 역방향 프록시는
   관리 포트를 공개하지 않고 추적 내보내기에서도 토큰 경로·쿼리·헤더가 남지 않는지 검증한다.
3. 공개 요청 제한 수치와 접근 감사 기록 보존 정책을 정한다.
4. BATON 전체 시즌 재전달 매니페스트와 완료 신호를 정하고, 필요하면 복구 중 구독 생성·회전을
   차단하는 복구 모드를 추가한다.
5. 운영 RTO/RPO, 백업 저장소·암호화와 실제 환경 복원 훈련을 확정한다.
6. 배포 대상을 정하고 GHCR digest 고정, builder 갱신과 배포 절차를 만든다.
7. 후속 iCal4j가 Olson `2025a`보다 최신 데이터를 포함하면 의존성 잠금, 골든 바이트와 ETag를
   검토한 새 계약 후보로 갱신한다.

## 현재 제한

- 안정 계약 게시와 BATON 생산자 고정은 완료했지만 실제 운영 활성화와 공개 배포는 하지 않았다.
- CI 계약 팩은 보존 기간 90일을 요청하는 변경 검토용 임시 산출물이며 안정 의존성이 아니다.
- GHCR 게시 자동화는 구성했지만 실제 운영 배포, 운영 비밀 수명주기, Prometheus 백엔드 연결과
  실제 프록시 관측 경로 검증은 남아 있다.
- 저장소 복원 훈련은 대표 픽스처만 사용한다. BATON 전체 재전달 완료를 증명하지 않으며 CAL은
  재전달 전에 구독 생성·회전을 자동 차단하지 않는다.
- iCal4j 4.3.0 내장 시간대 데이터는 Olson `2025a`다. CAL은 이를 입력 검증과 출력의 단일 권위로
  사용하므로 더 최신 IANA 시간대 식별자와 규칙은 다음 의존성 갱신 전까지 지원하지 않는다.
- 현재 구현은 MVP이며 운영 준비 완료를 뜻하지 않는다.

## 저장소

- 공개 원격 저장소: <https://github.com/ljkhyeong/baton-cal>
