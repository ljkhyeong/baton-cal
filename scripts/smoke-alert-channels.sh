#!/usr/bin/env bash
set -euo pipefail

project_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
scratch=$(mktemp -d "${TMPDIR:-/tmp}/baton-cal-alerts.XXXXXX")
project_name="baton-cal-alerts-$(date +%s)-$$"
receiver="$project_name-receiver"
sender="$project_name-sender"
request() { docker exec "$receiver" wget -q -T 5 -O - "$@"; }
# 조건 명령이 성공할 때까지 1초 간격으로 최대 횟수만큼 실행한다. 조건 안에서는 errexit가 꺼지므로
# 요청 실패가 재시도되도록 명령을 파이프나 &&로 묶는다.
eventually() {
  local attempts=$1
  shift
  for ((; attempts > 0; attempts--)); do "$@" && return 0; sleep 1; done
  return 1
}

cleanup() {
  local result=$?
  trap - EXIT
  docker rm --force "$sender" "$receiver" > /dev/null 2>&1 || true
  docker network rm "$project_name" > /dev/null 2>&1 || true
  rm -rf "$scratch"
  exit "$result"
}
trap cleanup EXIT
for required in docker jq; do command -v "$required" >/dev/null; done

# 이미지 고정값은 Dependabot이 갱신하는 Compose 정의에서 읽어 운영 스모크와 같은 버전을 검증한다.
# 보간 없는 `docker compose config`는 Compose 버전에 따라 `${VAR:?}` 볼륨을 해석하지 못하므로 image 줄을 직접 읽는다.
compose_image() {
  local image
  image=$(awk -v service="  $2:" '$0 == service { found = 1; next }
    found && /^  [^ ]/ { exit }
    found && $1 == "image:" { print $2; exit }' "$1")
  [[ "$image" =~ ^[^[:space:]]+@sha256:[0-9a-f]{64}$ ]] || {
    echo "$1의 $2 이미지 고정값을 읽지 못했습니다: '${image:-<없음>}'" >&2
    return 1
  }
  printf '%s\n' "$image"
}
alertmanager_image=$(compose_image "$project_directory/compose.operations.yml" alertmanager)
receiver_image=$(compose_image "$project_directory/compose.operations-smoke.yml" alert-receiver)

# 현재 조합의 설정 파일과 Secret 파일을 연결해 Alertmanager 도구를 외부 통신 없이 실행한다.
amtool() { docker run --rm --network none --entrypoint amtool "${config_volumes[@]}" "$alertmanager_image" "$@" > /dev/null; }

# 실제 메시지를 보내지 않도록 외부 통신이 차단된 검증 네트워크를 사용한다.
docker network create --internal "$project_name" > /dev/null
receiver_url=http://127.0.0.1:8080
sender_url=http://alert-sender:9093
printf '%s\n' http://alert-receiver:8080/healthchecks/smoke-secret-marker > "$scratch/healthchecks-url"

# 현재 조합의 전역 $channel·$configuration을 사용한다.
message_seen() {
  request "$receiver_url/alerts" \
    | jq -e --arg channel "$channel" --arg title "$1" \
      'any(.[]; .channel == $channel and .title == $title and (.text | contains("HTTPS 연결 확인")))' > /dev/null
}
wait_message() {
  eventually 40 message_seen "$1" || { echo "$configuration 알림 전달 실패: $1" >&2; return 1; }
}
endpoints_ready() {
  request "$receiver_url/alerts" > /dev/null 2>&1 && request "$sender_url/-/ready" > /dev/null 2>&1
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
  amtool check-config /config.yml
  amtool config routes test --config.file=/config.yml --verify.receivers="$watchdog_receiver" alertname=CalWatchdog
  amtool config routes test --config.file=/config.yml --verify.receivers=operations alertname=CalTlsFailed
  if [[ "$channel" == alertmanager ]]; then continue; fi
  # 조합마다 새 수신기를 사용해 이전 수신 기록이 검증에 섞이지 않게 한다.
  docker run --detach --name "$receiver" --network "$project_name" --network-alias alert-receiver \
    --env ALERT_TEST_FAILURES=429,503 \
    --volume "$project_directory/scripts/fixtures/alert-receiver.py:/app/receiver.py:ro" \
    --volume "$scratch:/data:ro" \
    "$receiver_image" \
    python /app/receiver.py > /dev/null
  docker run --detach --name "$sender" --network "$project_name" --network-alias alert-sender \
    "${config_volumes[@]}" \
    "$alertmanager_image" --config.file=/config.yml --cluster.listen-address= > /dev/null
  eventually 30 endpoints_ready || {
    echo "$configuration 알림 송신기 또는 수신기가 준비되지 않았습니다." >&2
    exit 1
  }
  for state in firing resolved; do
    jq -n --arg state "$state" '[("CalTlsFailed", "CalWatchdog") | {
      labels: {alertname: .},
      annotations: {summary: "HTTPS 연결 확인", description: "스모크 테스트"},
      startsAt: (now - 60 | todate),
      endsAt: (if $state == "firing" then now + 300 else now end | todate)}]' > "$scratch/alert.json"
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
