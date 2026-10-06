#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
  echo "사용법: $0 <로컬 OCI 이미지 이름>" >&2
  exit 2
fi

image_name=$1
project_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
project_name="baton-cal-smoke-$(date +%s)-$$"
scratch=$(mktemp -d "${TMPDIR:-/tmp}/baton-cal-smoke.XXXXXX")
internal_token=smoke-only-internal-token-00000000000000000000000000000000
generation_a=40000000-0000-0000-0000-000000000001
generation_b=40000000-0000-0000-0000-000000000002
source_item_id=b8ca471a-b228-42fa-8d41-28f05ee90d40
season_id=f5316f93-d49e-4230-b1d0-9e9c2d079819
# 각 실행은 빈 격리 DB에서 시작하므로 이 ID의 과거 완료 기록이 없다.
recovery_id=92490d0d-b82e-4f94-a041-308b184aaef9
export BATON_CAL_IMAGE="$image_name"
export BATON_CAL_INTERNAL_TOKEN="$internal_token"
export BATON_CAL_SUBSCRIPTION_GENERATION="$generation_a"
export BATON_CAL_RECOVERY_MODE=false
unset BATON_CAL_PREVIOUS_INTERNAL_TOKEN
export DATABASE_USERNAME=baton_cal_smoke DATABASE_PASSWORD=baton-cal-smoke-only
# 운영 Compose의 app과 의존 서비스 postgres만 띄운다. 내부 포트는 127.0.0.1의 임시 포트에 연결한다.
export CAL_INTERNAL_PORT=0
# 기동하지 않는 gateway·alertmanager·blackbox의 필수 보간 값이다. 경로의 파일은 만들지 않는다.
export CAL_TLS_DIRECTORY="$scratch/tls" CAL_ACME_DIRECTORY="$scratch/acme"
export CAL_ALERT_WEBHOOK_URL_FILE="$scratch/webhook-url"
database_name=baton_cal
database_user=$DATABASE_USERNAME

compose=(
  docker compose
  --ansi never
  --project-name "$project_name"
  --file "$project_directory/compose.operations.yml"
  --file "$project_directory/compose.operations-smoke.yml"
)
http_request=(curl --silent --show-error --connect-timeout 2 --max-time 60)
bearer=(--oauth2-bearer "$internal_token")
examples="$project_directory/contracts/examples"

fail() {
  echo "오류: $*" >&2
  return 1
}

# 응답 본문은 첫 인자의 파일에 저장하고 HTTP 상태 코드만 출력한다.
http_status() {
  local output=$1
  shift
  "${http_request[@]}" --output "$output" --write-out '%{http_code}' "$@"
}

cleanup() {
  local status=$?
  trap - EXIT

  if ((status != 0)); then
    echo "스모크 검증에 실패했습니다. 격리 환경의 상태와 로그를 출력합니다." >&2
    "${compose[@]}" ps --all >&2 || true
    "${compose[@]}" logs --no-color >&2 || true
  fi

  echo "격리된 Compose project '$project_name'의 컨테이너와 볼륨을 정리합니다."
  if ! "${compose[@]}" down --volumes --remove-orphans; then
    echo "오류: 격리된 스모크 자원을 완전히 정리하지 못했습니다." >&2
    if ((status == 0)); then
      status=1
    fi
  fi
  # Compose가 보간하는 임시 경로를 정리 명령 뒤에 지운다.
  rm -rf "$scratch"

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

  # 강제 재생성하면 포트가 바뀌므로 매번 다시 조회한다. 두 포트 모두 127.0.0.1의 임시 포트에만 연결한다.
  base_url="http://$("${compose[@]}" port app 8080)"
  management_url="http://$("${compose[@]}" port app 8081)"
  readiness_url="$management_url/actuator/health/readiness"

  local readiness_status=000
  local ready=false
  local running
  for ((attempt = 1; attempt <= 60; attempt++)); do
    readiness_status=$(
      curl --silent \
        --output "$scratch/readiness" \
        --write-out '%{http_code}' \
        --connect-timeout 1 \
        --max-time 2 \
        "$readiness_url" || true
    )

    if [[ "$readiness_status" == 200 ]] \
      && jq --exit-status '.status == "UP"' "$scratch/readiness" >/dev/null 2>&1; then
      ready=true
      break
    fi

    running=$(docker inspect --format '{{.State.Running}}' "$container_id" 2>/dev/null || true)
    [[ "$running" == true ]] || break
    sleep 2
  done

  if [[ "$ready" != true ]]; then
    echo "마지막 readiness HTTP 상태: $readiness_status" >&2
    if [[ -s "$scratch/readiness" ]]; then
      echo "마지막 readiness 응답:" >&2
      sed -n '1,40p' "$scratch/readiness" >&2
    fi
    fail "readiness가 제한 시간 안에 HTTP 200과 UP을 반환하지 않았습니다."
  fi
  echo "readiness HTTP 200/UP 확인: $readiness_url"
}

