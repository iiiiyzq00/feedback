#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
runtime_dir="${BACKEND_RUNTIME_DIR:-/tmp/feedback-platform-local}"
source "$project_dir/tools/backend-instance-guard.sh"
source "$project_dir/tools/local-public-url.sh"
alert_config="${ALERT_CONFIG_FILE:-$HOME/.config/feedback-platform/alerts.env}"
jira_config="${JIRA_CONFIG_FILE:-$HOME/.config/feedback-platform/jira400.env}"
keychain_account="${USER}"

existing_instances=()
while IFS= read -r instance_pid; do [[ -n "$instance_pid" ]] && existing_instances+=("$instance_pid"); done < <(feedback_backend_instances "$project_dir")
if ((${#existing_instances[@]})); then
  printf 'ERROR: another feedback-platform backend is already connected from this project: ' >&2
  for pid in "${existing_instances[@]}"; do describe_feedback_backend_instance "$pid" >&2; printf ' ' >&2; done
  printf '\nStop it with tools/manage-backend-local-secure.sh stop or terminate the reported PID before starting another port.\n' >&2
  exit 1
fi

singleton_dir="${BACKEND_SINGLETON_DIR:-/tmp/feedback-platform-backend-singleton}"
if ! mkdir "$singleton_dir" 2>/dev/null; then
  if [[ -s "$singleton_dir/launcher.pid" ]] && ! kill -0 "$(cat "$singleton_dir/launcher.pid")" 2>/dev/null; then
    rm -f "$singleton_dir/launcher.pid"
    rmdir "$singleton_dir"
    mkdir "$singleton_dir"
  else
    printf 'ERROR: feedback-platform backend startup is already in progress (lock=%s).\n' "$singleton_dir" >&2
    exit 1
  fi
fi
printf '%s\n' "$$" >"$singleton_dir/launcher.pid"
cleanup_singleton(){ rm -f "$singleton_dir/launcher.pid"; rmdir "$singleton_dir" 2>/dev/null || true; }
trap cleanup_singleton EXIT INT TERM

secret() {
  security find-generic-password -a "$keychain_account" -s "feedback-platform-$1" -w
}

app_secret() {
  local app_name="$1"
  local legacy_name="${2:-}"
  security find-generic-password -a "$keychain_account" \
    -s "feedback-platform-${app_name}-SDK_SECRET" -w 2>/dev/null \
    || { [[ -n "$legacy_name" ]] && secret "$legacy_name"; }
}

if [[ -f "$jira_config" ]]; then
  [[ "$(stat -f '%Lp' "$jira_config")" == "600" ]] || {
    printf 'ERROR: Jira config must have mode 600: %s\n' "$jira_config" >&2
    exit 1
  }
  set -a
  source "$jira_config"
  set +a
fi

if [[ "${JIRA_400_API_ENABLED:-false}" == "true" ]]; then
  export JIRA_400_API_TOKEN="${JIRA_400_API_TOKEN:-$(secret JIRA_400_API_TOKEN)}"
fi

server_port="${SERVER_PORT:-18080}"
if lsof -tiTCP:"$server_port" -sTCP:LISTEN >/dev/null 2>&1; then
  printf 'ERROR: port %s is already in use; stop the existing backend first.\n' "$server_port" >&2
  exit 1
fi

export DB_USERNAME="feedback_alert_runtime"
export DB_PASSWORD="$(secret DB_PASSWORD)"
export DB_URL="${DB_URL:-jdbc:mysql://127.0.0.1:3306/feedback_platform_local_deploy?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai}"
export XXL_JOB_ENABLED="${XXL_JOB_ENABLED:-true}"
export XXL_JOB_ADMIN_ADDRESSES="${XXL_JOB_ADMIN_ADDRESSES:-http://127.0.0.1:18081/xxl-job-admin}"
export XXL_JOB_EXECUTOR_APPNAME="${XXL_JOB_EXECUTOR_APPNAME:-feedback-platform}"
export XXL_JOB_EXECUTOR_PORT="${XXL_JOB_EXECUTOR_PORT:--1}"
export PHASE_ONE_LIST_PROJECTION_READ_ENABLED="${PHASE_ONE_LIST_PROJECTION_READ_ENABLED:-true}"
export PHASE_ONE_PROBLEM_ANALYSIS_PROJECTION_READ_ENABLED="${PHASE_ONE_PROBLEM_ANALYSIS_PROJECTION_READ_ENABLED:-true}"
export AUTH_SESSION_COOKIE_SECURE="${AUTH_SESSION_COOKIE_SECURE:-true}"
export FEISHU_SSO_ENABLED="${FEISHU_SSO_ENABLED:-true}"
export FEISHU_APP_ID="${FEISHU_APP_ID:-cli_aa0cea4135799cbc}"
export FEISHU_APP_SECRET="${FEISHU_APP_SECRET:-$(secret FEISHU_APP_SECRET)}"
export APP_PUBLIC_BASE_URL="$(local_public_base_url "${FRONTEND_PUBLIC_PORT:-8088}")"
export FEISHU_REDIRECT_URI="${FEISHU_REDIRECT_URI:-${APP_PUBLIC_BASE_URL%/}/api/v1/auth/feishu/callback}"
printf 'Feishu OAuth callback: %s\n' "$FEISHU_REDIRECT_URI"
export FEISHU_BOOTSTRAP_FIRST_USER_ADMIN="${FEISHU_BOOTSTRAP_FIRST_USER_ADMIN:-false}"
export COURSE_SDK_SECRET="$(app_secret COURSE SDK_SECRET)"
export QUESTION_BANK_SDK_SECRET="$(app_secret QUESTION_BANK SDK_SECRET)"
export WRONG_BOOK_SDK_SECRET="$(app_secret WRONG_BOOK SDK_SECRET)"
export AI_TUTOR_SDK_SECRET="$(app_secret AI_TUTOR SDK_SECRET)"
export APP_STORE_SDK_SECRET="$(app_secret APP_STORE SDK_SECRET)"
export UNIVERSAL_FEEDBACK_SDK_SECRET="${UNIVERSAL_FEEDBACK_SDK_SECRET:-$(app_secret UNIVERSAL_FEEDBACK SDK_SECRET)}"
export HEALTH_DEMO_SDK_SECRET="${HEALTH_DEMO_SDK_SECRET:-$(app_secret HEALTH_DEMO SDK_SECRET)}"
export CHILD_ART_DEMO_SDK_SECRET="${CHILD_ART_DEMO_SDK_SECRET:-$(app_secret CHILD_ART_DEMO SDK_SECRET)}"
export MASKING_FINGERPRINT_SECRET="$(secret MASKING_FINGERPRINT_SECRET)"
export CONTACT_STORAGE_SECRET="$(secret CONTACT_STORAGE_SECRET)"
export IMPORT_RAW_STORAGE_SECRET="$(secret IMPORT_RAW_STORAGE_SECRET)"
if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  OPENAI_API_KEY="$(secret OPENAI_API_KEY 2>/dev/null || true)"
fi
export OPENAI_API_KEY
if [[ -z "${OPENAI_MODEL:-}" ]]; then
  OPENAI_MODEL="$(secret OPENAI_MODEL 2>/dev/null || true)"
fi
export OPENAI_MODEL
if [[ -z "${OPENAI_BASE_URL:-}" ]]; then
  OPENAI_BASE_URL="$(secret OPENAI_BASE_URL 2>/dev/null || true)"
fi
export OPENAI_BASE_URL="${OPENAI_BASE_URL:-https://api.openai.com/v1}"
if [[ -z "${OPENAI_ALLOWED_HOSTS:-}" ]]; then
  OPENAI_ALLOWED_HOSTS="$(secret OPENAI_ALLOWED_HOSTS 2>/dev/null || true)"
fi
export OPENAI_ALLOWED_HOSTS="${OPENAI_ALLOWED_HOSTS:-api.openai.com}"
if [[ -z "${CLUSTER_AI_ASSISTED_DECISION_ENABLED:-}" ]]; then
  CLUSTER_AI_ASSISTED_DECISION_ENABLED="$(secret CLUSTER_AI_ASSISTED_DECISION_ENABLED 2>/dev/null || true)"
fi
export CLUSTER_AI_ASSISTED_DECISION_ENABLED="${CLUSTER_AI_ASSISTED_DECISION_ENABLED:-false}"
export AI_PROMPT_ASSETS_PATH="${AI_PROMPT_ASSETS_PATH:-$project_dir/prompts}"
export SDK_TRANSPORT_KEY_ENABLED="${SDK_TRANSPORT_KEY_ENABLED:-true}"
export SDK_TRANSPORT_PRIVATE_KEY_PATH="${SDK_TRANSPORT_PRIVATE_KEY_PATH:-$HOME/.config/feedback-platform/sdk-transport-private-v2.pem}"
export SDK_TRANSPORT_PRIVATE_KEY_PATHS="${SDK_TRANSPORT_PRIVATE_KEY_PATHS:-sdk-transport-v1=$HOME/.config/feedback-platform/sdk-transport-private.pem,sdk-transport-v2=$HOME/.config/feedback-platform/sdk-transport-private-v2.pem}"
export SDK_TRANSPORT_ACTIVE_KEY_ID="${SDK_TRANSPORT_ACTIVE_KEY_ID:-sdk-transport-v2}"
export SDK_TRANSPORT_PUBLIC_KEY_PATH="${SDK_TRANSPORT_PUBLIC_KEY_PATH:-$HOME/.config/feedback-platform/sdk-transport-public-v2.pem}"
export ATTACHMENT_STORAGE_ENCRYPTION_SECRET="${ATTACHMENT_STORAGE_ENCRYPTION_SECRET:-$(secret ATTACHMENT_STORAGE_ENCRYPTION_SECRET)}"
export ATTACHMENT_DIR="${ATTACHMENT_DIR:-$project_dir/data/attachments}"
# 当前候选由旧工作区迁移而来；显式保留两个旧附件目录为只读兼容根，路径策略仍拒绝白名单外文件。
export ATTACHMENT_LEGACY_READ_ROOTS="${ATTACHMENT_LEGACY_READ_ROOTS:-$HOME/projects/feedback-platform/data/attachments,$HOME/projects/feedback-platform/backend/data/attachments}"
export ATTACHMENT_SECURITY_SCAN_ENABLED="${ATTACHMENT_SECURITY_SCAN_ENABLED:-true}"
export ATTACHMENT_CLAMAV_HOST="${ATTACHMENT_CLAMAV_HOST:-127.0.0.1}"
export ATTACHMENT_CLAMAV_PORT="${ATTACHMENT_CLAMAV_PORT:-3310}"
# 本机验收只在 ClamAV 通过后交付 Jira 图片原始字节；生产可替换为受治理的内容审核器。
export ATTACHMENT_CONTENT_REVIEW_LOCAL_IMAGE_PASS="${ATTACHMENT_CONTENT_REVIEW_LOCAL_IMAGE_PASS:-true}"
export ATTACHMENT_CONTENT_REVIEW_LOCAL_VIDEO_PASS="${ATTACHMENT_CONTENT_REVIEW_LOCAL_VIDEO_PASS:-true}"
export ATTACHMENT_CONTENT_REVIEW_LOCAL_LOG_ARCHIVE_PASS="${ATTACHMENT_CONTENT_REVIEW_LOCAL_LOG_ARCHIVE_PASS:-true}"
export ATTACHMENT_SECURITY_SCAN_TIMEOUT_MS="${ATTACHMENT_SECURITY_SCAN_TIMEOUT_MS:-30000}"
export ATTACHMENT_SECURE_STREAM_ENABLED="${ATTACHMENT_SECURE_STREAM_ENABLED:-true}"
export ATTACHMENT_SECURE_STREAM_TTL_SECONDS="${ATTACHMENT_SECURE_STREAM_TTL_SECONDS:-300}"
# ffmpeg 是按任务启动的受限子进程而非常驻服务。本机首次回填以单任务并发启动，
# 新上传和页面可见任务仍由数据库优先级抢占历史回填，避免部署库出现转码尖峰。
export ATTACHMENT_MEDIA_PREVIEW_FFMPEG_ENABLED="${ATTACHMENT_MEDIA_PREVIEW_FFMPEG_ENABLED:-true}"
export ATTACHMENT_MEDIA_PREVIEW_FFMPEG_PATH="${ATTACHMENT_MEDIA_PREVIEW_FFMPEG_PATH:-$(command -v ffmpeg || true)}"
export ATTACHMENT_MEDIA_PREVIEW_CONCURRENCY="${ATTACHMENT_MEDIA_PREVIEW_CONCURRENCY:-1}"
export ATTACHMENT_MEDIA_PREVIEW_BATCH_SIZE="${ATTACHMENT_MEDIA_PREVIEW_BATCH_SIZE:-1}"
if [[ "$ATTACHMENT_MEDIA_PREVIEW_FFMPEG_ENABLED" == "true" && ! -x "$ATTACHMENT_MEDIA_PREVIEW_FFMPEG_PATH" ]]; then
  printf 'ERROR: media preview ffmpeg is enabled but no executable was found: %s\n' "$ATTACHMENT_MEDIA_PREVIEW_FFMPEG_PATH" >&2
  exit 1
fi
if [[ "${LOCAL_MINIO_ENABLED:-true}" == "true" ]]; then
  export ATTACHMENT_OBJECT_STORAGE_ENABLED="${ATTACHMENT_OBJECT_STORAGE_ENABLED:-true}"
  export ATTACHMENT_OBJECT_STORAGE_ENDPOINT="${ATTACHMENT_OBJECT_STORAGE_ENDPOINT:-http://127.0.0.1:19000}"
  export ATTACHMENT_OBJECT_STORAGE_REGION="${ATTACHMENT_OBJECT_STORAGE_REGION:-us-east-1}"
  export ATTACHMENT_OBJECT_STORAGE_BUCKET="${ATTACHMENT_OBJECT_STORAGE_BUCKET:-feedback-private}"
  export ATTACHMENT_OBJECT_STORAGE_PATH_STYLE_ACCESS="${ATTACHMENT_OBJECT_STORAGE_PATH_STYLE_ACCESS:-true}"
  # 仅允许本机 loopback 的开发 MinIO 使用 HTTP；生产仍必须显式配置 TLS。
  export ATTACHMENT_OBJECT_STORAGE_ALLOW_INSECURE_ENDPOINT="${ATTACHMENT_OBJECT_STORAGE_ALLOW_INSECURE_ENDPOINT:-true}"
  export ATTACHMENT_OBJECT_STORAGE_ACCESS_KEY="${ATTACHMENT_OBJECT_STORAGE_ACCESS_KEY:-$(secret ATTACHMENT_OBJECT_STORAGE_ACCESS_KEY)}"
  export ATTACHMENT_OBJECT_STORAGE_SECRET_KEY="${ATTACHMENT_OBJECT_STORAGE_SECRET_KEY:-$(secret ATTACHMENT_OBJECT_STORAGE_SECRET_KEY)}"
  # 本机 MinIO 已完成私有桶、版本控制与最小权限初始化；新通过审核的附件默认直接发布到对象存储。
  export ATTACHMENT_OBJECT_STORAGE_PUBLISH_NEW="${ATTACHMENT_OBJECT_STORAGE_PUBLISH_NEW:-true}"
  export ATTACHMENT_OBJECT_STORAGE_MIGRATION_ENABLED="${ATTACHMENT_OBJECT_STORAGE_MIGRATION_ENABLED:-false}"
  export ATTACHMENT_OBJECT_STORAGE_MIGRATION_DRY_RUN="${ATTACHMENT_OBJECT_STORAGE_MIGRATION_DRY_RUN:-true}"
  export MINIO_DATA_DIR="${MINIO_DATA_DIR:-$project_dir/.runtime/minio/data}"
  export ATTACHMENT_OBJECT_STORAGE_CLEANUP_LOCAL_AFTER_PUBLISH="${ATTACHMENT_OBJECT_STORAGE_CLEANUP_LOCAL_AFTER_PUBLISH:-true}"
  export ATTACHMENT_OBJECT_STORAGE_CLEANUP_AUDIT_PATH="${ATTACHMENT_OBJECT_STORAGE_CLEANUP_AUDIT_PATH:-$project_dir/logs/attachment-object-migration-audit.jsonl}"
fi
export EMBEDDING_PROVIDER="${EMBEDDING_PROVIDER:-hashing}"
export MASKING_NER_ENABLED="${MASKING_NER_ENABLED:-true}"
export MILVUS_ENABLED="${MILVUS_ENABLED:-false}"

# Station 可靠双写保持显式开启；仅在功能启用时读取其凭据，普通本机配置不受影响。
if [[ "${STATION_DUAL_WRITE_ENABLED:-false}" == "true" ]]; then
  export STATION_DUAL_WRITE_STORAGE_SECRET="${STATION_DUAL_WRITE_STORAGE_SECRET:-$(secret STATION_DUAL_WRITE_STORAGE_SECRET)}"
  export STATION_DUAL_WRITE_STORAGE_KEY_ID="${STATION_DUAL_WRITE_STORAGE_KEY_ID:-station-dual-write-local-v1}"
  export STATION_OLD_FBP_TOKEN_URL="${STATION_OLD_FBP_TOKEN_URL:-https://itapis.cvte.com/iac/app/access_token}"
  export STATION_OLD_FBP_SUBMIT_BASE_URL="${STATION_OLD_FBP_SUBMIT_BASE_URL:-https://fbp.cvte.com}"
  export STATION_OLD_FBP_APP_ID="${STATION_OLD_FBP_APP_ID:-$(secret STATION_OLD_FBP_APP_ID)}"
  export STATION_OLD_FBP_SECRET="${STATION_OLD_FBP_SECRET:-$(secret STATION_OLD_FBP_SECRET)}"
  export FEEDBACK_RPC_ENABLED="${FEEDBACK_RPC_ENABLED:-true}"
  export FEEDBACK_RPC_PORT="${FEEDBACK_RPC_PORT:-20880}"
  export FEEDBACK_RPC_REGISTRY_ADDRESS="${FEEDBACK_RPC_REGISTRY_ADDRESS:-zookeeper://127.0.0.1:2181}"
fi

value_of() {
  awk -F= -v key="$1" '$0 ~ "^[[:space:]]*"key"[[:space:]]*=" {
    sub(/^[^=]*=/, ""); gsub(/^[[:space:]\047\"]+|[[:space:]\047\"]+$/, ""); print; exit
  }' "$alert_config"
}

alert_names=(
  OPENAI_API_KEY OPENAI_MODEL OPENAI_BASE_URL OPENAI_ALLOWED_HOSTS
  FEISHU_WEBHOOK_URL FEISHU_ALL_WEBHOOK_URL FEISHU_DM_ENDPOINT
  FEISHU_TASK_ENDPOINT FEISHU_API_TOKEN FEISHU_RECIPIENT_IDS
  ALERT_SMS_WEBHOOK_URL ALERT_SMS_RECIPIENTS
  ALERT_VOICE_CALL_WEBHOOK_URL ALERT_VOICE_CALL_TOKEN ALERT_VOICE_CALL_RECIPIENTS
  ALERT_EMAIL_PROVIDER ALERT_SMTP_HOST ALERT_SMTP_PORT
  ALERT_SMTP_SSL ALERT_SMTP_STARTTLS ALERT_SMTP_USERNAME ALERT_SMTP_AUTH_CODE
  ALERT_EMAIL_FROM ALERT_EMAIL_RECIPIENTS
)
for name in "${alert_names[@]}"; do
  value="$(value_of "$name")"
  if [[ -z "${!name:-}" && -n "$value" ]]; then export "$name=$value"; fi
done

cd "$project_dir"
./gradlew :backend:bootJar --no-daemon

# bootRun 直接把 backend/build/classes 暴露给运行中的 JVM。一次并发
# 编译可能替换掉其中某些 class 文件，让进程处于
# 新旧链接类混杂且无法恢复的状态。因此先把完整的启动
# 归档准备好再启动，后续 Gradle 操作就无法改动运行中的 classpath。
mkdir -p "$runtime_dir"
chmod 700 "$runtime_dir"
runtime_jar="$runtime_dir/backend-runtime.jar"
cp "$project_dir/backend/build/libs/feedback-platform-backend.jar" "$runtime_jar"
chmod 600 "$runtime_jar"

app_args=(
  "--server.port=${server_port}"
  "--server.address=${SERVER_ADDRESS:-0.0.0.0}"
  "--spring.flyway.validate-on-migrate=${LOCAL_FLYWAY_VALIDATE:-true}"
)
if [[ -n "${BACKEND_EXTRA_ARGS:-}" ]]; then
  read -r -a extra_args <<<"$BACKEND_EXTRA_ARGS"
  app_args+=("${extra_args[@]}")
fi

printf '%s\n' "$$" >"$runtime_dir/backend.pid"
chmod 600 "$runtime_dir/backend.pid"
exec java --add-opens=java.base/java.lang=ALL-UNNAMED \
  "-Dfeedback.project.root=$project_dir" -jar "$runtime_jar" "${app_args[@]}"
