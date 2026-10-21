#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 4 ]] || { echo "usage: $0 <base-url> <jwt> <skill-code> <version>" >&2; exit 2; }
curl --fail-with-body -sS -X POST "${1%/}/api/v1/admin/ai-assets/skills/$3/rollback/$4" \
  -H "Authorization: Bearer $2"
