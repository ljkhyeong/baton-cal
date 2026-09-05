# 인수인계

## 현재 상태

- CAL은 일정 수신·캘린더 투영·구독·복구 진단을 구현했다. BATON의 개인 구독, 내 구독 목록,
  여러 구독 선택 해제와 문구 개선도 로컬 검증을 마쳤다. 기능 설명은 [README](README.md),
  API 기준은 [PRD-0002](docs/PRD/0002_mvp-contract/spec.md)를 따른다.
- 2026-09-05 확인 시 CAL 기능 변경은 로컬 `main`의 `592dc8b`, BATON 화면 변경은 `fc3d90f1`에
  포함됐다. 이후 지시 정리는 `codex/streamline-cal-instructions`에서 진행했다. 다음 작업은 실제
  브랜치·미커밋 변경부터 확인한다.
- 표준 API 검토의 4건을 반영했다. PostgreSQL 예외 변환을 공통 설정으로 옮기고, 복구의 수동 행 매핑·
  중복 정렬·완료 직후 재조회와 같은 값 재검증을 제거했다. 추가 검토한 일정 상태 조회도 응답에 필요한
  5개 열만 읽도록 반영했다. [변경과 검증](docs/reviews/2026-09-05-standard-api-review.md)
- 현재 경로는 CAL `/Users/lim/devProject/personal/baton-cal`, BATON `/Users/lim/devProject/personal/manager`다.
  과거 `/Users/lim/Documents/` 경로를 재사용하지 말고 다른 작업 트리는 Git 등록 상태를 확인한다.
- 공식 게시된 후보 계약은 `1.1.0-rc.1`, 현재 작업 후보는 `1.1.0-rc.2`다. 안정 기준은 `1.0.0`이며,
  `rc.2` 공식 게시·BATON 자산 고정·안정 승격은 남아 있다. [릴리스 현황](docs/contract-release-history.md)
- `cal.b4ton.com`용 로컬 HTTPS·프록시·Prometheus·알림 구성은 검증했다. 실제 서버·DNS·공인 인증서·
  운영 알림 채널은 연결하지 않았다. BATON 기능 플래그와 실제 운영 활성화도 별도 작업이다.

## 최근 검증 근거

다음 결과는 해당 시점의 기록이다. 재사용 전 [개발 검증 절차](docs/development.md)에 따라
소스·미추적 파일·설정·환경 차이를 확인한다.

| 기준 | 범위·환경 | 결과와 제한 |
| --- | --- | --- |
| 검증 스크립트 `45dd445`, 문서 수정 중 | 로컬 Python 3.14·PyYAML 6.0.3, `bash -n`, 스킬 검증 최초 실행·환경 재사용·입력 오류 2건 | 성공. 문서 로컬 링크 32개도 확인. 앱·Gradle 동작 변경이 없어 서버 테스트는 실행하지 않음 |
| CAL `dcc264d` + 일정 상태 조회 구현 미커밋 변경 | Java 25.0.3·PostgreSQL 18.6 Testcontainers, `./gradlew --no-daemon test --tests 'io.baton.cal.web.MvpHttpFlowTest'` | 기존 HTTP 테스트 13개 새 실행 성공. 전체 테스트·계약 ZIP·운영 검증은 반복하지 않음. 앞선 공통 JDBC·복구의 116개 결과는 [검토 기록](docs/reviews/2026-09-05-standard-api-review.md)에 보존 |
| BATON `68179922` | 문구 변경 관련 Playwright 38건, PC·모바일·WebKit, 타입 검사 포함 프로덕션 빌드 | 성공. 서버·외부 앱 검증은 제외. [기록](docs/reviews/2026-09-05-wording-followup-review.md) |

이전 서버·OCI·복원 검증과 WebKit 시간 초과 기록은 `git show 6c8eec6:HANDOFF.md`에서 확인할 수 있다.
과거 테스트 개수와 브랜치 목록을 새 인수인계에 반복해서 추가하지 않는다.

## 남은 작업

1. `1.1.0-rc.2` 검토·게시 후 BATON을 공식 자산·증명으로 고정하고 검증한 계약을 안정 버전으로
   승격한다. `1.0.x` 유지가 필요하면 `LICENSE`를 포함한 `1.0.1` 호환 보완판도 게시한다.
2. 실제 서버·DNS 업체와 비밀 관리 시스템을 정하고 `cal.b4ton.com` HTTPS·인증서 갱신·알림 수신·
   외부 점검을 연결한다. 내부 Bearer 회전과 실제 프록시·추적의 토큰 비노출을 검증한다.
3. 실제 캘린더 앱에서 구독·이름 표시·갱신·취소를 확인한 뒤 BATON의 관련 기능과 이름 보정·전달을
   활성화한다. [앱별 확인표](docs/calendar-subscription-guide.md)
4. 운영 RTO/RPO·백업 저장소·암호화와 실제 복원 훈련을 정한다. 구독 세대 교체, 최신 재전달·완료 확인,
   복구 모드 해제 순서를 운영 절차에 연결한다. 배포 이미지 digest 고정과 builder 갱신도 적용한다.
5. 실제 수신량·제공자 공유 IP·로그 보존 정책에 맞춰 요청 제한과 알림을 조정한다. 데이터 규모와
   복구 목표 시간을 정한 뒤 성능을 측정하고, 목표를 넘으면 묶음 수신 계약을 검토한다.
6. 시간대 데이터 갱신이 필요하면 iCal4j 의존성 잠금·골든 바이트·ETag를 검토한 새 계약 후보를 만든다.

## 현재 제한

- 로컬 복원은 대표 데이터 검증이다. 실제 운영 전체 데이터·백업 저장소 복원이나 운영 준비 완료를 뜻하지 않는다.
- 복구 상태 GET은 저장된 기록을 반환하며 현재 원본 완전성을 다시 판정하거나 복구 모드를 해제하지 않는다.
- ID 지정 PUT의 응답 유실은 사전 저장한 ID로 조회할 수 있다. 기존 POST 생성과 rotate의 토큰 원문은
  다시 조회할 수 없으며 명시적 회전이 필요하다.
- 시간대 입력과 출력은 iCal4j 4.3.0 내장 Olson `2025a` 기준이다.
- CI 산출물·공식 계약 릴리스·BATON의 계약 채택·운영 배포는 구분한다.
