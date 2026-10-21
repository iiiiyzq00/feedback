#!/usr/bin/env bash
set -euo pipefail

count="${1:-500}"
seed="${2:-20260804}"
scenario="${3:-mixed}"

exec ./gradlew :backend:bootRun --args="--task=generate-sdk-mock --count=${count} --seed=${seed} --scenario=${scenario}"
