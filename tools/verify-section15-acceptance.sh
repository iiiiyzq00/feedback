#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
base="${SECTION15_BASE_URL:-http://127.0.0.1:18081}"
report="${SECTION15_REPORT:-$root/output/acceptance/section15-acceptance.md}"
work="$(mktemp -d /tmp/feedback-section15.XXXXXX)"
mkdir -p "$(dirname "$report")"

finalize(){
  local exit_code=$?
  if compgen -G "$work/*.log" >/dev/null; then cp "$work"/*.log "$(dirname "$report")/"; fi
  if [[ -f "$work/results" ]]; then
    {
      echo "# 第 15 节统一验收报告"
      echo
      echo "- 执行时间：$(date -Iseconds)"
      echo "- 后端：$base"
      echo "- 总体结论：**$([[ $exit_code -eq 0 ]] && echo PASS || echo FAIL)**"
      echo
      echo "| 验收域 | 结果 | 证据日志 |"
      echo "|---|---|---|"
      while IFS='|' read -r name status log; do echo "| $name | $status | \`$(dirname "$report")/$(basename "$log")\` |"; done <"$work/results"
    } >"$report"
  fi
  rm -rf "$work"
}
trap finalize EXIT

require_env=(D10P_APP_SECRET D10P_PRIVATE_KEY)
for name in "${require_env[@]}"; do [[ -n "${!name:-}" ]] || { echo "$name is required" >&2; exit 2; }; done
curl -fsS "$base/actuator/health" >"$work/health.json"

host_port="${base#*://}"; host_port="${host_port%%/*}"
export D10_E2E_BASE_URL="$base" TOPIC04_BASE_URL="$base" TOPIC08_BASE_URL="$base"
export D10_E2E_ALLOW_DERIVED_DEGRADED=true D10_E2E_INDEX_WAIT_LOOPS="${SECTION15_INDEX_WAIT_LOOPS:-5}"
export D10P_HOST="${host_port%%:*}" D10P_PORT="${host_port##*:}" D10P_COUNT=1 D10P_CONCURRENCY=1
export D10P_ID_PREFIX="SECTION15-SDK-$(date +%s)"

run_step(){
  local name="$1"; shift
  if "$@" >"$work/$name.log" 2>&1; then echo "$name|PASS|$work/$name.log" >>"$work/results";
  else echo "$name|FAIL|$work/$name.log" >>"$work/results"; return 1; fi
}

run_step sdk node "$root/tools/day10-sdk-load.js"
python3 - "$work/sdk.log" <<'PY'
import json,sys
r=json.loads(open(sys.argv[1]).read().splitlines()[-1]); assert r['count']==1 and int(r['codes'].get('202',0)+r['codes'].get('200',0))==1,r
PY
run_step four_channel_import "$root/tools/verify-day10-e02-e08-import-e2e.sh"
run_step import_retry_privacy "$root/tools/verify-topic04-import-privacy.sh"
run_step source_labels_analysis "$root/tools/verify-topic08-overview-drilldown-analysis.sh"
run_step zero_leakage "$root/tools/verify-section15-zero-leakage.sh"

echo "SECTION15_ACCEPTANCE=PASS report=$report"
