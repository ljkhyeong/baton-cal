# 인수인계

## 현재 상태

- 시즌 단위 MVP와 계약·영속성·HTTP·iCalendar·OCI 스모크 테스트가 구현되어 있다.
- BATON 생산자 연동은 수동 회차 `ALL_DAY`, 자동 회차 `ZONED_LOCAL_POINT`, 루틴 마감
  `UTC_POINT`와 변경·취소·중복·역순 전달까지 검증했다.
- 현재 생산자 기준은 [불변 안정 릴리스 `contracts-v1.0.0`](https://github.com/ljkhyeong/baton-cal/releases/tag/contracts-v1.0.0)이다.
  태그 커밋은 `fd081a742b7c09a7ace53bb445ce1380c533c19e`, 자산
  `baton-cal-contracts-1.0.0.zip`의 SHA-256은
  `b1aea8fed42c7b3f38320e1e0d883bd99c4d78e09d5b1dbddd4c90b2154146a7`이며 BATON 고정을 완료했다.
- 공개 구독은 `ACTIVE` 상태·토큰 해시·구독 세대가 모두 일치할 때만 조회된다. V4~V6 최초 적용은
  구버전을 모두 중지한 유지보수 배포이며, 적용 뒤 이전 버전 롤백과 구·신 버전 공존을 금지한다.
- OCI 스모크는 Java 25 비루트 이미지, Flyway V1~V6, 준비 상태, SIGTERM 종료, 동일 세대 재시작,
  PostgreSQL 논리 백업·복원과 복원 세대 펜스를 검증한다.
- 구현 계약과 운영 절차는 [PRD-0002](docs/PRD/0002_mvp-contract/spec.md), 기술 선택은
  [ADR-0002](docs/ADR/0002_technology-stack/adr.md), 계약 게시 이력은
  [계약 릴리스 현황](docs/contract-release-history.md)을 기준으로 한다.

## 검증 명령

```shell
./gradlew --no-daemon test bootJar contractsZip
./gradlew --no-daemon bootBuildImage --imageName=baton-cal:smoke
./scripts/smoke-oci-image.sh baton-cal:smoke
```

안정 계약 자산은 다음 명령으로 확인한다.

```shell
gh release verify contracts-v1.0.0
gh release verify-asset contracts-v1.0.0 baton-cal-contracts-1.0.0.zip
```

## 다음 작업

1. 내부 Bearer를 실제 비밀 관리 시스템에 연결하고 현재 값·이전 값 회전을 운영 환경에서 훈련한다.
2. 운영 HTTPS 인증서와 종단 구성을 확정하고, 역방향 프록시와 추적 내보내기에서 토큰 경로·쿼리·
   헤더가 남지 않는지 검증한다.
3. 공개 요청 제한 수치와 접근 감사 기록 보존 정책을 정한다.
4. BATON 전체 시즌 재전달 매니페스트와 완료 신호를 정하고, 필요하면 복구 중 구독 생성·회전을
   차단하는 복구 모드를 추가한다.
5. 운영 RTO/RPO, 백업 저장소·암호화와 실제 환경 복원 훈련을 확정한다.
6. 배포 대상과 이미지 레지스트리를 정하고 불변 이미지 식별자, builder 갱신과 배포 절차를 만든다.

## 현재 제한

- 안정 계약 게시와 BATON 생산자 고정은 완료했지만 실제 운영 활성화와 공개 배포는 하지 않았다.
- CI 계약 팩은 보존 기간 90일을 요청하는 변경 검토용 임시 산출물이며 안정 의존성이 아니다.
- OCI 이미지는 로컬·CI에서 검증했지만 레지스트리 게시, 운영 비밀 수명주기와 실제 프록시 관측
  경로 검증은 남아 있다.
- 저장소 복원 훈련은 대표 픽스처만 사용한다. BATON 전체 재전달 완료를 증명하지 않으며 CAL은
  재전달 전에 구독 생성·회전을 자동 차단하지 않는다.
- 현재 구현은 MVP이며 운영 준비 완료를 뜻하지 않는다.

## 저장소

- 공개 원격 저장소: <https://github.com/ljkhyeong/baton-cal>