assert_prometheus_metrics() {
  local status
  status=$(http_status "$scratch/readiness" "$management_url/actuator/prometheus")
  [[ "$status" == 200 ]] || fail "Prometheus 메트릭이 HTTP 200이 아닌 $status를 반환했습니다."
  grep --quiet '^jvm_info' "$scratch/readiness" \
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
  # 체크아웃의 마이그레이션 파일과 이미지가 실제로 적용한 버전이 정확히 같아야 한다.
  local expected_versions successful_versions
  expected_versions=$(cd "$project_directory/src/main/resources/db/migration" && printf '%s\n' V*__*.sql \
    | sed -E 's/^V([0-9]+)__.*/\1/' | sort -n | paste -sd, -)
  successful_versions=$(database_scalar \
    "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success IS TRUE;")
  [[ "$successful_versions" == "$expected_versions" ]] \
    || fail "성공한 Flyway 버전 '${successful_versions:-<비어 있음>}'이 마이그레이션 파일 '$expected_versions'과 다릅니다."
  echo "Flyway 성공 버전 확인: $successful_versions"
}

# DB 조회 결과가 기대값과 같은지 확인한다.
assert_database_value() {
  local expected=$1 query=$2 message=$3 actual
  actual=$(database_scalar "$query")
  [[ "$actual" == "$expected" ]] || fail "$message: '${actual:-<비어 있음>}'"
}

post_snapshot() {
  local fixture_name=$1
  local response
  if ! response=$(
    "${http_request[@]}" "${bearer[@]}" --fail \
      --json "@$examples/$fixture_name" \
      "$base_url/internal/api/v1/schedule-snapshots"
  ); then
    fail "일정 스냅샷 '$fixture_name' 수신 요청에 실패했습니다."
  fi
  printf '%s' "$response" \
    | jq --exit-status '.result == "APPLIED"' >/dev/null \
    || fail "일정 스냅샷 '$fixture_name'이 APPLIED로 처리되지 않았습니다."
  echo "일정 스냅샷 APPLIED 확인: $fixture_name"
}

put_season_metadata() {
  local fixture_name=$1
  local response
  response=$(
    "${http_request[@]}" "${bearer[@]}" --fail --request PUT \
      --json "@$examples/$fixture_name" \
      "$base_url/internal/api/v1/seasons/$season_id/calendar-metadata"
  )
  printf '%s' "$response" \
    | jq --exit-status --arg season "$season_id" \
      --slurpfile expected "$examples/$fixture_name" \
      '.seasonId == $season and .revision == $expected[0].revision and .displayName == $expected[0].displayName' \
      >/dev/null || fail "시즌 표시 이름 '$fixture_name'을 채택하지 못했습니다."
  echo "시즌 표시 이름 채택 확인: $fixture_name"
}

assert_recovery_blocked() {
  local status
  status=$(http_status "$scratch/response-body" "${bearer[@]}" --request POST "$@")
  [[ "$status" == 503 ]] || fail "복구 중 구독 발급이 HTTP 503이 아닌 $status를 반환했습니다."
  jq --exit-status '.code == "RECOVERY_IN_PROGRESS"' "$scratch/response-body" >/dev/null \
    || fail "복구 중 구독 발급 차단 오류 코드가 올바르지 않습니다."
}

assert_recovery_result() {
  local path=$1
  local fixture=$2
  local expected_status=$3
  local expected_result=$4
  local status
  status=$(
    http_status "$scratch/response-body" "${bearer[@]}" --request PUT \
      --json "@$examples/$fixture" \
      "$base_url/internal/api/v1/recovery-runs/$recovery_id/$path"
  )
  [[ "$status" == "$expected_status" ]] \
    || fail "복구 $path 요청이 HTTP $expected_status 대신 $status를 반환했습니다."
  jq --exit-status --arg expected "$expected_result" \
    '(.result // .code) == $expected' "$scratch/response-body" >/dev/null \
    || fail "복구 $path 요청 결과가 $expected_result가 아닙니다."
}

