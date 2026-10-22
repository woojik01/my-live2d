#!/usr/bin/env bash
# Keep the emulator alive while collecting evidence, preserving the test exit code.
set -u
./gradlew --no-daemon :app:connectedDebugAndroidTest
test_status=$?
mkdir -p app/build/reports/gl-screenshots
adb pull /sdcard/Android/data/dev.mylive2d.app/files/qa/. app/build/reports/gl-screenshots/ || true
adb logcat -d -s AndroidRuntime:E > app/build/reports/gl-screenshots/crashes.txt || true
exit "$test_status"
