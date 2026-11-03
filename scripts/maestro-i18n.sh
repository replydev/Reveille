#!/usr/bin/env bash
# Runs the localized Maestro flows: clears Reveille, sets its per-app language, runs the flow, always resets it.
#
# The clear happens here (`pm clear`) instead of `launchApp: clearState: true`, because clearing the
# app's data also drops its per-app locale. The flow therefore launches without clearState.
set -euo pipefail
PKG=com.replydev.reveille
cd "$(dirname "$0")/.."
trap 'adb shell cmd locale set-app-locales "$PKG" --locales "" >/dev/null' EXIT
adb shell pm clear "$PKG" >/dev/null
adb shell cmd locale set-app-locales "$PKG" --locales it
MAESTRO_CLI_NO_ANALYTICS=1 maestro test .maestro/i18n/italian.yaml
