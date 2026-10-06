---
name: baton-cal-operations
description: BATON CAL의 Compose, Nginx 공개 프록시, Prometheus·Alertmanager·Blackbox 규칙, Slack·Discord·Healthchecks 연동, TLS·Secret 파일, OCI 이미지와 운영 스모크를 변경하거나 검증할 때 사용한다. 실제 서버·DNS·인증서·알림 채널 변경은 포함하지 않는다.
---

# BATON CAL 운영 구성

실제 서버·DNS·인증서·k3s·알림 채널·Healthchecks 계정은 사용자가 해당 작업을 요청할 때만 다룬다.
로컬 스모크 통과를 운영 연결이나 실제 수신 확인으로 보고하지 않는다.

## 기준 문서

| 주제 | 문서 |
| --- | --- |
| 연결·공개 범위, Secret 파일, 알림 채널, Healthchecks, 최초 HTTPS, 요청 제한과 로그 | `docs/operations.md` |
| 애플리케이션 입력, 공개·API 주소, 인증서 갱신, k3s 점검 경로, 웹훅 조합 | `docs/integration-runtime.md` |
| 운영 프로필·로그 경계, 공개 프록시와 관측 구성 | `docs/ADR/0002_technology-stack/adr.md`의 해당 절 |
| 새 외부 연동 판단 | `docs/external-api-options.md`. 추가 요금 없는 기존 도구 기능을 우선한다 |
| 이미지 빌드·실행 검증 | `README.md`의 `OCI 이미지 검증` |

## 파일

| 경로 | 역할 |
| --- | --- |
| `compose.operations.yml` | 단일 Docker 호스트 운영 구성. Nginx·CAL·PostgreSQL·Prometheus·Alertmanager·Blackbox |
| `compose.healthchecks.yml` | Healthchecks 정상 신호를 더하는 Alertmanager 덮어쓰기 |
| `compose.operations-smoke.yml` | 운영·이미지 스모크가 운영 구성에 덧붙이는 스모크 전용 구성. 운영에 포함하지 않는다 |
| `operations/nginx/` | 공개 프록시, 요청 제한, 내부·관리 경로 차단 |
| `operations/prometheus/alerts.yml`, `alerts.test.yml` | 알림 규칙과 `promtool` 규칙 테스트 |
| `operations/alertmanager/*.yml` | 일반 웹훅·Slack·Discord 단독과 Healthchecks 조합 |
| `operations/blackbox/blackbox.yml` | TLS 인증서·공개 경로 점검 |
| `.env.example`, `.env.production.example` | 입력 예시. 실제 값은 넣지 않는다 |
| `scripts/smoke-*.sh`, `scripts/fixtures/alert-receiver.py` | 이미지·운영·알림 채널 스모크와 모의 수신기 |

## 변경 원칙

- 구독 토큰·내부 Bearer·웹훅 URL·DB 비밀번호를 Compose·Git·명령 인수·로그·메트릭 라벨에 넣지 않는다.
  비밀값은 파일 경로(`*_FILE`)나 Spring Boot `configtree`로 전달한다.
- 공개 프록시는 내부 API·관리 경로를 `404`로 막고 공개 피드만 전달한다. 규칙을 바꾸면 스모크의 차단 검사도 함께 확인한다.
- 재시도·알림 형식·인증서 갱신은 Alertmanager·Spring Boot SSL bundle 같은 기존 도구 기능을 먼저 사용하고
  제품 코드를 추가하지 않는다.
- 외부 이미지는 버전 태그와 `@sha256` digest로 고정하고, CAL 이미지는 커밋 SHA나 digest로 지정한다.
- `subPath` Secret 마운트는 파일 갱신이 반영되지 않으므로 디렉터리 전체를 읽기 전용으로 마운트한다.
- 알림 규칙을 바꾸면 `alerts.test.yml`에 발생·해제 사례를 추가한다.

## 검증

| 변경 | 로컬 검증 |
| --- | --- |
| Nginx·Prometheus·Alertmanager·Blackbox·Compose | `./gradlew --no-daemon bootBuildImage --imageName=baton-cal:smoke` 후 `./scripts/smoke-operations.sh baton-cal:smoke` |
| Alertmanager 채널 조합·모의 수신기 | `bash scripts/smoke-alert-channels.sh` |
| 이미지·런타임 설정 | `./scripts/smoke-oci-image.sh <이미지>` |
| `tls`·`prod` 프로필 | `./gradlew --no-daemon test --tests 'io.baton.cal.config.TlsHttpIntegrationTest' --tests 'io.baton.cal.config.ProductionDatasourceConfigurationTest'` |

`smoke-operations.sh`는 `promtool check config`·`promtool test rules`·`amtool check-config`·`nginx -t`를 포함한다.
제품 코드·의존성이 같으면 기존 이미지를 재사용하고 근거를 기록한다. Docker 검증 전에 `docker ps`로 다른 실행과
겹치지 않는지 확인한다. 스크립트는 자기 Compose 프로젝트만 정리하며 다른 작업의 컨테이너는 종료하지 않는다.

결과 보고에는 실제 제공자 전달, `Retry-After` 대기 준수, 실제 DNS·인증서처럼 검증하지 않은 범위를 함께 적는다.
