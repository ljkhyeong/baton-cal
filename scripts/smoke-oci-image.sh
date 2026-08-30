#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
  echo "사용법: $0 <로컬 OCI 이미지 이름>" >&2
  exit 2
fi

image_name=$1
script_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_directory=$(cd "$script_directory/.." && pwd)
compose_file="$project_directory/compose.smoke.yml"
project_name="baton-cal-smoke-$(date +%s)-$$"
readiness_file=$(mktemp "${TMPDIR:-/tmp}/baton-cal-readiness.XXXXXX")
response_header_file=$(mktemp "${TMPDIR:-/tmp}/baton-cal-response-headers.XXXXXX")
response_body_file=$(mktemp "${TMPDIR:-/tmp}/baton-cal-response-body.XXXXXX")
feed_file=$(mktemp "${TMPDIR:-/tmp}/baton-cal-feed.XXXXXX")
dump_file=$(mktemp "${TMPDIR:-/tmp}/baton-cal-backup.XXXXXX")
database_name=baton_cal_smoke
database_user=baton_cal_smoke
internal_token=smoke-only-internal-token-00000000000000000000000000000000
generation_a=40000000-0000-0000-0000-000000000001
generation_b=40000000-0000-0000-0000-000000000002
source_item_id=b8ca471a-b228-42fa-8d41-28f05ee90d40
export BATON_CAL_IMAGE="$image_name"
export BATON_CAL_INTERNAL_TOKEN="$internal_token"
export BATON_CAL_SUBSCRIPTION_GENERATION="$generation_a"
export BATON_CAL_RECOVERY_MODE=false

compose=(
  docker compose
  --ansi never
  --project-name "$project_name"
  --file "$compose_file"
)
http_request=(curl --silent --show-error --connect-timeout 2 --max-time 60)

fail() {
  echo "오류: $*" >&2
  return 1
}

cleanup() {
  local status=$?
  trap - EXIT

  if ((status != 0)); then
    echo "스모크 검증에 실패했습니다. 격리 환경의 상태와 로그를 출력합니다." >&2
    "${compose[@]}" ps --all >&2 || true
    "${compose[@]}" logs --no-color >&2 || true
  fi

  rm -f \
    "$readiness_file" \
    "$response_header_file" \
    "$response_body_file" \
    "$feed_file" \
    "$dump_file"
  echo "격리된 Compose project '$project_name'의 컨테이너와 볼륨을 정리합니다."
  if ! "${compose[@]}" down --volumes --remove-orphans; then
    echo "오류: 격리된 스모크 자원을 완전히 정리하지 못했습니다." >&2
    if ((status == 0)); then
      status=1
    fi
  fi

  exit "$status"
}

trap cleanup EXIT

for required_command in docker curl jq awk grep; do
  command -v "$required_command" >/dev/null 2>&1 \
    || fail "필수 명령 '$required_command'을 찾을 수 없습니다."
done
docker compose version >/dev/null

