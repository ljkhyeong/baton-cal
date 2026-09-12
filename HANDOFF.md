# 인수인계

## 현재 상태

- CAL의 일정 수신·캘린더 변환·구독·복구 진단을 구현했다. 기능은 [README](README.md),
  API는 [PRD-0002](docs/PRD/0002_mvp-contract/spec.md)를 따른다.
- 공개 HEAD는 캘린더 본문을 조회하지 않고 구독 상태·캐시 검증 값·파일 크기를 반환한다.
  [후속 기능 검토](docs/reviews/2026-09-12-feature-review.md)에 채택 근거와 보류 항목을 정리했다.
- DB 연결·트랜잭션 시작 실패는 `503 SERVICE_BUSY`와 재시도 간격을 반환한다. 연결 반환 후 기존 구독 조회와
  같은 ID의 발급 재시도가 정상 처리되는지 실제 연결 풀 고갈 테스트로 확인했다.
- 이미 저장된 구독의 재요청은 캘린더 준비와 토큰 생성을 생략한다. 동시 생성은 DB 기본키로 판정한다.
  V3의 투영 외래키 제약에 따라 캘린더 준비 후 구독을 저장하는 순서는 유지한다.
- 일정·구독·복구 상태 조회의 `404`, 상태 충돌의 `409`, 복구 중 발급 차단의 `503`에도
  `Cache-Control: no-store`를 적용한다. 상태가 바뀐 뒤 이전 오류 응답이 재사용되는 것을 막는다.
- 파일 작성 직후 검사와 종료 전 전체 diff·ArchUnit 검사를 추가했다. 기존 Spring Repository 주입 구조를
  유지하며 Controller의 DB 접근, 도메인의 실행 계층 의존, Service의 JDBC 사용·Repository 생성을 검사한다.
  실행 방법과 Codex 훅 신뢰 절차는 [개발 검증 절차](docs/development.md)를 따른다.
- BATON의 개인 구독·내 구독 목록·여러 구독 해제·앱별 등록 안내는 원격 main에 반영됐다.
  실제 캘린더 앱 검증과 운영 활성화는 남아 있다.
- CAL 경로는 `/Users/lim/devProject/personal/baton-cal`이다. BATON 작업 경로는
  `/Users/lim/devProject/personal/manager`에서 `git worktree list`로 확인한다.
  이전 `/private/tmp/baton-cal-registration-20260907`은 Git 작업 트리 정보가 삭제되어 작업 경로로 사용하지 않는다.
- 안정 계약은 `1.0.0`, 게시된 후보는 `1.1.0-rc.1`, 현재 작업 후보는 `1.1.0-rc.2`다.
  [rc.2 릴리스 노트](docs/releases/contracts-v1.1.0-rc.2.md)를 작성했다. 게시·BATON 사용 버전 지정·
  안정 버전 승격은 남아 있다. [릴리스 현황](docs/contract-release-history.md)
