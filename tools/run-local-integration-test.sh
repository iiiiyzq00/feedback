#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mysql_url="${D9_MYSQL_URL:-jdbc:mysql://127.0.0.1:3306/feedback_platform_test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai}"
mysql_user="${D9_MYSQL_USERNAME:-root}"
mysql_password="${D9_MYSQL_PASSWORD:-}"
redis_host="${D9_REDIS_HOST:-127.0.0.1}"
redis_port="${D9_REDIS_PORT:-6379}"
test_filter="${1:-}"

database_name="${mysql_url%%\?*}"
database_name="${database_name##*/}"
if [[ "${database_name}" != "feedback_platform_test" ]]; then
  echo "REFUSING_LOCAL_INTEGRATION_DATABASE=${database_name}" >&2
  echo "Only feedback_platform_test is allowed; business databases must never be cleaned by integration tests." >&2
  exit 2
fi

args=(":backend:integrationTest" "-Dd9.external-infra=true"
  "-Dd9.mysql.url=${mysql_url}" "-Dd9.mysql.username=${mysql_user}" "-Dd9.mysql.password=${mysql_password}"
  "-Dd9.redis.host=${redis_host}" "-Dd9.redis.port=${redis_port}" "--console=plain")
if [[ -n "${test_filter}" ]]; then
  args+=("--tests" "${test_filter}")
fi

exec "${project_dir}/gradlew" -p "${project_dir}" "${args[@]}"
