#!/bin/bash
# Runs one typed command on the phone (debug APK only) and prints its steps.
# usage: scripts/goal.sh "<command>" [wait-seconds]
# env: ADB (default: adb), SERIAL (default: the only device)
ADB=${ADB:-adb}; [ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
export MSYS_NO_PATHCONV=1
G=$(printf '%s' "$1" | base64 -w0)
$ADB logcat -c
$ADB shell am start -n dev.localphone.agent/.VoiceActivity --es goal_b64 "$G" >/dev/null
for _ in $(seq 1 "${2:-90}"); do
  sleep 1
  if $ADB logcat -d -s AgentStep | grep -q "RESULT"; then break; fi
done
$ADB logcat -d -s AgentStep AgentTools | grep -v "^-" | cut -c19-
$ADB shell dumpsys window | grep mCurrentFocus
