#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
manifest="${XXL_JOB_TASK_MANIFEST:-$project_dir/config/xxl-job-tasks.conf}"
admin_base="${XXL_JOB_ADMIN_ADDRESSES:-http://127.0.0.1:18081/xxl-job-admin}"
admin_user="${XXL_JOB_ADMIN_USER:-admin}"
admin_password="${XXL_JOB_ADMIN_PASSWORD:-123456}"
database_name="feedback_platform_local_deploy"
database_user="feedback_alert_runtime"
cookie_file="$(mktemp /tmp/feedback-platform-xxl-cookie.XXXXXX)"
trap 'rm -f "$cookie_file"' EXIT

secret() {
  security find-generic-password -a "$USER" -s "feedback-platform-$1" -w
}

db_password="$(secret DB_PASSWORD)"
group_id="$(MYSQL_PWD="$db_password" mysql --protocol=TCP -h127.0.0.1 -P3306 \
  -u"$database_user" -Nse "SELECT id FROM ${database_name}.XXL_JOB_QRTZ_TRIGGER_GROUP WHERE app_name='feedback-platform' LIMIT 1;")"
if [[ -z "$group_id" ]]; then
  printf 'ERROR: feedback-platform executor group does not exist.\n' >&2
  exit 1
fi

login_response="$(curl -fsS -c "$cookie_file" -X POST "$admin_base/login" \
  --data-urlencode "userName=$admin_user" \
  --data-urlencode "password=$admin_password" \
  --data-urlencode "ifRemember=on")"
if [[ "$login_response" != *'"code":200'* ]]; then
  printf 'ERROR: XXL-Job Admin login failed.\n' >&2
  exit 1
fi

created=0
updated=0
while IFS='|' read -r handler cron description route block failure; do
  [[ -z "$handler" || "$handler" == \#* ]] && continue
  existing_id="$(MYSQL_PWD="$db_password" mysql --protocol=TCP -h127.0.0.1 -P3306 \
    -u"$database_user" -Nse "SELECT id FROM ${database_name}.XXL_JOB_QRTZ_TRIGGER_INFO WHERE job_group=${group_id} AND executor_handler='${handler}' LIMIT 1;")"
  endpoint="jobinfo/add"
  if [[ -n "$existing_id" ]]; then
    endpoint="jobinfo/update"
  fi
  response="$(curl -fsS -b "$cookie_file" -X POST "$admin_base/$endpoint" \
    --data-urlencode "id=${existing_id:-0}" \
    --data-urlencode "jobGroup=$group_id" \
    --data-urlencode "jobCron=$cron" \
    --data-urlencode "jobDesc=$description" \
    --data-urlencode "author=feedback-platform" \
    --data-urlencode "alarmEmail=" \
    --data-urlencode "executorRouteStrategy=$route" \
    --data-urlencode "executorHandler=$handler" \
    --data-urlencode "executorParam=" \
    --data-urlencode "executorBlockStrategy=$block" \
    --data-urlencode "executorFailStrategy=$failure" \
    --data-urlencode "glueType=BEAN" \
    --data-urlencode "glueSource=" \
    --data-urlencode "glueRemark=GLUE代码初始化" \
    --data-urlencode "childJobId=")"
  if [[ "$response" != *'"code":200'* ]]; then
    printf 'ERROR: failed to sync %s: %s\n' "$handler" "$response" >&2
    exit 1
  fi
  if [[ -n "$existing_id" ]]; then
    updated=$((updated + 1))
  else
    created=$((created + 1))
  fi
done <"$manifest"
unset db_password admin_password

printf 'XXL-Job tasks synchronized: created=%s updated=%s group=%s.\n' "$created" "$updated" "$group_id"
