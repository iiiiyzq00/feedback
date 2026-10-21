#!/usr/bin/env bash

# 解析本地 OAuth 回调要用的浏览器侧地址。调用方可以
# 在需要 DNS、隧道或其他外部可达地址时，显式固定
# APP_PUBLIC_BASE_URL。
detect_local_ipv4() {
  if [[ -n "${FEEDBACK_PUBLIC_HOST:-}" ]]; then
    printf '%s\n' "$FEEDBACK_PUBLIC_HOST"
    return 0
  fi

  local interface="" address=""
  if command -v route >/dev/null 2>&1; then
    interface="$(route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}')"
  fi
  if [[ -n "$interface" ]] && command -v ipconfig >/dev/null 2>&1; then
    address="$(ipconfig getifaddr "$interface" 2>/dev/null || true)"
  fi
  if [[ -z "$address" ]] && command -v ifconfig >/dev/null 2>&1; then
    address="$(ifconfig 2>/dev/null | awk '
      /^[[:alnum:]][[:alnum:]_.-]*:/{interface=$1; sub(":$", "", interface)}
      /inet / && $2 != "127.0.0.1" && $2 !~ /^169\.254\./ {
        if (interface ~ /^en[0-9]+$/) { print $2; exit }
        if (fallback == "") fallback=$2
      }
      END { if (fallback != "") print fallback }
    ' | head -n 1)"
  fi

  [[ -n "$address" ]] || {
    printf 'ERROR: unable to detect a local IPv4 address; set APP_PUBLIC_BASE_URL or FEEDBACK_PUBLIC_HOST.\n' >&2
    return 1
  }
  printf '%s\n' "$address"
}

local_public_base_url() {
  local port="${1:-8088}"
  if [[ -n "${APP_PUBLIC_BASE_URL:-}" ]]; then
    printf '%s\n' "${APP_PUBLIC_BASE_URL%/}"
  else
    local scheme="${FEEDBACK_PUBLIC_SCHEME:-https}"
    [[ "$scheme" == "https" || "$scheme" == "http" ]] || {
      printf 'ERROR: FEEDBACK_PUBLIC_SCHEME must be https or http.\n' >&2
      return 1
    }
    printf '%s://%s:%s\n' "$scheme" "$(detect_local_ipv4)" "$port"
  fi
}