wait_for_readiness() {
  container_id=$("${compose[@]}" ps --all --quiet app)
  [[ -n "$container_id" ]] || fail "애플리케이션 컨테이너 ID를 찾을 수 없습니다."

  local application_address
  application_address=$("${compose[@]}" port app 8080)
  application_port=${application_address##*:}
  [[ "$application_port" =~ ^[0-9]+$ ]] \
    || fail "동적으로 할당된 애플리케이션 포트를 확인할 수 없습니다: '$application_address'"
  base_url="http://127.0.0.1:$application_port"

  local management_address
  management_address=$("${compose[@]}" port app 8081)
  management_port=${management_address##*:}
  [[ "$management_port" =~ ^[0-9]+$ ]] \
    || fail "동적으로 할당된 관리 포트를 확인할 수 없습니다: '$management_address'"
  management_url="http://127.0.0.1:$management_port"
  readiness_url="$management_url/actuator/health/readiness"

  local readiness_status=000
  local ready=false
  local running
  for ((attempt = 1; attempt <= 60; attempt++)); do
    readiness_status=$(
      curl --silent \
        --output "$readiness_file" \
        --write-out '%{http_code}' \
        --connect-timeout 1 \
        --max-time 2 \
        "$readiness_url" || true
    )

    if [[ "$readiness_status" == 200 ]] \
      && jq --exit-status '.status == "UP"' "$readiness_file" >/dev/null 2>&1; then
      ready=true
      break
    fi

    running=$(docker inspect --format '{{.State.Running}}' "$container_id" 2>/dev/null || true)
    [[ "$running" == true ]] || break
    sleep 2
  done

  if [[ "$ready" != true ]]; then
    echo "마지막 readiness HTTP 상태: $readiness_status" >&2
    if [[ -s "$readiness_file" ]]; then
      echo "마지막 readiness 응답:" >&2
      sed -n '1,40p' "$readiness_file" >&2
    fi
    fail "readiness가 제한 시간 안에 HTTP 200과 UP을 반환하지 않았습니다."
  fi
  echo "readiness HTTP 200/UP 확인: $readiness_url"
}

assert_prometheus_metrics() {
  local status
  status=$(
    "${http_request[@]}" \
      --output "$readiness_file" \
      --write-out '%{http_code}' \
      "$management_url/actuator/prometheus"
  )
  [[ "$status" == 200 ]] || fail "Prometheus 메트릭이 HTTP 200이 아닌 $status를 반환했습니다."
  grep --quiet '^jvm_info' "$readiness_file" \
    || fail "Prometheus 메트릭에서 JVM 런타임 정보를 찾을 수 없습니다."
  echo "Prometheus 메트릭 HTTP 200과 JVM 런타임 정보를 확인했습니다."
}

database_scalar() {
  "${compose[@]}" exec --no-TTY postgres \
    psql \
      --set=ON_ERROR_STOP=1 \
      --username="$database_user" \
      --dbname="$database_name" \
      --tuples-only \
      --no-align \
      --command="$1"
}

assert_flyway_versions() {
  local successful_versions
  successful_versions=$(database_scalar \
    "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success IS TRUE;")
  [[ "$successful_versions" == "1,2,3,4,5,6" ]] \
    || fail "성공한 Flyway 버전이 정확히 1,2,3,4,5,6이 아닙니다: '${successful_versions:-<비어 있음>}'"
  echo "Flyway 성공 버전 확인: $successful_versions"
}

post_snapshot() {
  local fixture_name=$1
  local response
  if ! response=$(
    "${http_request[@]}" --fail \
      --header "Authorization: Bearer $internal_token" \
      --header 'Content-Type: application/json' \
      --data-binary "@$project_directory/contracts/examples/$fixture_name" \
      "$base_url/internal/api/v1/schedule-snapshots"
  ); then
    fail "일정 스냅샷 '$fixture_name' 수신 요청에 실패했습니다."
  fi
  printf '%s' "$response" \
    | jq --exit-status '.result == "APPLIED"' >/dev/null \
    || fail "일정 스냅샷 '$fixture_name'이 APPLIED로 처리되지 않았습니다."
  echo "일정 스냅샷 APPLIED 확인: $fixture_name"
}

assert_recovery_blocked() {
  local status
  status=$(
    "${http_request[@]}" \
      --request POST \
      --header "Authorization: Bearer $internal_token" \
      --output "$response_body_file" \
      --write-out '%{http_code}' \
      "$@"
  )
  [[ "$status" == 503 ]] || fail "복구 중 구독 발급이 HTTP 503이 아닌 $status를 반환했습니다."
  jq --exit-status '.code == "RECOVERY_IN_PROGRESS"' "$response_body_file" >/dev/null \
    || fail "복구 중 구독 발급 차단 오류 코드가 올바르지 않습니다."
}

assert_public_ok() {
  local token=$1
  local status
  status=$(
    "${http_request[@]}" \
      --output /dev/null \
      --write-out '%{http_code}' \
      "$base_url/calendars/v1/$token.ics"
  )
  [[ "$status" == 200 ]] || fail "현재 세대 공개 피드가 HTTP 200이 아닌 $status를 반환했습니다."
}

assert_public_not_found() {
  local token=$1
  local status
  status=$(
    "${http_request[@]}" \
      --dump-header "$response_header_file" \
      --output "$response_body_file" \
      --write-out '%{http_code}' \
      "$base_url/calendars/v1/$token.ics"
  )
  [[ "$status" == 404 ]] || fail "이전 세대 공개 피드가 HTTP 404가 아닌 $status를 반환했습니다."
  [[ ! -s "$response_body_file" ]] || fail "이전 세대 공개 피드의 404 응답 본문이 비어 있지 않습니다."
  if grep --ignore-case --quiet '^content-type:' "$response_header_file"; then
    fail "이전 세대 공개 피드의 404 응답에 Content-Type이 포함되었습니다."
  fi
}

configured_user=$(docker image inspect --format '{{.Config.User}}' "$image_name")
configured_principal=${configured_user%%:*}
case "$configured_principal" in
  "" | 0 | root)
    fail "이미지 Config.User가 비루트 사용자를 지정하지 않았습니다: '${configured_user:-<비어 있음>}'"
    ;;
esac
echo "이미지 Config.User 비루트 확인: $configured_user"

java_version_output=$(
  docker run --rm \
    --env BPL_JAVA_NMT_ENABLED=false \
    --entrypoint /cnb/lifecycle/launcher \
    "$image_name" \
    -- java -version 2>&1
)
printf '%s\n' "$java_version_output"
java_version=$(
  printf '%s\n' "$java_version_output" \
    | awk -F'"' '/^(openjdk|java) version "/ { print $2; exit }'
)
case "$java_version" in
  25 | 25.* | 25-*)
    echo "Java 25 런타임 확인: $java_version"
    ;;
  *)
    fail "이미지의 Java 런타임이 25가 아닙니다: '${java_version:-확인 불가}'"
    ;;
