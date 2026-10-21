#!/usr/bin/env bash
set -euo pipefail

channel="${1:?usage: generate_table_mock.sh JIRA|QUALITY|REDBLACK [count] [seed]}"
count="${2:-100}"
seed="${3:-20260804}"

exec ./gradlew :backend:bootRun --args="--task=generate-table-mock --channel=${channel} --count=${count} --seed=${seed}"
