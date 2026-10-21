#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
source_dir="$project_dir/.runtime/xxl-job-1.9.1-src"
admin_dir="$source_dir/xxl-job-admin"
runtime_dir="${XXL_ADMIN_RUNTIME_DIR:-/tmp/feedback-platform-xxl-job-admin}"
pid_file="$runtime_dir/admin.pid"
log_file="$runtime_dir/admin.log"
admin_port="${XXL_ADMIN_PORT:-18081}"
context_path="/xxl-job-admin"
health_url="http://127.0.0.1:${admin_port}${context_path}/toLogin"
launch_label="com.feedback-platform.xxl-job-admin"
launch_domain="gui/$(id -u)"
launch_plist="$project_dir/tools/${launch_label}.plist"
database_name="feedback_platform_local_deploy"
database_user="feedback_alert_runtime"

mkdir -p "$runtime_dir"
chmod 700 "$runtime_dir"

secret() {
  security find-generic-password -a "$USER" -s "feedback-platform-$1" -w
}

database_env() {
  export XXL_JOB_DB_URL="jdbc:mysql://127.0.0.1:3306/${database_name}?useUnicode=true&characterEncoding=UTF-8&useSSL=false"
  export XXL_JOB_DB_USER="$database_user"
  export XXL_JOB_DB_PASSWORD="$(secret DB_PASSWORD)"
}

initialize_database() {
  database_env
  local table_count
  table_count="$(MYSQL_PWD="$XXL_JOB_DB_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -P3306 \
    -u"$XXL_JOB_DB_USER" -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${database_name}' AND table_name LIKE 'XXL_JOB%';")"
  if [[ "$table_count" == "0" ]]; then
    tail -n +3 "$source_dir/doc/db/tables_xxl_job.sql" | \
      MYSQL_PWD="$XXL_JOB_DB_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -P3306 \
        -u"$XXL_JOB_DB_USER" "$database_name"
  fi
}

pid_is_alive() {
  [[ -s "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null
}

service_is_loaded() {
  launchctl print "${launch_domain}/${launch_label}" >/dev/null 2>&1
}

run_admin() {
  database_env
  # XXL-Job 1.9.1 用的 Spring 4/CGLIB 在 Java 17 上需要开放这个模块。
  export MAVEN_OPTS="${MAVEN_OPTS:-} --add-opens=java.base/java.lang=ALL-UNNAMED"
  printf '%s\n' "$$" >"$pid_file"
  chmod 600 "$pid_file"
  cd "$admin_dir"
  exec mvn -DskipTests \
    org.eclipse.jetty:jetty-maven-plugin:9.4.57.v20241219:run-war \
    -Djetty.http.port="$admin_port" \
    -Djetty.context.path="$context_path"
}

start() {
  if service_is_loaded && curl -fsS "$health_url" >/dev/null 2>&1; then
    printf 'XXL-Job Admin already running (pid=%s).\n' "$(cat "$pid_file")"
    return
  fi
  if service_is_loaded; then
    printf 'ERROR: XXL-Job Admin service is loaded but unhealthy; run restart.\n' >&2
    exit 1
  fi
  if lsof -tiTCP:"$admin_port" -sTCP:LISTEN >/dev/null 2>&1; then
    printf 'ERROR: port %s is already occupied.\n' "$admin_port" >&2
    exit 1
  fi
  initialize_database
  : >"$log_file"
  chmod 600 "$log_file"
  rm -f "$pid_file"
  launchctl bootstrap "$launch_domain" "$launch_plist"
  launchctl kickstart "${launch_domain}/${launch_label}"
  for _ in {1..120}; do
    if curl -fsS "$health_url" >/dev/null 2>&1; then
      printf 'XXL-Job Admin started (pid=%s, url=http://127.0.0.1:%s%s).\n' \
        "$(cat "$pid_file")" "$admin_port" "$context_path"
      return
    fi
    if ! service_is_loaded; then
      printf 'ERROR: XXL-Job Admin exited; inspect %s\n' "$log_file" >&2
      exit 1
    fi
    sleep 1
  done
  printf 'ERROR: XXL-Job Admin startup timed out; inspect %s\n' "$log_file" >&2
  exit 1
}

stop() {
  if ! service_is_loaded; then
    rm -f "$pid_file"
    printf 'XXL-Job Admin is not running.\n'
    return
  fi
  launchctl bootout "${launch_domain}/${launch_label}"
  for _ in {1..30}; do
    if ! service_is_loaded; then
      rm -f "$pid_file"
      printf 'XXL-Job Admin stopped.\n'
      return
    fi
    sleep 1
  done
  printf 'ERROR: XXL-Job Admin service did not stop.\n' >&2
  exit 1
}

status() {
  if service_is_loaded && pid_is_alive && curl -fsS "$health_url" >/dev/null 2>&1; then
    printf 'RUNNING pid=%s url=http://127.0.0.1:%s%s\n' "$(cat "$pid_file")" "$admin_port" "$context_path"
  else
    printf 'STOPPED\n'
    exit 1
  fi
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  logs) tail -n "${LINES:-100}" "$log_file" ;;
  run) run_admin ;;
  *) printf 'Usage: %s {start|stop|restart|status|logs}\n' "$0" >&2; exit 2 ;;
esac