esac

echo "격리된 Compose project '$project_name'에서 애플리케이션을 시작합니다."
"${compose[@]}" up --detach
wait_for_readiness
assert_prometheus_metrics
assert_flyway_versions

container_pid1=$(docker inspect --format '{{.State.Pid}}' "$container_id")
pid1_uid=$(
  docker top "$container_id" -eo pid,uid,comm \
    | awk -v expected_pid="$container_pid1" '$1 == expected_pid { print $2; exit }'
)
[[ "$pid1_uid" =~ ^[0-9]+$ ]] \
  || fail "실행 중인 컨테이너 PID 1의 UID를 확인할 수 없습니다."
((pid1_uid != 0)) || fail "실행 중인 컨테이너 PID 1이 root UID 0입니다."
echo "실행 중인 컨테이너 PID 1 비루트 확인: UID $pid1_uid"

post_snapshot schedule-snapshot.zoned-active-r0.json

if ! initial_credential=$(
  "${http_request[@]}" --fail \
    --header "Authorization: Bearer $internal_token" \
    --header 'Content-Type: application/json' \
    --data-binary "@$project_directory/contracts/examples/subscription-create.json" \
    "$base_url/internal/api/v1/subscriptions"
); then
  fail "초기 캘린더 구독 생성에 실패했습니다."
fi
subscription_id=$(printf '%s' "$initial_credential" | jq --exit-status --raw-output '.subscriptionId')
token_t1=$(printf '%s' "$initial_credential" | jq --exit-status --raw-output '.token')
initial_credential=
assert_public_ok "$token_t1"
echo "세대 A 구독과 공개 피드 HTTP 200을 확인했습니다."

container_id_before_restart=$container_id
echo "같은 세대 A 설정으로 애플리케이션 컨테이너를 강제 재생성합니다."
"${compose[@]}" up --detach --force-recreate app
wait_for_readiness
[[ "$container_id" != "$container_id_before_restart" ]] \
  || fail "같은 세대 재시작 검증에서 애플리케이션 컨테이너가 교체되지 않았습니다."
assert_public_ok "$token_t1"
echo "같은 세대 A 재시작 뒤 기존 공개 피드 HTTP 200 유지를 확인했습니다."

echo "PostgreSQL 사용자 지정 형식 논리 백업을 생성하고 아카이브를 검증합니다."
"${compose[@]}" exec --no-TTY postgres \
  pg_dump -Fc --username="$database_user" --dbname="$database_name" >"$dump_file"
[[ -s "$dump_file" ]] || fail "PostgreSQL 논리 백업 아카이브가 비어 있습니다."
"${compose[@]}" exec --no-TTY postgres pg_restore --list <"$dump_file" >/dev/null
echo "pg_dump -Fc 아카이브 생성과 pg_restore 목록 검증을 완료했습니다."

post_snapshot schedule-snapshot.zoned-active-r2.json
post_snapshot schedule-snapshot.zoned-cancelled.json
latest_state=$(database_scalar \
  "SELECT revision || ':' || status FROM calendar_item WHERE source_item_id = '$source_item_id'::uuid;")
[[ "$latest_state" == "3:CANCELLED" ]] \
  || fail "백업 이후 원본 DB가 최신 취소 상태가 아닙니다: '${latest_state:-<비어 있음>}'"
echo "백업 이후 원본 DB의 revision 3 CANCELLED 상태를 확인했습니다."

echo "복원 전에 애플리케이션을 중지하고 세대 B와 복구 모드를 설정합니다."
"${compose[@]}" stop app
export BATON_CAL_SUBSCRIPTION_GENERATION="$generation_b"
export BATON_CAL_RECOVERY_MODE=true

echo "pg_restore --clean --create --exit-on-error로 논리 백업을 실제 복원합니다."
"${compose[@]}" exec --no-TTY postgres \
  pg_restore \
    --clean \
    --create \
    --exit-on-error \
    --username="$database_user" \
    --dbname=postgres <"$dump_file"

echo "세대 B 설정으로 애플리케이션을 강제 재생성합니다."
"${compose[@]}" up --detach --force-recreate app
wait_for_readiness
assert_flyway_versions

