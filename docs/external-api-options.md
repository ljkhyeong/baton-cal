# 추가 요금 없는 외부 API 연동

- 확인일: 2026-09-12
- 기준: CAL `5b7e0c1`, 추가 요금 없는 외부 API·운영 연동 재검토
- 선택: `.ics` 구독 유지, 기본 제공 알림·인증서·공개 경로 점검·외부 정상 신호와 Secret 파일 연동

## 검토 결과

| 대상 | 활용할 API·표준 연동 | 줄일 수 있는 직접 구현 | 결정 |
| --- | --- | --- | --- |
| 운영 알림 | Alertmanager의 Slack·Discord 웹훅 연동 | 메시지 변환 서버, API 호출·재시도 코드 | 수신 설정과 모의 API 검증 추가 |
| 서버·모니터링 중단 | Healthchecks.io Pinging API와 Alertmanager | 정상 신호 전송 프로그램, 신호 누락 판정·외부 알림 | 무료 점검 1개를 사용하는 선택 설정과 로컬 검증 추가 |
| 인증서 점검 | Blackbox Exporter TLS 검사와 Prometheus | 인증서 파싱·만료일 점검 스크립트 | 이름·체인 확인, 14일 이내 만료 알림 추가 |
| 공개 요청 경로 점검 | Blackbox Exporter HTTP 검사와 Prometheus | 전용 공개 상태 API·점검 클라이언트 | 실제 구독 토큰 없이 프록시→CAL 응답을 확인하는 설정과 장애·복구 검증 추가 |
| 비밀값 전달 | Spring Boot `configtree`와 k3s Secret 파일 | Secret 조회 API·파일 판독·문자열 변환 코드 | 기존 기능으로 연결 가능. 실제 설정 바인딩·실패 시 비노출 검증과 연결 기준 추가 |
| 의존성 버전 확인 | GitHub Dependabot | 패키지·이미지 버전 조회 봇 | Gradle·GitHub Actions·Compose의 기존 주간 설정 유지 |
| 시간대 정보 갱신 | iCal4j의 TZURL 갱신 기능 | 시간대 API 클라이언트 | 자동 갱신 비활성 유지. 라이브러리 버전 변경 때 캘린더 결과를 검증 |
| 인증서 발급·갱신 | ACME 지원 인증서 관리자 | CAL 전용 발급·갱신 API 클라이언트 | 준비된 인증서 유지. 갱신은 운영에서 선택한 관리 도구에 연결 |
| 공휴일 | 한국천문연구원 특일 정보 | 설날·추석·대체공휴일 계산 | 일정 원본을 결정하는 BATON에서 활용. CAL에는 추가하지 않음 |
| 캘린더 앱의 빠른 갱신 | Google Calendar·Microsoft Graph | 외부 앱에 일정 생성·수정·삭제 | 계정 연결과 상태 관리가 더 필요해 현재 `.ics` 구독 유지 |
| DNS 변경 | DNS 업체 API·기존 DDNS 도구 | IP 변경 감지·레코드 갱신 스크립트 | IP가 바뀌는 환경이면 기존 DDNS 도구 사용. DNS 업체·변동 여부가 정해지기 전에는 추가하지 않음 |
| 백업 보관 | `pg_dump`와 restic | 백업 암호화·중복 제거·저장소 전송 | 저장소·보존 기간이 정해진 뒤 연결. 기존 논리 백업·복원 절차 유지 |
| 요청 추적 | Spring Boot의 Micrometer·OpenTelemetry 연동 | 추적 ID 전파·전송 API 클라이언트 | 수신 도구와 보존 범위가 정해진 뒤 연결. 기존 공개 URL 토큰 삭제 규칙 유지 |

현재 추가할 새 API 연동은 확인되지 않았다. CAL 제품 코드에는 외부 HTTP 호출을 직접 구현한 부분이
없으며 운영 연동은 기존 도구의 설정으로 처리한다. 남은 후보는 표에 적힌 조건이 정해지면 진행한다.

버전 확인은 [기존 Dependabot 설정](../.github/dependabot.yml)을 사용한다. 원격 작업의 실행 상태나
자동 병합을 이번 검토에서 확인·변경하지 않았다. 고정한 iCal4j 4.3.0의 시간대 외부 갱신은 기본값이
`false`이며 프로젝트에서도 활성화하지 않는다. 자동 갱신으로 같은 일정의 캘린더 바이트·ETag가 달라지는
것을 피하고, 시간대 데이터 변경은 기존 라이브러리 갱신·회귀 검증 절차로 처리한다.

