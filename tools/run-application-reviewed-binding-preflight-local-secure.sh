#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
input_path="${1:?input JSON path required}"
output_path="${2:?output JSON path required}"

[[ -f "$input_path" ]] || { printf 'ERROR: input file not found\n' >&2; exit 1; }
[[ "$(stat -f '%Lp' "$input_path")" == "600" ]] || {
  printf 'ERROR: input file must have mode 600\n' >&2
  exit 1
}
[[ ! -e "$output_path" ]] || { printf 'ERROR: output file already exists\n' >&2; exit 1; }

export DB_USERNAME="feedback_alert_runtime"
export DB_PASSWORD="$(security find-generic-password -a "$USER" -s feedback-platform-DB_PASSWORD -w)"
export DB_URL="${DB_URL:-jdbc:mysql://127.0.0.1:3306/feedback_platform_local_deploy?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai}"

cd "$project_dir"
exec ./gradlew :backend:applicationReviewedBindingPreflight --args="--spring.flyway.enabled=false --spring.datasource.hikari.read-only=true --logging.level.root=ERROR --logging.level.com.feedback.mapper=OFF --logging.level.org.mybatis=OFF --logging.level.org.springframework.jdbc=OFF --mybatis.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl --app.application-identity.reviewed-binding-preflight.confirmation=READ_ONLY_APPLICATION_BINDING_PREFLIGHT --app.application-identity.reviewed-binding-preflight.input-path=$input_path --app.application-identity.reviewed-binding-preflight.output-path=$output_path"
