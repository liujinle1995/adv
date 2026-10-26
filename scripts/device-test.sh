#!/usr/bin/env bash
# 设备连通后一键：装机 -> 启动 -> 收诊断日志
# 用法：bash scripts/device-test.sh [虚拟应用包名]
set -u
export MSYS_NO_PATHCONV=1

ADB="/c/Users/94591/AppData/Local/Android/Sdk/platform-tools/adb.exe"
# 注意：adb.exe 是 Windows 程序，主机侧路径必须用盘符写法，不能用 /d/...
APK="D:/app/adv/app/build/outputs/apk/release/app-release.apk"
PKG="com.adv.runner"
TARGET="${1:-com.phoenix.read}"
OUT="D:/app/adv/logs/device"
mkdir -p "$OUT"

echo "== 等待设备 =="
for i in $(seq 1 60); do
  if "$ADB" devices 2>/dev/null | grep -qE '\bdevice$'; then break; fi
  sleep 2
done
"$ADB" devices -l
if ! "$ADB" devices 2>/dev/null | grep -qE '\bdevice$'; then
  echo "!! 没有可用设备，退出"; exit 2
fi

DEV=$("$ADB" devices | grep -E '\bdevice$' | head -1 | awk '{print $1}')
echo "DEV=$DEV"
echo "== 设备信息 =="
"$ADB" -s "$DEV" shell getprop ro.build.version.sdk
"$ADB" -s "$DEV" shell getprop ro.product.cpu.abi

echo "== 安装 =="
"$ADB" -s "$DEV" install -r -d "$APK" 2>&1 | tail -3

echo "== 停掉旧进程 =="
"$ADB" -s "$DEV" shell am force-stop "$PKG"
"$ADB" -s "$DEV" shell am force-stop "$TARGET" 2>/dev/null
"$ADB" -s "$DEV" shell rm -rf "/sdcard/Android/data/$PKG/files/logs" 2>/dev/null

echo "== 启动调试 ($TARGET) =="
"$ADB" -s "$DEV" shell am start -n "$PKG/.MainActivity" --es debug_launch "$TARGET" 2>&1 | tail -3
sleep 15

echo "== 进程状态 =="
"$ADB" -s "$DEV" shell ps -A 2>/dev/null | grep -E "$PKG|${TARGET}" || echo "(无匹配进程)"

echo "== 拉取引擎日志 =="
"$ADB" -s "$DEV" pull "/sdcard/Android/data/$PKG/files/logs" "$OUT/" 2>&1 | tail -3
if [ -d "$OUT/logs" ]; then
  echo "---- 最后 80 行 ----"
  tail -80 "$OUT"/logs/*.log 2>/dev/null
fi

echo "== 崩溃记录 =="
"$ADB" -s "$DEV" shell dumpsys dropbox --print data_app_crash 2>/dev/null | tail -120
