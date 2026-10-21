#!/usr/bin/env bash
set -euo pipefail
[[ $# -ge 3 ]] || { echo "usage: $0 <base-url> <jwt> <skill-code> [days]" >&2; exit 2; }
curl --fail-with-body -sS "${1%/}/api/v1/admin/ai-assets/skills/$3/stats?days=${4:-7}" \
  -H "Authorization: Bearer $2"
