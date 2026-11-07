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

dismiss_anr() {
  # A system "app isn't responding" dialog (in practice the launcher ANR-ing
  # after the dark-mode switch on the emulator) would otherwise sit on top of
  # every screenshot. If one is present, tap its "Wait" button.
  adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || return 0
  adb pull /sdcard/window_dump.xml window_dump.xml >/dev/null 2>&1 || return 0
  grep -q "isn't responding" window_dump.xml || return 0
  local node bounds nums
  node=$(grep -o 'text="Wait"[^>]*' window_dump.xml | head -1)
  bounds=$(echo "$node" | grep -o 'bounds="\[[0-9,]*\]\[[0-9,]*\]"' | head -1)
  nums=$(echo "$bounds" | grep -o '[0-9]\+')
  set -- $nums
  if [ $# -eq 4 ]; then
    local cx=$(( ($1 + $3) / 2 )) cy=$(( ($2 + $4) / 2 ))
    echo "ANR dialog detected; tapping 'Wait' at $cx,$cy"
    adb shell input tap "$cx" "$cy" || true
    sleep 2
  fi
}

screencap() { # $1 = output png name
  dismiss_anr
  adb exec-out screencap -p > "$1" 2>/dev/null || adb shell screencap -p "/sdcard/$1" && adb pull "/sdcard/$1" "$1" >/dev/null 2>&1
  if [ -s "$1" ]; then echo "screenshot saved: $1 ($(stat -c%s "$1") bytes)"; else echo "WARNING: screenshot $1 empty/missing"; fi
}

adb root || true
sleep 2
adb install -r "$APK" || note_fail "adb install failed"
# Pre-grant the notification permission: MainActivity requests it on first
# launch (API 33+) and the system dialog would otherwise sit on top of
# MainActivity and trip the top-activity check below. This mirrors a user
# tapping "Allow" once.
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
# Moriya's phone runs in dark mode and the app theme is DayNight, so capture
# the screenshots in dark mode to match what he actually sees.
adb shell cmd uimode night yes || true
sleep 10
adb logcat -c || true

# --- MainActivity -----------------------------------------------------------
adb shell am start -n $PKG/.MainActivity
sleep 8
PID=$(adb shell pidof $PKG | tr -d '\r\n')
echo "PID after MainActivity: '$PID'"
[ -n "$PID" ] || note_fail "app process not alive after MainActivity launch"
check_top MainActivity "MainActivity launch"
screencap screenshot-main.png

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
screencap screenshot-player.png

# --- QueueActivity ----------------------------------------------------------
adb shell am start -n $PKG/.QueueActivity
sleep 4
check_top QueueActivity "QueueActivity launch"
screencap screenshot-queue.png

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
