#!/bin/zsh
set -euo pipefail

keychain_account="${USER}"
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
source "$project_dir/tools/local-public-url.sh"

keychain_secret() {
  security find-generic-password -a "$keychain_account" -s "feedback-platform-$1" -w
}

export STATION_DUAL_WRITE_ENABLED="true"
export STATION_DUAL_WRITE_STORAGE_SECRET="$(keychain_secret STATION_DUAL_WRITE_STORAGE_SECRET)"
export STATION_DUAL_WRITE_STORAGE_KEY_ID="station-dual-write-local-v1"
export STATION_OLD_FBP_TOKEN_URL="https://itapis.cvte.com/iac/app/access_token"
export STATION_OLD_FBP_SUBMIT_BASE_URL="https://fbp.cvte.com"
export STATION_OLD_FBP_APP_ID="$(keychain_secret STATION_OLD_FBP_APP_ID)"
export STATION_OLD_FBP_SECRET="$(keychain_secret STATION_OLD_FBP_SECRET)"
export STATION_OLD_FBP_TOKEN_CACHE_SECONDS="300"
export STATION_OLD_FBP_CONNECT_TIMEOUT_SECONDS="2"
export STATION_OLD_FBP_CALL_TIMEOUT_SECONDS="8"
export STATION_DUAL_WRITE_WORKER_DELAY_MS="1000"
export FEEDBACK_RPC_ENABLED="true"
export FEEDBACK_RPC_PORT="20880"
export FEEDBACK_RPC_REGISTRY_ADDRESS="${FEEDBACK_RPC_REGISTRY_ADDRESS:-zookeeper://127.0.0.1:2181}"
export LLM_API_KEY="$(keychain_secret LLM_API_KEY)"

# 受治理的附件流水线：ClamAV -> 内容闸门 -> 加密存储 ->
# 一次性、限定用途的安全流。密钥始终留在 macOS Keychain。
export ATTACHMENT_SECURITY_SCAN_ENABLED="true"
export ATTACHMENT_CLAMAV_HOST="127.0.0.1"
export ATTACHMENT_CLAMAV_PORT="3310"
export ATTACHMENT_SECURITY_SCAN_TIMEOUT_MS="30000"
export ATTACHMENT_STORAGE_ENCRYPTION_SECRET="$(keychain_secret ATTACHMENT_STORAGE_ENCRYPTION_SECRET)"
export ATTACHMENT_SECURE_STREAM_ENABLED="true"

# 本地飞书 OAuth 测试配置。App Secret 留在 Keychain；
# 浏览器侧 IP 跟随当前网络，除非显式覆盖。
# 生产必须使用稳定的公网 URL，并关闭首个用户自举。
export FEISHU_SSO_ENABLED="true"
export FEISHU_APP_ID="cli_aa0cea4135799cbc"
export FEISHU_APP_SECRET="$(keychain_secret FEISHU_APP_SECRET)"
export APP_PUBLIC_BASE_URL="$(local_public_base_url "${FRONTEND_PUBLIC_PORT:-8088}")"
export FEISHU_REDIRECT_URI="${FEISHU_REDIRECT_URI:-${APP_PUBLIC_BASE_URL}/api/v1/auth/feishu/callback}"
export FEISHU_BOOTSTRAP_FIRST_USER_ADMIN="true"
export FEISHU_ORGANIZATION_SYNC_ENABLED="false"

# 已部署的 JAR 只有在异步脱敏
# Worker 能解开数据密钥时才接受 SDK 信封。轮换期间要同时保留历史与
# 在用密钥 ID；公钥由 SDK 配置接口暴露。
export SDK_TRANSPORT_PRIVATE_KEY_PATH="${SDK_TRANSPORT_PRIVATE_KEY_PATH:-$HOME/.config/feedback-platform/sdk-transport-private-v2.pem}"
export SDK_TRANSPORT_PRIVATE_KEY_PATHS="${SDK_TRANSPORT_PRIVATE_KEY_PATHS:-sdk-transport-v1=$HOME/.config/feedback-platform/sdk-transport-private.pem,sdk-transport-v2=$HOME/.config/feedback-platform/sdk-transport-private-v2.pem}"
export SDK_TRANSPORT_ACTIVE_KEY_ID="${SDK_TRANSPORT_ACTIVE_KEY_ID:-sdk-transport-v2}"
export SDK_TRANSPORT_PUBLIC_KEY_PATH="${SDK_TRANSPORT_PUBLIC_KEY_PATH:-$HOME/.config/feedback-platform/sdk-transport-public-v2.pem}"

exec "$HOME/.config/feedback-platform/run-local-deploy.sh"
