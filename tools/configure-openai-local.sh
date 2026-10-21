#!/usr/bin/env bash
set -euo pipefail

account="${USER}"
provider="${1:-aihub}"

case "$provider" in
  aihub)
    model="gpt-5.4"
    base_url="https://study.test.seewo.com/ai/transit/v1"
    allowed_hosts="study.test.seewo.com"
    ;;
  openai)
    base_url="https://api.openai.com/v1"
    allowed_hosts="api.openai.com"
    read -r -p "经评测批准的 OpenAI 模型 ID: " model
    ;;
  *)
    printf 'Usage: %s [aihub|openai]\n' "$0" >&2
    exit 2
    ;;
esac

[[ "$model" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{1,99}$ ]] || {
  printf 'ERROR: OpenAI model ID is invalid.\n' >&2
  exit 2
}
read -r -s -p "反馈平台专属 ${provider} API Key（输入不回显）: " api_key
printf '\n'
[[ ${#api_key} -ge 20 ]] || {
  printf 'ERROR: OpenAI API Key is too short.\n' >&2
  exit 2
}

security add-generic-password -U -a "$account" -s feedback-platform-OPENAI_API_KEY -w "$api_key" >/dev/null
security add-generic-password -U -a "$account" -s feedback-platform-OPENAI_MODEL -w "$model" >/dev/null
security add-generic-password -U -a "$account" -s feedback-platform-OPENAI_BASE_URL -w "$base_url" >/dev/null
security add-generic-password -U -a "$account" -s feedback-platform-OPENAI_ALLOWED_HOSTS -w "$allowed_hosts" >/dev/null
security add-generic-password -U -a "$account" -s feedback-platform-CLUSTER_AI_ASSISTED_DECISION_ENABLED -w "true" >/dev/null
unset api_key
printf 'AI provider configuration stored in macOS Keychain.\n'
printf 'provider=%s model=%s baseUrl=%s allowedHosts=%s assistedDecision=true\n' \
  "$provider" "$model" "$base_url" "$allowed_hosts"
printf 'Restart the backend with tools/manage-backend-local-secure.sh restart.\n'
