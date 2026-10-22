#!/usr/bin/env bash
# Keep the emulator alive while collecting evidence, preserving the test exit code.
set -u
mkdir -p app/build/reports/device-diagnostics
adb logcat -c
adb logcat -v threadtime MyLive2D-QA:I TestRunner:I AndroidRuntime:E '*:S' > app/build/reports/device-diagnostics/test-logcat.txt &
trace_pid=$!
timeout --signal=TERM --kill-after=20s 8m ./gradlew --no-daemon --info :app:connectedDebugAndroidTest
test_status=$?
adb shell dumpsys activity activities > app/build/reports/device-diagnostics/activities.txt || true
adb shell dumpsys activity processes > app/build/reports/device-diagnostics/processes.txt || true
kill "$trace_pid" 2>/dev/null || true
wait "$trace_pid" 2>/dev/null || true
tail -n 160 app/build/reports/device-diagnostics/test-logcat.txt
mkdir -p app/build/reports/gl-screenshots
adb pull /sdcard/Pictures/MyLive2D-QA/. app/build/reports/gl-screenshots/ || true
adb logcat -d -s AndroidRuntime:E > app/build/reports/gl-screenshots/crashes.txt || true
exit "$test_status"
