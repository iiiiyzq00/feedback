#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
runtime_dir="${BACKEND_RUNTIME_DIR:-/tmp/feedback-platform-local}"
pid_file="$runtime_dir/backend.pid"
log_file="$runtime_dir/backend.log"
server_port="${SERVER_PORT:-18080}"
start_timeout_seconds="${BACKEND_START_TIMEOUT_SECONDS:-180}"
health_url="http://127.0.0.1:${server_port}/actuator/health"
launch_label="com.feedback-platform.backend"
launch_domain="gui/$(id -u)"
launch_plist="$project_dir/tools/${launch_label}.plist"

mkdir -p "$runtime_dir"
chmod 700 "$runtime_dir"

pid_is_alive() {
  [[ -s "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null
}

service_is_loaded() {
  launchctl print "${launch_domain}/${launch_label}" >/dev/null 2>&1
}

start() {
  if pid_is_alive; then
    printf 'Backend already running (pid=%s).\n' "$(cat "$pid_file")"
    exit 0
  fi
  rm -f "$pid_file"
  if lsof -tiTCP:"$server_port" -sTCP:LISTEN >/dev/null 2>&1; then
    printf 'ERROR: port %s is occupied by an unmanaged process.\n' "$server_port" >&2
    exit 1
  fi
  : >"$log_file"
  chmod 600 "$log_file"
  launchctl bootstrap "$launch_domain" "$launch_plist"
  launchctl kickstart "${launch_domain}/${launch_label}"
  for ((attempt = 0; attempt < start_timeout_seconds; attempt++)); do
    # Gradle 单次守护进程可能先结束启动器 shell，再由 Java 子进程开始监听；以真实健康端点为准。
    if curl -fs "$health_url" >/dev/null 2>&1; then
      listener_pid="$(lsof -tiTCP:"$server_port" -sTCP:LISTEN | head -n 1)"
      if [[ -n "$listener_pid" ]]; then
        printf '%s\n' "$listener_pid" > "$pid_file"
      fi
      printf 'Backend started (pid=%s, port=%s).\n' "$(cat "$pid_file")" "$server_port"
      return
    fi
    sleep 1
  done
  stop
  printf 'ERROR: backend health check timed out after %s seconds; inspect %s\n' "$start_timeout_seconds" "$log_file" >&2
  exit 1
}

stop() {
  if service_is_loaded; then
    launchctl bootout "${launch_domain}/${launch_label}"
    for _ in {1..30}; do
      if ! service_is_loaded; then
        rm -f "$pid_file"
        printf 'Backend stopped.\n'
        return
      fi
      sleep 1
    done
    printf 'ERROR: backend service did not stop within 30 seconds.\n' >&2
    exit 1
  fi
  if ! pid_is_alive; then
    rm -f "$pid_file"
    printf 'Backend is not running.\n'
    return
  fi
  backend_pid="$(cat "$pid_file")"
  kill "$backend_pid"
  for _ in {1..30}; do
    if ! kill -0 "$backend_pid" 2>/dev/null; then
      rm -f "$pid_file"
      printf 'Backend stopped.\n'
      return
    fi
    sleep 1
  done
  printf 'ERROR: backend pid %s did not stop within 30 seconds.\n' "$backend_pid" >&2
  exit 1
}

status() {
  if pid_is_alive && curl -fsS "$health_url" >/dev/null; then
    printf 'RUNNING pid=%s port=%s health=UP\n' "$(cat "$pid_file")" "$server_port"
  elif pid_is_alive; then
    printf 'STARTING_OR_UNHEALTHY pid=%s port=%s\n' "$(cat "$pid_file")" "$server_port"
    exit 1
  else
    printf 'STOPPED\n'
    exit 1
  fi
}

smoke() {
  curl -fsS "$health_url" >/dev/null
  smoke_target="${2:-all}"
  case "$smoke_target" in
    course) smoke_keys=(course) ;;
    app-store) smoke_keys=(app-store) ;;
    health-demo) smoke_keys=(health-demo) ;;
    child-art-demo) smoke_keys=(child-art-demo) ;;
    all) smoke_keys=(course app-store) ;;
    *) printf 'Unknown smoke target: %s\n' "$smoke_target" >&2; exit 2 ;;
  esac
  body='{"events":[{"client_feedback_id":null,"failure_stage":"STARTUP_SMOKE","error_type":"HMAC_CONFIG_CHECK","network_type":"LOCAL","retry_count":0,"attachment_size_bytes":null,"sdk_version":"local-smoke","app_version":null,"duration_ms":0}]}'
  for app_key in "${smoke_keys[@]}"; do
    service_name="$(printf '%s' "$app_key" | tr '[:lower:]-' '[:upper:]_')"
    sdk_secret="$(security find-generic-password -a "$USER" -s "feedback-platform-${service_name}-SDK_SECRET" -w 2>/dev/null || security find-generic-password -a "$USER" -s feedback-platform-SDK_SECRET -w)"
    timestamp="$(($(date +%s) * 1000))"
    nonce="$(openssl rand -hex 16)"
    body_hash="$(printf '%s' "$body" | shasum -a 256 | awk '{print $1}')"
    export SDK_SMOKE_SECRET="$sdk_secret" SDK_SMOKE_CONTENT="POST/api/v1/sdk/telemetry${timestamp}${nonce}${body_hash}"
    signature="$(python3 -c 'import hashlib,hmac,os; print(hmac.new(os.environ["SDK_SMOKE_SECRET"].encode(), os.environ["SDK_SMOKE_CONTENT"].encode(), hashlib.sha256).hexdigest())')"
    unset SDK_SMOKE_SECRET SDK_SMOKE_CONTENT sdk_secret
    curl -fsS -X POST "http://127.0.0.1:${server_port}/api/v1/sdk/telemetry" \
      -H 'Content-Type: application/json' \
      -H "X-App-Key: $app_key" \
      -H "X-Timestamp: $timestamp" \
      -H "X-Nonce: $nonce" \
      -H "X-Signature: $signature" \
      --data-binary "$body" >/dev/null
    printf 'SDK HMAC smoke passed: %s\n' "$app_key"
  done
  printf 'Health and requested SDK HMAC smoke checks passed.\n'
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  smoke) smoke "$@" ;;
  logs) tail -n "${LINES:-100}" "$log_file" ;;
  *) printf 'Usage: %s {start|stop|restart|status|smoke|logs}\n' "$0" >&2; exit 2 ;;
esac
