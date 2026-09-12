#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
  echo "사용법: $0 <로컬 OCI 이미지 이름>" >&2
  exit 2
fi
project_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
project_name="baton-cal-operations-$(date +%s)-$$"
scratch=$(mktemp -d "${TMPDIR:-/tmp}/baton-cal-operations.XXXXXX")
export BATON_CAL_IMAGE="$1"
export DATABASE_USERNAME=operations_smoke
export DATABASE_PASSWORD=operations-smoke-only
export BATON_CAL_INTERNAL_TOKEN=operations-smoke-internal-token-that-is-long-enough
export BATON_CAL_SUBSCRIPTION_GENERATION=60000000-0000-0000-0000-000000000001
export BATON_CAL_RECOVERY_MODE=false
unset BATON_CAL_PREVIOUS_INTERNAL_TOKEN
export CAL_GATEWAY_BIND=127.0.0.1
export CAL_INTERNAL_PORT=0 CAL_HTTP_PORT=0 CAL_HTTPS_PORT=0 CAL_PROMETHEUS_PORT=0 CAL_ALERTMANAGER_PORT=0
export CAL_REQUEST_RATE=10r/s CAL_REQUEST_BURST=20 CAL_CONNECTION_LIMIT=20
export CAL_TLS_DIRECTORY="$scratch/tls" CAL_ACME_DIRECTORY="$scratch/acme"
export CAL_ALERT_WEBHOOK_URL_FILE="$scratch/webhook-url"
export CAL_HEALTHCHECKS_PING_URL_FILE="$scratch/healthchecks-url"
export CAL_ALERTMANAGER_CONFIG_FILE="$project_directory/operations/alertmanager/healthchecks.yml"
compose=(docker compose --ansi never --project-name "$project_name"
  --file "$project_directory/compose.operations.yml" --file "$project_directory/compose.healthchecks.yml"
  --file "$project_directory/compose.operations-smoke.yml")
request=(curl --silent --show-error --connect-timeout 2 --max-time 10)

cleanup() {
  local result=$?
  trap - EXIT
  if ((result != 0)); then
    "${compose[@]}" ps --all >&2 || true
    # 원문 로그는 실패 시에도 출력하지 않는다. 아래 비노출 검사에서 누출 가능성을 확인한다.
    echo "운영 스모크가 실패했습니다. 외부 운영 환경에는 변경이 없습니다." >&2
  fi
  "${compose[@]}" down --volumes --remove-orphans || result=1
  rm -rf "$scratch"
  exit "$result"
}
trap cleanup EXIT

for required in docker curl jq openssl; do command -v "$required" >/dev/null; done
mkdir -p "$CAL_TLS_DIRECTORY/live/cal.b4ton.com" "$CAL_ACME_DIRECTORY/.well-known/acme-challenge"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=cal.b4ton.com \
  -addext subjectAltName=DNS:cal.b4ton.com \
  -keyout "$CAL_TLS_DIRECTORY/live/cal.b4ton.com/privkey.pem" \
  -out "$CAL_TLS_DIRECTORY/live/cal.b4ton.com/fullchain.pem" >"$scratch/openssl.log" 2>&1
printf '%s\n' http://alert-receiver:8080/alerts > "$CAL_ALERT_WEBHOOK_URL_FILE"
printf '%s\n' http://alert-receiver:8080/healthchecks/heartbeat-secret-marker > "$CAL_HEALTHCHECKS_PING_URL_FILE"
printf '%s' acme-smoke > "$CAL_ACME_DIRECTORY/.well-known/acme-challenge/smoke"

"${compose[@]}" config --quiet
"${compose[@]}" run --rm --no-deps --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml
"${compose[@]}" run --rm --no-deps --entrypoint promtool prometheus test rules /etc/prometheus/alerts.test.yml
"${compose[@]}" run --rm --no-deps --entrypoint amtool alertmanager check-config /etc/alertmanager/alertmanager.yml
"${compose[@]}" up --detach --wait --wait-timeout 120
"${compose[@]}" exec -T gateway nginx -t

