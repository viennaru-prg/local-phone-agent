#!/bin/bash
# Runs commands one after another on the phone and prints one line each: result, model calls, seconds.
# "HOME" between commands presses the home key. Same env as goal.sh.
# usage: scripts/suite.sh HOME "회사까지 얼마나 걸려" HOME "지금 몇 시야"
DIR=$(cd "$(dirname "$0")" && pwd)
ADB=${ADB:-adb}; [ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
for g in "$@"; do
  if [ "$g" = "HOME" ]; then $ADB shell input keyevent KEYCODE_HOME; sleep 3; continue; fi
  t0=$(date +%s)
  out=$(bash "$DIR/goal.sh" "$g" 120)
  t1=$(date +%s)
  m=$(echo "$out" | grep -c "\[model")
  r=$(echo "$out" | grep -o "RESULT.*" | tail -1)
  echo "$g | ${r:-NO RESULT} | 모델 ${m}회 | 약 $((t1-t0))초"
  sleep 2
done
