#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
action="${1:?PRECHECK, BUILD, VERIFY or ABANDON_STALE required}"
generation_key="${2:?generation key required}"

action="$(printf '%s' "$action" | tr '[:lower:]' '[:upper:]')"
[[ "$action" == "PRECHECK" || "$action" == "BUILD" || "$action" == "VERIFY" || "$action" == "ABANDON_STALE" ]] || {
  printf 'ERROR: action must be PRECHECK, BUILD, VERIFY or ABANDON_STALE\n' >&2
  exit 1
}
[[ "$generation_key" =~ ^[a-z0-9][a-z0-9._-]{2,99}$ ]] || {
  printf 'ERROR: invalid generation key\n' >&2
  exit 1
}
if [[ "$action" == "VERIFY" && "$generation_key" == "shadow-11-full-20260908-2310" ]]; then
  printf 'ERROR: VERIFY is permanently forbidden for this generation\n' >&2
  exit 1
fi
if [[ "$action" == "ABANDON_STALE" && "$generation_key" != "shadow-11-full-20260908-2310" ]]; then
  printf 'ERROR: ABANDON_STALE is restricted to the approved stale generation\n' >&2
  exit 1
fi

export DB_USERNAME="feedback_alert_runtime"
export DB_PASSWORD="$(security find-generic-password -a "$USER" -s feedback-platform-DB_PASSWORD -w)"
export DB_URL="${DB_URL:-jdbc:mysql://127.0.0.1:3306/feedback_platform_local_deploy?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai}"
export BGE_M3_PATH="${BGE_M3_PATH:-/Users/yang/.cache/modelscope/models/BAAI--bge-m3/snapshots/master}"
[[ -s "$BGE_M3_PATH/onnx/model.onnx" && -s "$BGE_M3_PATH/onnx/model.onnx_data" && -s "$BGE_M3_PATH/tokenizer.json" ]] || {
  printf 'ERROR: complete local BGE-M3 ONNX model is unavailable\n' >&2
  exit 1
}

actor_id="$(MYSQL_PWD="$DB_PASSWORD" /opt/homebrew/bin/mysql --batch --skip-column-names \
  -u "$DB_USERNAME" feedback_platform_local_deploy -e \
  "SELECT DISTINCT u.id FROM t_auth_user u JOIN t_auth_user_role r ON r.user_id=u.id WHERE u.is_active=1 AND r.role_code='ADMIN' AND (r.expires_at IS NULL OR r.expires_at>NOW()) ORDER BY u.id LIMIT 2")"
[[ "$actor_id" =~ ^[1-9][0-9]*$ ]] || {
  printf 'ERROR: active ADMIN actor is not unique\n' >&2
  exit 1
}

cd "$project_dir"
exec ./gradlew :backend:clusterShadowGeneration --args="--spring.flyway.enabled=false --logging.level.root=ERROR --logging.level.com.feedback.mapper=OFF --logging.level.org.mybatis=OFF --logging.level.org.springframework.jdbc=OFF --mybatis.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl --app.cluster.shadow-generation.confirmation=ISOLATED_SHADOW_CANDIDATE_ONLY --app.cluster.shadow-generation.action=$action --app.cluster.shadow-generation.generation-key=$generation_key --app.cluster.shadow-generation.actor-id=$actor_id"