address() { "${compose[@]}" port "$1" "$2"; }
internal_url="http://$(address app 8080)"
management_url="http://$(address app 8081)"
prometheus_url="http://$(address prometheus 9090)"
receiver_url="http://$(address alert-receiver 8080)"
alertmanager_url="http://$(address alertmanager 9093)"
blackbox_url="http://$(address blackbox 9115)"
https_address=$(address gateway 443)
https_port=${https_address##*:}
public_url="https://cal.b4ton.com:$https_port"
tls=(--cacert "$CAL_TLS_DIRECTORY/live/cal.b4ton.com/fullchain.pem" --resolve "cal.b4ton.com:$https_port:127.0.0.1" --noproxy '*')

wait_ready() {
  management_url="http://$(address app 8081)"
  for ((attempt=0; attempt<90; attempt++)); do
    if "${request[@]}" --fail "$management_url/actuator/health/readiness" > "$scratch/readiness.json" 2>/dev/null; then
      jq -e '.status == "UP"' "$scratch/readiness.json" >/dev/null && return 0
    fi
    sleep 1
  done
  echo "준비 상태 대기 시간을 넘었습니다." >&2
  return 1
}
wait_ready

"${request[@]}" --fail --get --data-urlencode module=cal_tls --data-urlencode target=gateway:443 \
  "$blackbox_url/probe" > "$scratch/tls-probe"
grep -q '^probe_success 1$' "$scratch/tls-probe"
grep -q '^probe_ssl_earliest_cert_expiry ' "$scratch/tls-probe"
"${request[@]}" --fail --get --data-urlencode module=cal_tls --data-urlencode target=alert-receiver:8080 \
  "$blackbox_url/probe" > "$scratch/tls-rejected"
grep -q '^probe_success 0$' "$scratch/tls-rejected"
echo "Blackbox의 인증서 검증·만료 시각 수집과 TLS 연결 실패 탐지를 확인했습니다."

"${request[@]}" --fail --get --data-urlencode module=cal_public_route \
  --data-urlencode target=https://gateway/calendars/v1/monitoring-probe.ics \
  "$blackbox_url/probe" > "$scratch/public-probe"
grep -q '^probe_success 1$' "$scratch/public-probe"
grep -q '^probe_http_status_code 404$' "$scratch/public-probe"
# Nginx의 차단 페이지도 404이므로, 본문이 있는 응답은 정상으로 보지 않아야 한다.
"${request[@]}" --fail --get --data-urlencode module=cal_public_route --data-urlencode target=https://gateway/ \
  "$blackbox_url/probe" > "$scratch/public-rejected"
grep -q '^probe_success 0$' "$scratch/public-rejected"
grep -q '^probe_http_status_code 404$' "$scratch/public-rejected"
echo "실제 구독 토큰 없이 공개 경로를 확인하고, 프록시의 404 오류 페이지를 구분했습니다."

"${request[@]}" --fail -H "Authorization: Bearer $BATON_CAL_INTERNAL_TOKEN" \
  -H 'Content-Type: application/json' --data-binary @"$project_directory/contracts/examples/schedule-snapshot.utc-active.json" \
  "$internal_url/internal/api/v1/schedule-snapshots" > /dev/null
"${request[@]}" --fail -H "Authorization: Bearer $BATON_CAL_INTERNAL_TOKEN" \
  -H 'Content-Type: application/json' --data-binary @"$project_directory/contracts/examples/subscription-create.json" \
  "$internal_url/internal/api/v1/subscriptions" > "$scratch/credential.json"
feed_path=$(jq -r '"/calendars/v1/" + .token + ".ics"' "$scratch/credential.json")
token=$(jq -r .token "$scratch/credential.json")
status=$("${request[@]}" "${tls[@]}" -D "$scratch/headers" -o "$scratch/feed" -w '%{http_code}' "$public_url$feed_path")
[[ "$status" == 200 ]]
grep -q 'BEGIN:VEVENT' "$scratch/feed"
etag=$(awk 'tolower($1) == "etag:" {gsub("\r", ""); print $2}' "$scratch/headers")
[[ -n "$etag" ]]
status=$("${request[@]}" "${tls[@]}" -H "If-None-Match: $etag" -o "$scratch/unchanged" -w '%{http_code}' "$public_url$feed_path")
[[ "$status" == 304 && ! -s "$scratch/unchanged" ]]

for path in /internal/api/v1/subscriptions /actuator/prometheus /actuator/health/readiness; do
  status=$("${request[@]}" "${tls[@]}" -o /dev/null -w '%{http_code}' "$public_url$path")
  [[ "$status" == 404 ]]
done
[[ "$("${request[@]}" "${tls[@]}" -X POST -o /dev/null -w '%{http_code}' "$public_url$feed_path")" == 405 ]]
http_url="http://$(address gateway 80)"
[[ "$("${request[@]}" "$http_url/.well-known/acme-challenge/smoke")" == acme-smoke ]]
[[ "$("${request[@]}" -o /dev/null -w '%{http_code}' "$http_url$feed_path")" == 308 ]]

for ((index=0; index<80; index++)); do
  "${request[@]}" "${tls[@]}" -D "$scratch/rate-header-$index" -o /dev/null -w '%{http_code}\n' \
    "$public_url$feed_path?probe=query-smoke-marker" > "$scratch/rate-$index" &
done
wait
grep -l '^429$' "$scratch"/rate-[0-9]* > "$scratch/rejected"
[[ -s "$scratch/rejected" ]]
while IFS= read -r rejected; do
  index=${rejected##*-}
  grep -qi '^Retry-After: 1' "$scratch/rate-header-$index"
  grep -qi '^Cache-Control: no-store' "$scratch/rate-header-$index"
done < "$scratch/rejected"
echo "HTTPS, 피드 200·304, 내부 경로 차단, ACME 경로와 요청 초과 429를 확인했습니다."

for ((attempt=0; attempt<30; attempt++)); do
  "${request[@]}" --fail --get --data-urlencode 'query=up{job="baton-cal"}' \
    "$prometheus_url/api/v1/query" > "$scratch/query.json"
  jq -e '.data.result[0].value[1] == "1"' "$scratch/query.json" >/dev/null && break
  sleep 1
done
jq -e '.data.result[0].value[1] == "1"' "$scratch/query.json" >/dev/null

wait_alert() {
  local name=$1 expected=$2
  for ((attempt=0; attempt<100; attempt++)); do
    "${request[@]}" --fail "$receiver_url/alerts" > "$scratch/alerts.json"
    if jq -e --arg name "$name" --arg state "$expected" --argjson offset "$alert_offset" 'any(.[$offset:][]; .alertname == $name and .status == $state)' \
      "$scratch/alerts.json" >/dev/null; then return 0; fi
    sleep 1
  done
  echo "$name 알림의 $expected 전달을 확인하지 못했습니다." >&2
  return 1
}
alert_offset=$("${request[@]}" --fail "$receiver_url/alerts" | jq length)
"${compose[@]}" stop app
# 제한 대기열을 비운 뒤 실제 upstream 실패 경로에서도 비밀 URL을 보내 로그를 검사한다.
sleep 3
# 연결 거부는 502, 연결 시간 초과는 504로 응답한다.
status=$("${request[@]}" "${tls[@]}" -o /dev/null -w '%{http_code}' "$public_url$feed_path?probe=query-smoke-marker")
if [[ "$status" != 502 && "$status" != 504 ]]; then
  echo "CAL 중단 후 프록시 응답이 502 또는 504가 아닙니다: $status" >&2
  exit 1
fi
echo "CAL 중단 후 프록시의 $status 응답을 확인했습니다."
wait_alert CalReadinessFailed firing
wait_alert CalPublicRouteFailed firing
echo "CAL 중단으로 발생한 준비 상태·공개 경로 실패 알림이 로컬 수신기에 도착했습니다."
alert_offset=$("${request[@]}" --fail "$receiver_url/alerts" | jq length)
"${compose[@]}" start app
wait_ready
wait_alert CalReadinessFailed resolved
wait_alert CalPublicRouteFailed resolved
echo "CAL 재시작 뒤 알림 해제 전달을 확인했습니다."

heartbeat_count() {
  "${request[@]}" --fail "$receiver_url/alerts" | jq '[.[] | select(.channel == "healthchecks")] | length'
}
wait_heartbeat() {
  local expected=$1
  for ((attempt=0; attempt<80; attempt++)); do
    if (( $(heartbeat_count) >= expected )); then return 0; fi
    sleep 1
  done
  echo "외부 정상 신호 대기 시간을 넘었습니다." >&2
  return 1
}
wait_heartbeat 2
# 다음 신호를 받은 직후 원본 갱신을 멈추고 만료시켜, 진행 중인 전송과 경합하지 않는다.
expected=$(( $(heartbeat_count) + 1 ))
wait_heartbeat "$expected"
"${compose[@]}" stop prometheus
jq -n '[{labels: {alertname: "CalWatchdog", severity: "none"},
  startsAt: (now - 60 | todate), endsAt: (now | todate)}]' > "$scratch/watchdog-resolved.json"
