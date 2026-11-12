#!/usr/bin/env bash
# Runs the alarm sound flow: pushes a test MP3 into Download (the document picker reads it from there), then the flow.
set -euo pipefail
cd "$(dirname "$0")/.."
adb push .maestro/sounds/test_tone.mp3 /sdcard/Download/test_tone.mp3 >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/test_tone.mp3 >/dev/null || true
MAESTRO_CLI_NO_ANALYTICS=1 maestro test .maestro/sounds/alarm_sound.yaml