- 기존 Alertmanager에 Slack·Discord 기본 연동을 추가했다. Blackbox Exporter는 `cal.b4ton.com`의
  인증서 이름·체인·만료 시각을 검사하며, 실패와 14일 이내 만료를 알린다.
  [외부 연동 검토](docs/external-api-options.md), [연결 방법](docs/operations.md#운영-알림-채널-연결)
- 사용자 확인 기준으로 Ubuntu 홈서버·DNS·공인 IP·80/443 포트포워딩·인증서는 준비됐고 k3s는 구축 전이다.
  로컬 연동만 검증했으며 실제 서버·DNS·인증서·운영 알림 채널을 변경하지 않았다.

## 최근 검증

- 2026-09-12 외부 연동: 기준 `efed802` 이후 운영 설정·검증 스크립트·CI 변경을 `9d5406f`로 커밋했다.
  `bootBuildImage --imageName=baton-cal:external-integrations` 성공 후
  `bash scripts/smoke-operations.sh baton-cal:external-integrations`로 기존 HTTPS·장애 복구·토큰 비노출과
  새 TLS 점검·인증서 알림 규칙을 확인했다. `bash scripts/smoke-alert-channels.sh`로 실제 Alertmanager와
  모의 Slack·Discord API의 장애 발생·복구 알림, 웹훅 주소 비노출을 확인했다. 모두 통과했다.
  로그: `/private/tmp/baton-cal-external-operations.log`, `/private/tmp/baton-cal-alert-channels.log`.
  종료 파일·구조 검사와 전체 diff 검토도 통과했다. 구조 검사·검증 루프 테스트의 Gradle 작업 7개는
  입력이 같아 기존 결과를 재사용했다. CAL 소스·의존성도 그대로여서 아래 `82295dc`의 일반 테스트 결과를
  재사용한다. 실제 수신 채널과 홈서버 연결은 미검증이며 새 CI는 미푸시로 실행하지 않았다.
- CAL `3c2936d`: [필수 CI](https://github.com/ljkhyeong/baton-cal/actions/runs/34172597027) 통과.
  전체 테스트·계약 ZIP·OCI 이미지·운영 스모크를 포함한다. 서버 중단 후 프록시 응답은 연결 거부 시
  `502`, 연결 시간 초과 시 `504`를 허용하며, 알림 발생·해제와 토큰 비노출을 확인했다.
- 2026-09-12 검증 루프: 기준 `0a7cd0a`에서 검사·테스트·의존성·CI 변경을 `82295dc`로 커밋했다.
  `./gradlew --no-daemon --max-workers=2 check`로 일반 125개·구조 3개·스크립트 11개 테스트와 계약 ZIP을
  검증했다. Java 25·PostgreSQL 18.6, 실패·제외 없음. 4개 작업을 새로 실행하고 6개는 기존 결과를 재사용했다.
  로그: `/private/tmp/baton-cal-feedback-check-final.log`.
  실제 위반 탐지·정상 주입 허용, 새 파일·스테이징·중간 커밋 포함, 같은 실패의 자동 재실행 방지,
  수동 재시도·훅 JSON 응답을 확인했다. 테스트용 Spring 클래스는 제품 자동 탐색 범위 밖에 두었다.
  종료 검사는
  `python3 -B scripts/agent_feedback.py final`로 실행하고 출력된 전체 diff를 검토한다.
- BATON `0861b040`의 이전 검증 기록은 `/private/tmp/baton-cal-registration-20260907/output/verification/latest.md`에 있다.
  현재 BATON 검증 결과로 재사용하려면 변경 파일과 실행 환경을 먼저 비교한다.
  이전 코드 검토는 [표준 API 검토](docs/reviews/2026-09-05-standard-api-review.md), 과거 검증은 Git 기록을 참고한다.

결과를 재사용하기 전에 [개발 검증 절차](docs/development.md)에 따라 소스·테스트·설정·환경 차이를 확인한다.

## 남은 작업

외부 연동은 [추가 요금 없는 연동 기준](docs/external-api-options.md)을 따른다. 권장 방향으로 기존 구독의
앱별 등록 편의를 개선했다. 제공자 API 직접 연동은 보류하며 공휴일 활용은 BATON의 별도 작업이다.

1. 작성한 `1.1.0-rc.2` 릴리스 노트를 게시할 커밋과 대조한 뒤 게시한다. BATON에서 공식 ZIP과 릴리스 증명을 검증한 뒤
   사용할 버전·해시를 지정한다. 연동 검증 후 안정 버전을 게시한다.
   `1.0.x` 유지가 필요하면 `LICENSE`를 포함한 `1.0.1` 호환 보완판도 게시한다.
2. 실제 운영 환경에서 준비된 `cal.b4ton.com` DNS·인증서를 연결하고, 선택한 알림 채널의 웹훅을 등록한다.
   인증서 갱신·외부 점검·비밀 관리에 사용할 도구를 연결하고 내부 Bearer 회전과 프록시·추적의
   토큰 비노출을 검증한다. 홈서버 설치·k3s 구축은 이번 연동 작업 범위에 포함하지 않는다.
3. 실제 캘린더 앱에서 구독·이름 표시·갱신·취소를 확인한 뒤 BATON의 관련 기능과 이름 보정·전달을
   활성화한다. [앱별 확인표](docs/calendar-subscription-guide.md)
4. 운영 RTO/RPO·백업 저장소·암호화와 실제 복원 훈련을 정한다. 구독 세대 교체, 최신 재전달·완료 확인,
   복구 모드 해제 순서를 운영 절차에 연결한다. 배포 이미지 digest 고정과 builder 갱신도 적용한다.
5. 실제 수신량·제공자 공유 IP·로그 보존 정책에 맞춰 요청 제한과 알림을 조정한다. 데이터 규모와
   복구 목표 시간을 정한 뒤 성능을 측정하고, 목표를 넘으면 묶음 수신 계약을 검토한다.
6. 시간대 데이터 갱신이 필요하면 iCal4j 의존성 잠금·골든 바이트·ETag를 검토한 새 계약 후보를 만든다.

## 현재 제한

- Codex 자동 훅의 실제 세션 실행은 미검증이다. `.codex/hooks.json`의 세 훅을 `/hooks`에서 검토·신뢰해야
  자동 실행된다. 신뢰 전에는 AGENTS.md의 수동 파일·종료 검사를 사용한다.
- 로컬 복원은 대표 데이터 검증이다. 실제 운영 전체 데이터·백업 저장소 복원이나 운영 준비 완료를 뜻하지 않는다.
- 복구 상태 GET은 저장된 기록을 반환한다. 최신 원본의 반영 여부를 다시 확인하거나 복구 모드를 해제하지 않는다.
- ID 지정 PUT의 응답 유실은 사전 저장한 ID로 조회할 수 있다. 기존 POST 생성과 rotate의 토큰 원문은
  다시 조회할 수 없으므로 구독 주소를 재발급해야 한다.
- 시간대 입력과 출력은 iCal4j 4.3.0 내장 Olson `2025a` 기준이다.
- CI 산출물·공식 계약 릴리스·BATON의 계약 채택·운영 배포는 구분한다.
