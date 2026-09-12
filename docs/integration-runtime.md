# CAL 연동 실행 준비

CAL의 API·웹훅 연결에 필요한 애플리케이션 입력을 정리한다. 서버·공유기·DNS·인증서 발급·k3s 설치와
배포는 포함하지 않는다. 이미지 빌드는 배포 담당자가 [기존 빌드 절차](../README.md#oci-이미지-검증)로 수행한다.

## 외부 연동 선택

| 기능 | 사용할 연동 | 준비 상태 |
| --- | --- | --- |
| Google·Apple·Outlook 캘린더 | 표준 `.ics` 구독 | 발급·조회·해제와 앱별 안내 구현. 실제 앱의 갱신 확인은 배포 후 필요 |
| 공휴일 | 한국천문연구원 특일 정보 API | BATON에 수집 코드가 있다. BATON의 키와 활성화 설정이 필요하며 CAL은 확정 일정만 수신 |
| 장애·복구 알림 | Alertmanager의 Slack·Discord 웹훅 | 기본 제공 발송·재시도 기능 사용. 단독·Healthchecks 조합 중 선택 |
| 서버 중단 감지 | Alertmanager → Healthchecks.io | 채널별 조합과 모의 수신 검증 구현. 사용할 계정의 무료 점검과 수신 채널 등록 필요 |
| 내부 API의 HTTPS | Spring Boot PEM SSL bundle | `tls` 프로필 추가. 인증서·개인키 파일로 HTTPS를 제공하며 별도 서버 코드는 없음 |
| DB 비밀번호·내부 토큰 | Spring Boot `configtree` | Secret 파일 연결 지원. 별도 비밀 관리 API·파일 파서는 없음 |

CAL에 추가할 새 제공자 API는 선정하지 않았다. 현재 기능에서는 계정 동의·토큰 보관·동기화 상태 관리가
늘어나는 캘린더 제공자 API보다 기존 구독이 간단하다. 구독은 앱의 주기적 조회이며 실시간 푸시는 아니다.
[비용·선택 근거](external-api-options.md)

## 애플리케이션 입력

[`.env.production.example`](../.env.production.example)을 기준으로 아래 값을 전달한다.
`.env` 파일 자체는 Spring Boot가 읽지 않는다. k3s에서는 환경변수와 Secret 파일로 전달하고,
로컬 셸에서 직접 실행할 때는 값을 채운 파일을 `set -a; source <파일>; set +a`로 읽는다.

| 입력 | 값·파일 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | 프록시에서 TLS를 종료하면 `prod`, CAL이 직접 HTTPS를 제공하면 `prod,tls` |
| `SPRING_CONFIG_IMPORT` | `configtree:/run/secrets/baton-cal/` — 끝의 `/` 포함 |
| `DATABASE_URL`, `DATABASE_USERNAME` | CAL 전용 PostgreSQL 주소·계정. 예시의 `postgres`는 실제 DB 호스트로 변경 |
| `DATABASE_PASSWORD` | 위 Secret 디렉터리의 같은 이름 파일 |
| `BATON_CAL_INTERNAL_TOKEN` | 위 Secret 디렉터리의 같은 이름 파일. BATON 연결 시 32~200자, 영문·숫자와 `-._~`만 사용 |
| `BATON_CAL_PUBLIC_BASE_URL` | `https://cal.b4ton.com` |
| `BATON_CAL_SUBSCRIPTION_GENERATION` | 새 DB 최초 실행 때 정한 UUID. 기존 DB는 사용하던 값을 유지 |
| `BATON_CAL_TLS_CERTIFICATE` | `tls` 사용 시 PEM 인증서 체인. 예: `file:/run/secrets/baton-cal-tls/tls.crt` |
| `BATON_CAL_TLS_PRIVATE_KEY` | `tls` 사용 시 PEM 개인키. 예: `file:/run/secrets/baton-cal-tls/tls.key` |

CAL 자체는 RFC 6750 형식을 허용하지만 BATON의 전송 설정은 더 좁은 문자·길이 범위를 요구한다.
위 토큰 기준은 두 서비스가 함께 쓸 수 있는 범위다. 일반 Base64의 `+`, `/`, `=`는 BATON에서 거부된다.
Python `secrets.token_urlsafe(48)`로 생성한 값은 이 범위에 맞는다. 로컬 임시 토큰도 이 방식으로 준비했다.

이전 내부 토큰은 회전 기간에만 `baton.cal.previous-internal-token` 파일로 추가한다. 사용하지 않을 때는
빈 파일을 만들지 않는다. Secret 값이나 인증서 교체 후에는 애플리케이션을 재시작한다. 토큰 원문·개인키는
이미지나 Git에 넣지 않는다. [Secret 연결 기준](operations.md#secret-파일-연결)

## 공개 주소와 BATON API 주소

| 연결 | 경로 | 조건 |
| --- | --- | --- |
| 캘린더 앱 → CAL | `https://cal.b4ton.com/calendars/v1/{token}.ics` | 공개 GET·HEAD만 허용 |
| BATON → CAL | `/internal/api/v1/**` | 사설 연결, HTTPS, CAL 전용 Bearer 필요 |
| 상태 점검·Prometheus → CAL | 내부 `8081/actuator/**` | HTTP 관리 포트. 외부 공개 제외 |

BATON의 `BATON_CAL_BASE_URL`은 **내부 API에 도달하는 HTTPS 출처**여야 한다. `/internal/api/v1`을
값에 붙이지 않는다. 현재 공개 Nginx는 내부 API에 `404`를 반환하므로 공개 주소만 넣어서는 연결되지 않는다.
BATON의 HTTPS 검증을 끄거나 내부 API를 공개해 해결하지 않는다.

`prod,tls`에서는 `SERVER_PORT`(기본 8080)가 HTTPS로 바뀌고, 관리 포트는 HTTP를 유지한다.
BATON에서 인증서의 호스트 이름과 신뢰 체인을 검증할 수 있어야 한다. 예를 들어
`https://cal.b4ton.com:8080`을 사용하려면 BATON 실행 환경에서 이 이름이 CAL의 사설 주소로 해석되고
해당 포트로 연결돼야 한다. 공개 라우터에 8080을 열라는 뜻이 아니다. 서비스 포트가 다르면 그 포트를 쓴다.

공개 프록시도 CAL의 HTTPS 포트로 전달하도록 운영에서 맞춰야 한다. 기존 `compose.operations.yml`과
Nginx 예시는 CAL의 HTTP 포트를 전제로 하므로 `tls` 프로필만 켜서 함께 실행할 수 없다.
별도 내부 HTTPS 프록시를 사용하는 구성에서는 CAL을 기존 `prod`로 실행해도 된다.

BATON의 `BATON_CAL_BEARER_TOKEN` 또는 해당 Secret 파일에는 CAL 현재 내부 토큰과 같은 값을 전달한다.
캡처·전달·구독 활성화와 기존 데이터 보정 순서는 BATON의
[연동 문서](https://github.com/ljkhyeong/baton/blob/main/docs/runbooks/free-integrations.md)를 따른다.

## 웹훅과 검증

| 수신 채널 | 채널 알림만 사용 | Healthchecks도 사용 |
| --- | --- | --- |
| Slack | [slack.yml](../operations/alertmanager/slack.yml) | [slack-healthchecks.yml](../operations/alertmanager/slack-healthchecks.yml) |
| Discord | [discord.yml](../operations/alertmanager/discord.yml) | [discord-healthchecks.yml](../operations/alertmanager/discord-healthchecks.yml) |

선택한 파일을 Alertmanager 설정으로 사용한다. 채널 웹훅 URL은 `/run/secrets/alert-webhook-url`,
Healthchecks 성공 URL은 `/run/secrets/healthchecks-ping-url` 파일로 전달한다. CAL 환경변수에 넣지 않는다.
조합 설정은 정상 신호를 Healthchecks로만 보내고, Slack·Discord에는 장애와 복구만 알린다.
실제 채널 설정 전에도 아래 검증은 외부 메시지를 보내지 않고 실행할 수 있다.

```shell
./gradlew --no-daemon --max-workers=2 test \
  --tests 'io.baton.cal.config.TlsHttpIntegrationTest' \
  --tests 'io.baton.cal.config.ProductionDatasourceConfigurationTest' bootJar
bash scripts/smoke-alert-channels.sh
```

HTTPS 테스트는 임시 인증서와 격리된 PostgreSQL을 사용해 인증, 구독 발급·조회·해제, 공개 호스트,
관리 포트 분리와 토큰 비노출을 확인한다. 인증서 검증을 끄지 않는다. 웹훅 검증은 외부 통신이 차단된
Docker 네트워크에서 채널별 단독·Healthchecks 조합의 장애·복구 메시지와 정상 신호 분리를 확인한다.
실행에는 Docker와 Java 25 툴체인이 필요하다.
실행 JAR는 `build/libs/baton-cal-0.0.1-SNAPSHOT.jar`에 생성된다.

로컬 임시값은 Git에서 제외되는 `.env.integration.local`과 `build/local-integration/`에 둔다.
운영 자격 증명이 아니며 임시 인증서는 생성 후 2일 동안 유효하다. 로컬 PostgreSQL 주소·계정·비밀번호는
실행 환경에 맞춰야 한다. 실제 DNS·인증서·Secret 마운트, 공개 경로 차단과 캘린더 앱 갱신은 배포 후 확인한다.

HTTPS는 [Spring Boot SSL bundle](https://docs.spring.io/spring-boot/reference/features/ssl.html)을 사용한다.
인증서 발급·갱신이나 k3s 연결을 애플리케이션이 대신 수행하지 않는다.
