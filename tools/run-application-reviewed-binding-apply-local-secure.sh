#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
command_path="${1:?command JSON path required}"
expected_sha256="${2:?expected SHA-256 required}"

[[ -f "$command_path" ]] || { printf 'ERROR: command file not found\n' >&2; exit 1; }
[[ "$(stat -f '%Lp' "$command_path")" == "600" ]] || {
  printf 'ERROR: command file must have mode 600\n' >&2
  exit 1
}
[[ "$expected_sha256" == "32c17681b7d93b72874b69181770ba737034bd1fcff8bb147310f272555bcee6" ]] || {
  printf 'ERROR: command SHA-256 is not the approved package\n' >&2
  exit 1
}

export DB_USERNAME="feedback_alert_runtime"
export DB_PASSWORD="$(security find-generic-password -a "$USER" -s feedback-platform-DB_PASSWORD -w)"
export DB_URL="${DB_URL:-jdbc:mysql://127.0.0.1:3306/feedback_platform_local_deploy?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai}"
actor_id="$(MYSQL_PWD="$DB_PASSWORD" /opt/homebrew/bin/mysql --batch --skip-column-names \
  -u "$DB_USERNAME" feedback_platform_local_deploy -e \
  "SELECT DISTINCT u.id FROM t_auth_user u JOIN t_auth_user_role r ON r.user_id=u.id WHERE u.is_active=1 AND r.role_code='ADMIN' AND (r.expires_at IS NULL OR r.expires_at>NOW()) ORDER BY u.id LIMIT 2")"
[[ "$actor_id" =~ ^[1-9][0-9]*$ ]] || {
  printf 'ERROR: active ADMIN actor is not unique\n' >&2
  exit 1
}
export APP_APPLICATION_IDENTITY_REVIEWED_BINDING_APPLY_ACTOR_ID="$actor_id"

cd "$project_dir"
exec ./gradlew :backend:applicationReviewedBindingApply --args="--spring.flyway.enabled=false --logging.level.root=ERROR --logging.level.com.feedback.mapper=OFF --logging.level.org.mybatis=OFF --logging.level.org.springframework.jdbc=OFF --mybatis.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl --app.application-identity.reviewed-binding-apply.confirmation=APPLY_FOUR_APPROVED_APPLICATION_BINDINGS --app.application-identity.reviewed-binding-apply.command-path=$command_path --app.application-identity.reviewed-binding-apply.expected-sha256=$expected_sha256"