restored_item_state=$(database_scalar \
  "SELECT revision || ':' || status FROM calendar_item WHERE source_item_id = '$source_item_id'::uuid;")
[[ "$restored_item_state" == "0:ACTIVE" ]] \
  || fail "복원된 일정이 백업 시점의 revision 0 ACTIVE 상태가 아닙니다: '${restored_item_state:-<비어 있음>}'"
restored_subscription_state=$(database_scalar \
  "SELECT credential_generation || ':' || status FROM calendar_subscription WHERE id = '$subscription_id'::uuid;")
[[ "$restored_subscription_state" == "$generation_a:ACTIVE" ]] \
  || fail "복원된 구독이 세대 A의 ACTIVE 상태가 아닙니다: '${restored_subscription_state:-<비어 있음>}'"
assert_public_not_found "$token_t1"
echo "복원 직후 세대 A 구독과 토큰의 본문 없는 일반 404를 확인했습니다."
assert_recovery_blocked \
  --header 'Content-Type: application/json' \
  --data-binary "@$project_directory/contracts/examples/subscription-create.json" \
  "$base_url/internal/api/v1/subscriptions"
assert_recovery_blocked "$base_url/internal/api/v1/subscriptions/$subscription_id/rotate"
echo "복구 모드에서 구독 생성과 회전의 HTTP 503 차단을 확인했습니다."

post_snapshot schedule-snapshot.zoned-active-r2.json
post_snapshot schedule-snapshot.zoned-cancelled.json
echo "최신 ACTIVE 개정과 CANCELLED 스냅샷 재전달을 완료했습니다."
assert_recovery_blocked "$base_url/internal/api/v1/subscriptions/$subscription_id/rotate"
echo "스냅샷 재전달 뒤에도 복구 모드가 자동 해제되지 않는지 확인했습니다."

echo "대표 픽스처 복구를 마친 뒤 세대 B를 유지하고 복구 모드만 해제합니다."
export BATON_CAL_RECOVERY_MODE=false
"${compose[@]}" up --detach --force-recreate app
wait_for_readiness
assert_public_not_found "$token_t1"

if ! rotated_credential=$(
  "${http_request[@]}" --fail \
    --request POST \
    --header "Authorization: Bearer $internal_token" \
    "$base_url/internal/api/v1/subscriptions/$subscription_id/rotate"
); then
  fail "복원된 구독을 현재 세대로 회전하지 못했습니다."
fi
token_t2=$(printf '%s' "$rotated_credential" | jq --exit-status --raw-output '.token')
rotated_credential=

final_feed_status=$(
  "${http_request[@]}" \
    --output "$feed_file" \
    --write-out '%{http_code}' \
    "$base_url/calendars/v1/$token_t2.ics"
)
[[ "$final_feed_status" == 200 ]] \
  || fail "현재 세대로 회전한 공개 피드가 HTTP 200이 아닌 $final_feed_status를 반환했습니다."
awk '{ sub(/\r$/, ""); if ($0 == "SEQUENCE:3") sequence = 1; if ($0 == "STATUS:CANCELLED") cancelled = 1 } END { exit !(sequence && cancelled) }' \
  "$feed_file" \
  || fail "회전한 공개 피드에 SEQUENCE:3과 STATUS:CANCELLED가 모두 없습니다."
echo "동일 구독의 세대 B 전환과 새 피드의 SEQUENCE 3, CANCELLED 상태를 확인했습니다."

running_before_stop=$(docker inspect --format '{{.State.Running}}' "$container_id")
[[ "$running_before_stop" == true ]] \
  || fail "종료 검증 전에 애플리케이션 컨테이너가 실행 상태를 벗어났습니다."
echo "Compose의 35초 종료 유예 설정으로 애플리케이션을 중지합니다."
"${compose[@]}" stop app

container_status=$(docker inspect --format '{{.State.Status}}' "$container_id")
[[ "$container_status" == exited ]] \
  || fail "애플리케이션 컨테이너가 종료 상태가 아닙니다: $container_status"

oom_killed=$(docker inspect --format '{{.State.OOMKilled}}' "$container_id")
[[ "$oom_killed" == false ]] \
  || fail "애플리케이션 컨테이너가 OOM으로 종료되었습니다."

exit_code=$(docker inspect --format '{{.State.ExitCode}}' "$container_id")
case "$exit_code" in
  0 | 143) ;;
  *) fail "애플리케이션 컨테이너가 정상 종료 코드 0 또는 143이 아닌 $exit_code로 끝났습니다." ;;
esac
echo "SIGTERM 정상 종료 경로 확인: Status=$container_status, OOMKilled=$oom_killed, ExitCode=$exit_code"
echo "OCI 이미지와 PostgreSQL 논리 백업/복원 스모크 검증을 완료했습니다: $image_name"
