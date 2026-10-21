#!/usr/bin/env bash
set -euo pipefail

EXPECTED_MODELS="${EXPECTED_MODELS:-XT11A,DT15PA}"
SERIAL="${ANDROID_SERIAL:-}"
BACKEND_PORT="${BACKEND_PORT:-18080}"
PACKAGE="${FEEDBACK_PACKAGE:-com.feedback.demo}"
APK_PATH="${FEEDBACK_APK_PATH:-}"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
command -v adb >/dev/null 2>&1 || fail "未找到 adb；请安装 Android SDK Platform-Tools"

if [[ -z "$SERIAL" ]]; then
  mapfile_output="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
  device_count="$(printf '%s\n' "$mapfile_output" | awk 'NF {n++} END {print n+0}')"
  [[ "$device_count" == "1" ]] || fail "请只连接一台设备，或设置 ANDROID_SERIAL；当前在线设备数=$device_count"
  SERIAL="$mapfile_output"
fi

adb -s "$SERIAL" get-state >/dev/null || fail "设备 $SERIAL 不在线或尚未授权 USB 调试"
model="$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
manufacturer="$(adb -s "$SERIAL" shell getprop ro.product.manufacturer | tr -d '\r')"
release="$(adb -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')"
sdk="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
fingerprint="$(adb -s "$SERIAL" shell getprop ro.build.fingerprint | tr -d '\r')"

model_matched=false
IFS=',' read -r -a accepted_models <<< "$EXPECTED_MODELS"
for accepted_model in "${accepted_models[@]}"; do
  [[ "${model^^}" == *"${accepted_model^^}"* ]] && model_matched=true
done
[[ "$model_matched" == true ]] || fail "型号不匹配：期望包含 $EXPECTED_MODELS 之一，实际为 $manufacturer/$model"

printf '设备: %s/%s Android %s API %s\n' "$manufacturer" "$model" "$release" "$sdk"
printf 'ROM: %s\n' "$fingerprint"

curl -fsS "http://127.0.0.1:${BACKEND_PORT}/actuator/health" >/dev/null || \
  fail "本机后端未就绪：http://127.0.0.1:${BACKEND_PORT}/actuator/health"

adb -s "$SERIAL" reverse "tcp:${BACKEND_PORT}" "tcp:${BACKEND_PORT}"

if [[ -z "$APK_PATH" ]]; then
  ./gradlew :demo:assembleDebug -PSDK_SERVER_URL="http://127.0.0.1:${BACKEND_PORT}"
  APK_PATH="demo/build/outputs/apk/debug/demo-debug.apk"
fi
[[ -f "$APK_PATH" ]] || fail "APK 不存在：$APK_PATH"
adb -s "$SERIAL" install -r "$APK_PATH"
adb -s "$SERIAL" shell am force-stop "$PACKAGE"
adb -s "$SERIAL" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null

printf '\nXT11A 已连接并启动 Demo。请在设备上完成截图、录屏、断网重试、锁屏/Doze 和五场景上报。\n'
printf '实时日志: adb -s %q logcat --pid=$(adb -s %q shell pidof %s | tr -d "\\r")\n' "$SERIAL" "$SERIAL" "$PACKAGE"
printf '取消端口映射: adb -s %q reverse --remove tcp:%s\n' "$SERIAL" "$BACKEND_PORT"
