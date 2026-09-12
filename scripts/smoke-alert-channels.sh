#!/usr/bin/env bash
set -euo pipefail

project_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
scratch=$(mktemp -d "${TMPDIR:-/tmp}/baton-cal-alerts.XXXXXX")
project_name="baton-cal-alerts-$(date +%s)-$$"
receiver="$project_name-receiver"
sender="$project_name-sender"
alertmanager_image=prom/alertmanager:v0.32.1@sha256:51a825c2a40acc3e338fdd00d622e01ec090f72be2b3ea46be0839cd47a4d286
request() { docker exec "$receiver" wget -q -T 5 -O - "$@"; }

cleanup() {
  local result=$?
  trap - EXIT
  docker rm --force "$sender" "$receiver" > /dev/null 2>&1 || true
  docker network rm "$project_name" > /dev/null 2>&1 || true
  rm -rf "$scratch"
  exit "$result"
}
trap cleanup EXIT
for required in docker jq python3; do command -v "$required" >/dev/null; done

# 실제 메시지를 보내지 않도록 외부 통신이 차단된 검증 네트워크를 사용한다.
docker network create --internal "$project_name" > /dev/null
receiver_url=http://127.0.0.1:8080
printf '%s\n' http://alert-receiver:8080/healthchecks/smoke-secret-marker > "$scratch/healthchecks-url"

wait_message() {
  local title=$1
  for ((attempt=0; attempt<40; attempt++)); do
    request "$receiver_url/alerts" > "$scratch/events.json"
    if jq -e --arg channel "$channel" --arg title "$title" \
      'any(.[]; .channel == $channel and .title == $title and (.text | contains("HTTPS 연결 확인")))' \
      "$scratch/events.json" > /dev/null; then return 0; fi
    sleep 1
  done
  echo "$configuration 알림 전달 실패: $title" >&2
  return 1
}

for configuration in alertmanager slack discord slack-healthchecks discord-healthchecks; do
  channel=${configuration%-healthchecks}
  watchdog_receiver=discard
  if [[ "$configuration" == *-healthchecks ]]; then watchdog_receiver=healthchecks; fi
  printf '%s\n' "http://alert-receiver:8080/$channel/smoke-secret-marker" > "$scratch/webhook-url"
  config_volumes=(
    --volume "$project_directory/operations/alertmanager/$configuration.yml:/config.yml:ro"
    --volume "$scratch/webhook-url:/run/secrets/alert-webhook-url:ro"
    --volume "$scratch/healthchecks-url:/run/secrets/healthchecks-ping-url:ro"
  )
  docker run --rm --network none --entrypoint amtool \
    "${config_volumes[@]}" \
    "$alertmanager_image" check-config /config.yml > /dev/null
  docker run --rm --network none --entrypoint amtool \
    "${config_volumes[@]}" \
    "$alertmanager_image" config routes test --config.file=/config.yml \
    --verify.receivers="$watchdog_receiver" alertname=CalWatchdog > /dev/null
  docker run --rm --network none --entrypoint amtool \
    "${config_volumes[@]}" \
    "$alertmanager_image" config routes test --config.file=/config.yml \
    --verify.receivers=operations alertname=CalTlsFailed > /dev/null
  if [[ "$channel" == alertmanager ]]; then continue; fi
  # 조합마다 새 수신기를 사용해 이전 수신 기록이 검증에 섞이지 않게 한다.
  docker run --detach --name "$receiver" --network "$project_name" --network-alias alert-receiver \
    --env ALERT_TEST_FAILURES=429,503 \
    --volume "$project_directory/scripts/fixtures/alert-receiver.py:/app/receiver.py:ro" \
    --volume "$scratch:/data:ro" \
    python:3.14.7-alpine3.23@sha256:8caa2adfeb414dfe68d8b257f7aea9e205a400521c2b13b2d2e5e731fb8e70e5 \
    python /app/receiver.py > /dev/null
  docker run --detach --name "$sender" --network "$project_name" --network-alias alert-sender \
    "${config_volumes[@]}" \
    "$alertmanager_image" --config.file=/config.yml --cluster.listen-address= > /dev/null
  sender_url=http://alert-sender:9093
  for ((attempt=0; attempt<30; attempt++)); do
    if request "$receiver_url/alerts" > /dev/null 2>&1 && \
      request "$sender_url/-/ready" > /dev/null 2>&1; then break; fi
    sleep 1
  done
  if ((attempt == 30)); then
    echo "$configuration 알림 송신기 또는 수신기가 준비되지 않았습니다." >&2
    exit 1
  fi
  for state in firing resolved; do
    python3 -B - "$state" > "$scratch/alert.json" <<'PY'
from datetime import datetime, timedelta, timezone
import json
import sys
now = datetime.now(timezone.utc)
print(json.dumps([{
    "labels": {"alertname": name},
    "annotations": {"summary": "HTTPS 연결 확인", "description": "스모크 테스트"},
    "startsAt": (now - timedelta(minutes=1)).isoformat(),
    "endsAt": (now + timedelta(minutes=5) if sys.argv[1] == "firing" else now).isoformat(),
} for name in ("CalTlsFailed", "CalWatchdog")]))
PY
    request --header 'Content-Type: application/json' --post-file /data/alert.json \
      "$sender_url/api/v2/alerts" > /dev/null
    if [[ "$state" == firing ]]; then wait_message '장애 발생: CalTlsFailed'; else wait_message '복구: CalTlsFailed'; fi
  done
  request "$receiver_url/alerts" > "$scratch/events.json"
  if ! jq -e --arg channel "$channel" --arg watchdog "$watchdog_receiver" \
    '([.[] | select(.channel == $channel)] | length == 2) and
     (all(.[] | select(.channel == $channel); .title | contains("CalWatchdog") | not)) and
     (([.[] | select(.channel == "healthchecks")] | length > 0) == ($watchdog == "healthchecks"))' \
    "$scratch/events.json" > /dev/null; then
    echo "$configuration 알림 건수 또는 정상 신호의 수신 경로가 다릅니다." >&2
    exit 1
  fi
  request "$receiver_url/rejections" > "$scratch/rejections.json"
  if ! jq -e --arg channel "$channel" --arg watchdog "$watchdog_receiver" \
    '. as $attempts |
     (all(["장애 발생: CalTlsFailed", "복구: CalTlsFailed"][]; . as $title |
       [$attempts[] | select(.channel == $channel and .title == $title) | .status] == [429, 503])) and
     ([$attempts[] | select(.channel == "healthchecks") | .status] ==
       (if $watchdog == "healthchecks" then [429, 503] else [] end))' \
    "$scratch/rejections.json" > /dev/null; then
    echo "$configuration 429·503 응답 후 재전송을 확인하지 못했습니다." >&2
    exit 1
  fi
  docker logs "$sender" > "$scratch/sender.log" 2>&1
  if grep -Fq smoke-secret-marker "$scratch/sender.log"; then
    echo "알림 로그에 웹훅 주소가 기록됐습니다." >&2
    exit 1
  fi
  docker rm --force "$sender" "$receiver" > /dev/null
  echo "$configuration 429·503 후 알림 발생·해제, 정상 신호 분리와 오류 로그의 웹훅 주소 비노출을 확인했습니다."
done