"${request[@]}" --fail -H 'Content-Type: application/json' --data-binary @"$scratch/watchdog-resolved.json" \
  "$alertmanager_url/api/v2/alerts" > /dev/null
for ((attempt=0; attempt<65; attempt++)); do
  if (( $(heartbeat_count) != expected )); then
    echo "정상 신호 해제 후에도 외부 점검 요청이 전송됐습니다." >&2
    exit 1
  fi
  sleep 1
done
"${compose[@]}" start prometheus
prometheus_url="http://$(address prometheus 9090)"
wait_heartbeat "$((expected + 1))"
"${request[@]}" --fail "$receiver_url/alerts" | jq -e 'all(.[]; .alertname != "CalWatchdog")' > /dev/null
echo "외부 정상 신호의 반복 전송·해제 후 중단·재개와 일반 알림 분리를 확인했습니다."

"${compose[@]}" logs --no-color > "$scratch/logs"
"${request[@]}" --fail "$management_url/actuator/prometheus" > "$scratch/metrics"
"${request[@]}" --fail --get --data-urlencode 'match[]={job="baton-cal"}' \
  "$prometheus_url/api/v1/series" > "$scratch/series"
for secret in "$token" "$BATON_CAL_INTERNAL_TOKEN" query-smoke-marker heartbeat-secret-marker; do
  if grep -Fq "$secret" "$scratch/logs" "$scratch/metrics" "$scratch/series"; then
    echo "로그 또는 관측 데이터에서 비밀 표식이 발견됐습니다. 원문은 출력하지 않습니다." >&2
    exit 1
  fi
done
echo "운영 스모크 통과: HTTPS·요청 제한·격리·알림 발생과 해제·토큰 비노출."
