#!/usr/bin/env bash
# Verify AndroidX locale storage on a dedicated API 28–32 emulator with both debug APKs installed.
set -euo pipefail
adb="${ADB:-adb}"
serial="${1:?Pass the dedicated test emulator serial}"
api=$("$adb" -s "$serial" shell getprop ro.build.version.sdk)
[[ "$api" -ge 28 && "$api" -le 32 ]] || { echo "Requires Android API 28–32" >&2; exit 1; }
for language in fr en; do
  output=$("$adb" -s "$serial" shell am instrument -w -e persistLanguage "$language" \
    -e class 'com.android.sample.MainActivityTest#frenchAndEnglishSurviveActivityRecreation' \
    com.android.sample.test/androidx.test.runner.AndroidJUnitRunner)
  printf '%s\n' "$output"
  [[ "$output" == *"OK (1 test)"* ]]
  "$adb" -s "$serial" shell am force-stop com.android.sample
  "$adb" -s "$serial" shell am start -W -n com.android.sample/.MainActivity >/dev/null
  expected='Welcome back.'
  [[ "$language" != fr ]] || expected='Heureux de vous revoir.'
  for attempt in 1 2 3 4 5; do
    "$adb" -s "$serial" shell uiautomator dump /sdcard/locale-restart.xml >/dev/null
    xml=$("$adb" -s "$serial" exec-out cat /sdcard/locale-restart.xml)
    [[ "$xml" != *"text=\"$expected\""* ]] || break
  done
  [[ "$xml" == *"text=\"$expected\""* ]]
  printf 'PASS: %s retained after force-stop/relaunch on API %s\n' "$language" "$api"
done
