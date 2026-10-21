#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
source "$project_dir/tools/local-public-url.sh"

assert_eq() {
  [[ "$1" == "$2" ]] || { printf 'expected <%s>, got <%s>\n' "$1" "$2" >&2; exit 1; }
}

APP_PUBLIC_BASE_URL="https://feedback.example.test/"
assert_eq "https://feedback.example.test" "$(local_public_base_url 8088)"
unset APP_PUBLIC_BASE_URL

FEEDBACK_PUBLIC_HOST="10.23.45.67"
assert_eq "10.23.45.67" "$(detect_local_ipv4)"
assert_eq "https://10.23.45.67:9090" "$(local_public_base_url 9090)"

FEEDBACK_PUBLIC_SCHEME="http"
assert_eq "http://10.23.45.67:9090" "$(local_public_base_url 9090)"

printf 'local public URL tests passed\n'
