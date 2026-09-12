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
docker run --detach --name "$receiver" --network "$project_name" --network-alias alert-receiver \
  --volume "$project_directory/scripts/fixtures/alert-receiver.py:/app/receiver.py:ro" \
  --volume "$scratch:/data:ro" \
  python:3.14.7-alpine3.23@sha256:8caa2adfeb414dfe68d8b257f7aea9e205a400521c2b13b2d2e5e731fb8e70e5 \
  python /app/receiver.py > /dev/null
receiver_url=http://127.0.0.1:8080

wait_message() {
  local title=$1
  for ((attempt=0; attempt<40; attempt++)); do
    request "$receiver_url/alerts" > "$scratch/events.json"
    if jq -e --arg channel "$channel" --arg title "$title" \
      'any(.[]; .channel == $channel and .title == $title and (.text | contains("HTTPS 연결 확인")))' \
      "$scratch/events.json" > /dev/null; then return 0; fi
    sleep 1
  done
  echo "$channel 알림 전달 실패: $title" >&2
  return 1
}

for channel in slack discord; do
  printf '%s\n' "http://alert-receiver:8080/$channel/smoke-secret-marker" > "$scratch/webhook-url"
  docker run --rm --network none --entrypoint amtool \
    --volume "$project_directory/operations/alertmanager/$channel.yml:/config.yml:ro" \
    --volume "$scratch/webhook-url:/run/secrets/alert-webhook-url:ro" \
    "$alertmanager_image" check-config /config.yml > /dev/null
  docker run --detach --name "$sender" --network "$project_name" --network-alias alert-sender \
    --volume "$project_directory/operations/alertmanager/$channel.yml:/config.yml:ro" \
    --volume "$scratch/webhook-url:/run/secrets/alert-webhook-url:ro" \
    "$alertmanager_image" --config.file=/config.yml --cluster.listen-address= > /dev/null
  sender_url=http://alert-sender:9093
  for ((attempt=0; attempt<30; attempt++)); do
    request "$sender_url/-/ready" > /dev/null 2>&1 && break
    sleep 1
  done
  for state in firing resolved; do
    python3 -B - "$state" > "$scratch/alert.json" <<'PY'
from datetime import datetime, timedelta, timezone
import json
import sys
now = datetime.now(timezone.utc)
print(json.dumps([{
    "labels": {"alertname": "CalTlsFailed"},
    "annotations": {"summary": "HTTPS 연결 확인", "description": "스모크 테스트"},
    "startsAt": (now - timedelta(minutes=1)).isoformat(),
    "endsAt": (now + timedelta(minutes=5) if sys.argv[1] == "firing" else now).isoformat(),
}]))
PY
    request --header 'Content-Type: application/json' --post-file /data/alert.json \
      "$sender_url/api/v2/alerts" > /dev/null
    if [[ "$state" == firing ]]; then wait_message '장애 발생: CalTlsFailed'; else wait_message '복구: CalTlsFailed'; fi
  done
  docker logs "$sender" > "$scratch/sender.log" 2>&1
  if grep -Fq smoke-secret-marker "$scratch/sender.log"; then
    echo "알림 로그에 웹훅 주소가 기록됐습니다." >&2
    exit 1
  fi
  docker rm --force "$sender" > /dev/null
  echo "$channel 기본 연동의 알림 발생·해제와 웹훅 주소 비노출을 확인했습니다."
done
