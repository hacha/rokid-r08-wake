#!/bin/sh
# Push r08waked to the Rokid glasses and (re)launch it detached.
# Run once after each glasses reboot (the watcher survives adb disconnect and
# autonomous sleep, but not a reboot).
set -e
cd "$(dirname "$0")"

BIN=./r08waked
[ -f "$BIN" ] || { echo "binary missing; run ./build.sh first"; exit 1; }

GL=$(adb devices | awk 'NR>1 && $2=="device"{print $1}' | while read -r s; do
  [ "$(adb -s "$s" shell getprop ro.product.model | tr -d '\r')" = RG-glasses ] && echo "$s"
done)
[ -n "$GL" ] || { echo "RG-glasses not found via adb"; exit 1; }
echo "glasses: $GL"

adb -s "$GL" push "$BIN" /data/local/tmp/r08waked
adb -s "$GL" shell chmod 755 /data/local/tmp/r08waked
adb -s "$GL" shell 'pkill r08waked 2>/dev/null; setsid /data/local/tmp/r08waked >/data/local/tmp/r08waked.log 2>&1 </dev/null &'
sleep 1

echo "--- log ---"
adb -s "$GL" shell 'cat /data/local/tmp/r08waked.log'
echo "--- proc ---"
adb -s "$GL" shell 'ps -A | grep r08waked | grep -v grep' || echo "(NOT running!)"