assert_public_ok() {
  local token=$1
  local status
  status=$(http_status /dev/null "$base_url/calendars/v1/$token.ics")
  [[ "$status" == 200 ]] || fail "현재 세대 공개 피드가 HTTP 200이 아닌 $status를 반환했습니다."
}

assert_public_not_found() {
  local token=$1
  local status
  status=$(
    http_status "$scratch/response-body" --dump-header "$scratch/response-headers" "$base_url/calendars/v1/$token.ics"
  )
  [[ "$status" == 404 ]] || fail "이전 세대 공개 피드가 HTTP 404가 아닌 $status를 반환했습니다."
  [[ ! -s "$scratch/response-body" ]] || fail "이전 세대 공개 피드의 404 응답 본문이 비어 있지 않습니다."
  if grep --ignore-case --quiet '^content-type:' "$scratch/response-headers"; then
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
"${compose[@]}" up --detach app
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
put_season_metadata season-calendar-metadata.r0.json

if ! initial_credential=$(
  "${http_request[@]}" "${bearer[@]}" --fail \
    --json "@$examples/subscription-create.json" \
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
  pg_dump -Fc --username="$database_user" --dbname="$database_name" >"$scratch/backup.dump"
[[ -s "$scratch/backup.dump" ]] || fail "PostgreSQL 논리 백업 아카이브가 비어 있습니다."
"${compose[@]}" exec --no-TTY postgres pg_restore --list <"$scratch/backup.dump" >/dev/null
echo "pg_dump -Fc 아카이브 생성과 pg_restore 목록 검증을 완료했습니다."

post_snapshot schedule-snapshot.zoned-active-r2.json
post_snapshot schedule-snapshot.zoned-cancelled.json
put_season_metadata season-calendar-metadata.r2.json
item_state_query="SELECT revision || ':' || status FROM calendar_item WHERE source_item_id = '$source_item_id'::uuid;"
assert_database_value 3:CANCELLED "$item_state_query" "백업 이후 원본 DB가 최신 취소 상태가 아닙니다"
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
    --dbname=postgres <"$scratch/backup.dump"

echo "세대 B 설정으로 애플리케이션을 강제 재생성합니다."
"${compose[@]}" up --detach --force-recreate app
wait_for_readiness
assert_flyway_versions

assert_database_value 0:ACTIVE "$item_state_query" "복원된 일정이 백업 시점의 revision 0 ACTIVE 상태가 아닙니다"
assert_database_value 0 "SELECT revision FROM season_calendar_metadata WHERE season_id = '$season_id'::uuid;" \
  "복원된 시즌 이름이 백업 시점의 revision 0이 아닙니다"
assert_database_value "$generation_a:ACTIVE" \
  "SELECT credential_generation || ':' || status FROM calendar_subscription WHERE id = '$subscription_id'::uuid;" \
  "복원된 구독이 세대 A의 ACTIVE 상태가 아닙니다"
assert_public_not_found "$token_t1"
echo "복원 직후 세대 A 구독과 토큰의 본문 없는 일반 404를 확인했습니다."
assert_recovery_blocked \
  --json "@$examples/subscription-create.json" \
  "$base_url/internal/api/v1/subscriptions"
assert_recovery_blocked "$base_url/internal/api/v1/subscriptions/$subscription_id/rotate"
echo "복구 모드에서 구독 생성과 회전의 HTTP 503 차단을 확인했습니다."

post_snapshot schedule-snapshot.zoned-active-r2.json
assert_recovery_result "seasons/$season_id/manifest" recovery-season-manifest.zoned-cancelled.json 409 RECOVERY_MANIFEST_MISMATCH
assert_recovery_result completion recovery-run-completion.zoned-cancelled.json 409 RECOVERY_MANIFEST_MISMATCH
assert_recovery_blocked "$base_url/internal/api/v1/subscriptions/$subscription_id/rotate"
post_snapshot schedule-snapshot.zoned-cancelled.json
assert_recovery_result "seasons/$season_id/manifest" recovery-season-manifest.zoned-cancelled.json 409 RECOVERY_MANIFEST_MISMATCH
put_season_metadata season-calendar-metadata.r2.json
echo "취소와 최신 이름 누락 시 복구 검증 거부를 확인하고 최신 상태 재전달을 완료했습니다."
assert_recovery_blocked "$base_url/internal/api/v1/subscriptions/$subscription_id/rotate"
echo "스냅샷 재전달 뒤에도 복구 모드가 자동 해제되지 않는지 확인했습니다."

assert_recovery_result "seasons/$season_id/manifest" recovery-season-manifest.zoned-cancelled.json 200 VERIFIED
assert_recovery_result completion recovery-run-completion.zoned-cancelled.json 200 COMPLETED
completed_at=$(jq --exit-status --raw-output '.completedAt' "$scratch/response-body")
assert_recovery_result completion recovery-run-completion.zoned-cancelled.json 200 COMPLETED
jq --exit-status --arg expected "$completed_at" '.completedAt == $expected' "$scratch/response-body" >/dev/null \
  || fail "같은 완료 요청을 재시도했을 때 최초 완료 시각이 바뀌었습니다."
assert_recovery_blocked "$base_url/internal/api/v1/subscriptions/$subscription_id/rotate"
echo "COMPLETED 응답과 재시도 시각을 확인한 뒤 세대 B를 유지하고 복구 모드만 해제합니다."
export BATON_CAL_RECOVERY_MODE=false
"${compose[@]}" up --detach --force-recreate app
wait_for_readiness
assert_public_not_found "$token_t1"

if ! rotated_credential=$(
  "${http_request[@]}" "${bearer[@]}" --fail --request POST \
    "$base_url/internal/api/v1/subscriptions/$subscription_id/rotate"
); then
  fail "복원된 구독을 현재 세대로 회전하지 못했습니다."
fi
token_t2=$(printf '%s' "$rotated_credential" | jq --exit-status --raw-output '.token')
rotated_credential=

final_feed_status=$(http_status "$scratch/feed" "$base_url/calendars/v1/$token_t2.ics")
[[ "$final_feed_status" == 200 ]] \
  || fail "현재 세대로 회전한 공개 피드가 HTTP 200이 아닌 $final_feed_status를 반환했습니다."
awk '{ sub(/\r$/, ""); if ($0 == "SEQUENCE:3") sequence = 1; if ($0 == "STATUS:CANCELLED") cancelled = 1 } END { exit !(sequence && cancelled) }' \
  "$scratch/feed" \
  || fail "회전한 공개 피드에 SEQUENCE:3과 STATUS:CANCELLED가 모두 없습니다."
grep --quiet '^X-WR-CALNAME:BATON 가을' "$scratch/feed" \
  || fail "회전한 공개 피드에 최신 시즌 표시 이름이 없습니다."
echo "동일 구독의 세대 B 전환과 새 피드의 SEQUENCE 3, CANCELLED 상태, 최신 시즌 이름을 확인했습니다."

running_before_stop=$(docker inspect --format '{{.State.Running}}' "$container_id")
[[ "$running_before_stop" == true ]] \
  || fail "종료 검증 전에 애플리케이션 컨테이너가 실행 상태를 벗어났습니다."
echo "Compose의 35초 종료 유예 설정으로 애플리케이션을 중지합니다."
"${compose[@]}" stop app

container_state=$(docker inspect --format '{{.State.Status}} {{.State.OOMKilled}} {{.State.ExitCode}}' "$container_id")
read -r container_status oom_killed exit_code <<< "$container_state"
[[ "$container_status" == exited ]] \
  || fail "애플리케이션 컨테이너가 종료 상태가 아닙니다: $container_status"
[[ "$oom_killed" == false ]] \
  || fail "애플리케이션 컨테이너가 OOM으로 종료되었습니다."
case "$exit_code" in
  0 | 143) ;;
  *) fail "애플리케이션 컨테이너가 정상 종료 코드 0 또는 143이 아닌 $exit_code로 끝났습니다." ;;
esac
echo "SIGTERM 정상 종료 경로 확인: Status=$container_status, OOMKilled=$oom_killed, ExitCode=$exit_code"
echo "OCI 이미지와 PostgreSQL 논리 백업/복원 스모크 검증을 완료했습니다: $image_name"
