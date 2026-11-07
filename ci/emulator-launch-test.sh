#!/usr/bin/env bash
# Emulator launch test for YT Player.
#
# Installs the debug APK, launches MainActivity, PlayerActivity and
# QueueActivity, and verifies after each step that:
#   - the app process is alive,
#   - the expected activity is the top resumed activity,
#   - PlaybackService is running while MainActivity is in the foreground
#     (MainActivity connects a MediaController in onStart, which must start it).
# Finally it fails if logcat contains a FATAL EXCEPTION for the app or the
# app's own crash handler recorded a crash (files/last-crash.txt).
#
# This script exists as a file (not inline in the workflow) because
# reactivecircus/android-emulator-runner executes an inline `script:` block
# line-by-line in separate shells, so shell state and `exit` do not work there.
set -u
cd "$(dirname "$0")/.."

APK=app/build/outputs/apk/debug/app-debug.apk
PKG=app.ytplayer
FAIL=0

note_fail() { echo "LAUNCH-TEST FAILURE: $1"; FAIL=1; }

top_activity() {
  adb shell dumpsys activity activities 2>/dev/null | grep "topResumedActivity" | head -1
}

check_top() { # $1 = activity short name, $2 = label
  local top
  top=$(top_activity)
  echo "top after $2: $top"
  if echo "$top" | grep -q "$PKG/.$1"; then
    echo "OK: $1 is top resumed activity"
  else
    note_fail "$1 is not the top resumed activity after $2"
  fi
}

adb root || true
sleep 2
adb install -r "$APK" || note_fail "adb install failed"
# Pre-grant the notification permission: MainActivity requests it on first
# launch (API 33+) and the system dialog would otherwise sit on top of
# MainActivity and trip the top-activity check below. This mirrors a user
# tapping "Allow" once.
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb logcat -c || true

# --- MainActivity -----------------------------------------------------------
adb shell am start -n $PKG/.MainActivity
sleep 8
PID=$(adb shell pidof $PKG | tr -d '\r\n')
echo "PID after MainActivity: '$PID'"
[ -n "$PID" ] || note_fail "app process not alive after MainActivity launch"
check_top MainActivity "MainActivity launch"

adb shell dumpsys activity services $PKG > dumpsys-services-main.txt 2>&1 || true
if grep -q "PlaybackService" dumpsys-services-main.txt; then
  echo "OK: PlaybackService present in dumpsys while MainActivity is foreground"
else
  note_fail "PlaybackService not present in dumpsys after MainActivity launch"
fi

# --- PlayerActivity ---------------------------------------------------------
adb shell am start -n $PKG/.PlayerActivity
sleep 4
check_top PlayerActivity "PlayerActivity launch"

# --- QueueActivity ----------------------------------------------------------
adb shell am start -n $PKG/.QueueActivity
sleep 4
check_top QueueActivity "QueueActivity launch"

PID2=$(adb shell pidof $PKG | tr -d '\r\n')
echo "PID at end: '$PID2'"
[ -n "$PID2" ] || note_fail "app process not alive at end of test"

# --- Diagnostics ------------------------------------------------------------
adb shell dumpsys activity services $PKG > dumpsys-services.txt 2>&1 || true
adb shell dumpsys activity activities > dumpsys-activities.txt 2>&1 || true
adb logcat -d -v threadtime > logcat.txt 2>&1 || true
adb shell run-as $PKG cat files/last-crash.txt > last-crash.txt 2>/dev/null \
  || echo "<no last-crash.txt>" > last-crash.txt

if grep -q "FATAL EXCEPTION" logcat.txt && grep -A3 "FATAL EXCEPTION" logcat.txt | grep -q "$PKG"; then
  note_fail "FATAL EXCEPTION for $PKG in logcat"
fi
if grep -q "uncaught crash" last-crash.txt; then
  note_fail "app crash handler recorded a crash (see last-crash.txt)"
fi

echo "LAUNCH_TEST_FAIL=$FAIL"
exit $FAIL
