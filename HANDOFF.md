# 인수인계

## 현재 상태

- CAL의 일정 수신·캘린더 변환·구독·복구 진단을 구현했다. 기능은 [README](README.md),
  API는 [PRD-0002](docs/PRD/0002_mvp-contract/spec.md)를 따른다.
- BATON의 개인 구독·내 구독 목록·여러 구독 해제·앱별 등록 안내는 원격 main에 반영됐다.
  실제 캘린더 앱 검증과 운영 활성화는 남아 있다.
- CAL 경로는 `/Users/lim/devProject/personal/baton-cal`이다. BATON 작업 경로는
  `/Users/lim/devProject/personal/manager`에서 `git worktree list`로 확인한다.
  이전 `/private/tmp/baton-cal-registration-20260907`은 Git 작업 트리 정보가 삭제되어 작업 경로로 사용하지 않는다.
- 안정 계약은 `1.0.0`, 게시된 후보는 `1.1.0-rc.1`, 현재 작업 후보는 `1.1.0-rc.2`다.
  `rc.2` 게시·BATON 사용 버전 지정·안정 버전 승격은 남아 있다. [릴리스 현황](docs/contract-release-history.md)
- `cal.b4ton.com`용 로컬 HTTPS·프록시·Prometheus·알림 구성은 검증했다. 실제 서버·DNS·공인 인증서·
  운영 알림 채널은 연결하지 않았다.

## 최근 검증

- CAL `3c2936d`: [필수 CI](https://github.com/ljkhyeong/baton-cal/actions/runs/34172597027) 통과.
  전체 테스트·계약 ZIP·OCI 이미지·운영 스모크를 포함한다. 서버 중단 후 프록시 응답은 연결 거부 시
  `502`, 연결 시간 초과 시 `504`를 허용하며, 알림 발생·해제와 토큰 비노출을 확인했다.
- 2026-09-12 CAL 문구 수정: 기준 `fd26b79`에 설정 오류 문구 두 곳·기존 테스트 기대값·문서를 수정한 상태에서
  `./gradlew --no-daemon test --tests 'io.baton.cal.config.CalPropertiesTest' verifyContractsZip` 성공.
  Java 25 툴체인으로 설정 테스트 8개와 계약 ZIP을 새로 검증했다. 실패·제외 없음.
  문서 링크 37개가 유효하고 명령·예시 코드·성능 측정값은 유지된다. 검증 로직과 API 계약 변경이 없어
  전체 테스트·이미지·운영 스모크는 반복하지 않았다. 로그: `/private/tmp/baton-cal-wording-20260912.log`.
- BATON `0861b040`의 이전 검증 기록은 `/private/tmp/baton-cal-registration-20260907/output/verification/latest.md`에 있다.
  현재 BATON 검증 결과로 재사용하려면 변경 파일과 실행 환경을 먼저 비교한다.
  이전 코드 검토는 [표준 API 검토](docs/reviews/2026-09-05-standard-api-review.md), 과거 검증은 Git 기록을 참고한다.

결과를 재사용하기 전에 [개발 검증 절차](docs/development.md)에 따라 소스·테스트·설정·환경 차이를 확인한다.

## 남은 작업

외부 연동은 [추가 요금 없는 연동 기준](docs/external-api-options.md)을 따른다. 권장 방향으로 기존 구독의
앱별 등록 편의를 개선했다. 제공자 API 직접 연동은 보류하며 공휴일 활용은 BATON의 별도 작업이다.

1. `1.1.0-rc.2` 릴리스 노트를 작성·검토하고 게시한다. BATON에서 공식 ZIP과 릴리스 증명을 검증한 뒤
   사용할 버전·해시를 지정한다. 연동 검증 후 안정 버전을 게시한다.
   `1.0.x` 유지가 필요하면 `LICENSE`를 포함한 `1.0.1` 호환 보완판도 게시한다.
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
- 복구 상태 GET은 저장된 기록을 반환한다. 최신 원본의 반영 여부를 다시 확인하거나 복구 모드를 해제하지 않는다.
- ID 지정 PUT의 응답 유실은 사전 저장한 ID로 조회할 수 있다. 기존 POST 생성과 rotate의 토큰 원문은
  다시 조회할 수 없으므로 구독 주소를 재발급해야 한다.
- 시간대 입력과 출력은 iCal4j 4.3.0 내장 Olson `2025a` 기준이다.
- CI 산출물·공식 계약 릴리스·BATON의 계약 채택·운영 배포는 구분한다.