BATON은 `b4ton.com`, 마이크로서비스 공개 호스트는 `<서비스명>.b4ton.com`을 사용한다. CAL은
`cal.b4ton.com`이다. 홈서버의 Ubuntu·DNS·공인 IP·80/443 포트포워딩·인증서는 사용자 확인 기준으로
준비됐고 k3s는 아직 구축 전이다. 이 변경은 연동 설정이며 홈서버 설치나 배포를 수행하지 않는다.

알림은 [Slack 설정](../operations/alertmanager/slack.yml) 또는
[Discord 설정](../operations/alertmanager/discord.yml)을 선택하고 수신 URL을 외부 파일로 제공한다.
기존 웹훅 수신 서버가 있으면 일반 웹훅 설정도 사용할 수 있다. 별도 중계 서비스나 유료 알림 서비스를
추가하지 않으며, 기존 계정에서 웹훅을 등록할 수 있는 권한이 필요하다. [연결 방법](operations.md#운영-알림-채널-연결)

인증서 검사는 토큰 없는 TLS 연결만 사용한다. 준비된 인증서를 교체하거나 갱신하지 않으며,
HTTPS 연결 실패와 만료 임박을 기존 알림 경로로 전달한다.

공개 경로 점검은 인증서와 CAL 내부 상태가 정상인 상황의 프록시 라우팅 장애를 보완한다.
발급될 수 없는 고정 주소의 빈 본문 `404`를 확인하며 프록시 HTML 오류 페이지를 성공으로 보지 않는다.
기존 Blackbox를 재사용하고 CAL 제품 코드는 추가하지 않는다. [점검 기준](operations.md#모니터링과-알림)

외부 정상 신호는 Prometheus → Alertmanager → Healthchecks.io 순서로 전송한다. 같은 홈서버의
전원·네트워크 또는 모니터링 경로가 끊기면 Healthchecks.io가 신호 누락을 알린다. CAL·DB의 정상 여부나
공인 DNS·외부에서 들어오는 HTTPS 접속까지 보장하는 점검은 아니다.
[선택 설정과 연결 방법](operations.md#서버와-모니터링-중단-감지)

비밀번호와 내부 토큰은 CAL 전용 Secret 파일로 전달할 수 있다. Spring Boot가 파일 이름과 내용을
기존 설정에 연결하므로 제품 코드·의존성·별도 서비스 추가가 없다. 실제 k3s 마운트는 배포 시 적용한다.
[파일 이름과 연결 방법](operations.md#secret-파일-연결)

## 적용 기준

- 기본 캘린더 제공은 기존 `.ics` 구독을 유지한다. 외부 API 호출료가 없으며 서버·도메인은 기존 운영 범위다.
- Nylas·Cronofy·AddEvent 같은 중계 서비스를 새로 도입하지 않는다.
- 무료 체험 종료 후 결제, 유료 구독, 초과 사용 과금을 전제로 하는 연동은 제외한다.
- 외부 API를 활성화하기 전에 해당 계정의 무료 범위와 요청 제한을 확인한다.
  무료 범위를 넘으면 동기화를 보류하고, 비용이 발생하는 한도 증액이나 결제 설정을 자동 적용하지 않는다.

## 후보별 비용과 역할

| 후보 | 확인한 비용 조건 | 적용 위치와 남는 작업 |
| --- | --- | --- |
| Healthchecks.io | Hobbyist는 월 0달러, 점검 20개·점검당 기록 100개. 이번 연동은 점검 1개 사용 | 무료 계정에서 점검과 수신 채널을 등록해야 한다. 유료 플랜·문자·전화 알림은 사용하지 않는다. |
| 한국천문연구원 특일 정보 | 포털에 무료로 명시. 활용 신청·인증키·호출 제한 필요 | BATON에서 공휴일 데이터를 저장·갱신한다. 공휴일 표시나 회차 제외 규칙은 BATON이 결정하며 CAL은 확정 일정만 받는다. |
| Google Calendar API | 일반 사용은 추가 요금 없음. 공식 문서는 2026년 중 초과 사용 과금 도입 계획과 일일 무료 기준을 안내하므로 무제한 무료로 취급하지 않는다. | 사용자 동의 후 일정 생성·수정·삭제를 단방향으로 전달한다. 계정 연결, 일정 ID 연결, 재시도와 호출량 제한이 필요하다. |
| Microsoft Graph Calendar API | 표준 API는 이용 자격과 사용 한도 내에서 추가 API 요금 없이 제공된다. 계정·라이선스 조건은 별도다. | 기존 사용 가능한 Outlook 계정의 권한 범위에서 연동한다. 새 유료 Microsoft 365 구독을 구매하는 방식은 제외한다. |

Google 공식 문서의 일일 기준은 프로젝트당 1,000,000회다. 실제 적용 한도는 프로젝트 생성 시기와
설정에 따라 확인해야 한다. 서비스 자체 호출량과 재시도를 제한하고, 제공자 측의 무료 한도 차단 조건을
확인한 뒤 활성화한다. 이 문서는 한도 차단 구현이나 비용 보장을 완료했다는 뜻이 아니다.

## 캘린더 연동 범위

추가 요금과 연동 코드를 늘리지 않도록 기존 구독을 활용한다. BATON에서 주소 발급 후 등록 안내를
자동으로 펼치고 Google·Apple·Outlook 개인/회사·학교 계정 중 선택한 앱의 절차만 표시한다.
Google의 공식 URL 등록 화면과 앱별 공식 페이지를 새 탭으로 연다. 구독 주소는 링크에 넣지 않으며,
주소 복사 실패 시 직접 선택해 복사할 수 있다. 앱별 갱신 주기는 유지되며 실시간 동기화를 뜻하지 않는다.

공휴일 활용은 BATON의 별도 기능이다. 제공자 API 직접 연동은 빠른 갱신이 실제 요구사항으로 확정될 때
아래 조건과 비용을 다시 확인한다.

- 직접 연동: BATON에서 변경이 확정된 뒤 외부 캘린더로 전달한다. 외부 캘린더의 수정으로 BATON 원본을
  변경하지 않는다. 기존 `.ics` 주소 구독과 API를 통한 일정 복사 사이의 중복 등록도 처리해야 한다.
- 계정 연결: 현재 BATON의 `DiscardingOAuth2AuthorizedClientRepository`는 로그인 토큰을 저장하지 않는다.
  로그인 설정을 그대로 캘린더 권한으로 사용하지 않고, 별도 동의·연결 해제·자격 증명 보관을 설계한다.
- 공휴일 활용: 필요한 연도의 데이터를 저장하고 정기 갱신한다. 화면을 열 때마다 외부 API를 호출하지 않는다.
  데이터 변경만으로 이미 확정된 일정을 자동 수정하지 않는다.

## 공식 근거

- [GitHub: Dependabot 버전 확인과 PR](https://docs.github.com/en/code-security/concepts/supply-chain-security/dependabot-version-updates)
- [iCal4j 4.3.0: 시간대 외부 갱신 기본값과 처리](https://github.com/ical4j/ical4j/blob/ical4j-4.3.0/src/main/java/net/fortuna/ical4j/model/TimeZoneUpdater.java)
- [Spring Boot: Secret 파일 설정](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.files.configtree)
- [Kubernetes: Secret 파일 마운트](https://kubernetes.io/docs/concepts/configuration/secret/#using-secrets-as-files-from-a-pod)
- [Healthchecks.io: 무료 플랜](https://healthchecks.io/pricing/)
- [Healthchecks.io: 정상 신호 API와 요청 제한](https://healthchecks.io/docs/http_api/)
- [Healthchecks.io: 주기·유예 시간과 알림](https://healthchecks.io/docs/configuring_checks/)
- [restic: 기존 백업 파일 보관과 중복 제거](https://restic.readthedocs.io/en/stable/040_backup.html)
- [Spring Boot: 표준 추적 연동](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)
- [Alertmanager: 서비스별 기본 제공 알림 연동](https://prometheus.io/docs/alerting/latest/configuration/)
- [Slack Incoming Webhook](https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks/)
- [Discord Webhook API](https://docs.discord.com/developers/resources/webhook)
- [Blackbox Exporter: TLS 점검 설정](https://github.com/prometheus/blackbox_exporter/blob/master/CONFIGURATION.md)
- [Let's Encrypt: ACME 인증 방식](https://letsencrypt.org/docs/challenge-types/)
- [한국천문연구원 특일 정보: 비용·활용 신청·요청 제한](https://www.data.go.kr/data/15012690/openapi.do)
- [Google Calendar API: 무료 범위·초과 사용 과금 계획·요청 제한](https://developers.google.com/workspace/calendar/api/guides/quota)
- [Microsoft Graph: 표준 API와 과금 API 구분](https://learn.microsoft.com/en-us/graph/metered-api-overview)
- [Microsoft Graph: 일정 생성과 계정별 권한](https://learn.microsoft.com/en-us/graph/api/calendar-post-events?view=graph-rest-1.0)
- [Google Calendar: URL 등록 화면과 절차](https://support.google.com/calendar/answer/37100?hl=en)
- [Apple Calendar: 구독 등록](https://support.apple.com/en-ie/guide/iphone/iph3d1110d4/ios)
- [Outlook: 계정별 URL 구독 절차](https://support.microsoft.com/en-us/outlook/import-or-subscribe-to-a-calendar-in-outlook-com-or-outlook-on-the-web)
