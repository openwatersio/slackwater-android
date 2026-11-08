#!/usr/bin/env bash
set -euo pipefail

# Run on a disposable emulator with the debug and test APKs installed.
serial=$(adb get-serialno)
[[ $serial == emulator-* ]] || { echo "Select a disposable emulator with ANDROID_SERIAL." >&2; exit 1; }
package=io.openwaters.slackwater.android
adb -s "$serial" shell run-as "$package" id >/dev/null
adb -s "$serial" shell pm clear "$package"
trap 'adb -s "$serial" shell pm clear "$package" >/dev/null' EXIT
for phase in first relaunch; do
    adb -s "$serial" shell am force-stop "$package"
    echo "Running location prompt phase: $phase"
    status=0
    output=$(adb -s "$serial" shell am instrument -w \
        -e class "$package.FirstLaunchLocationTest" -e locationPromptPhase "$phase" \
        "$package.test/$package.SlackwaterTestRunner" 2>&1) || status=$?
    printf '%s\n' "$output"
    if (( status != 0 )) || ! grep -Fxq "Verified location prompt: $phase" <<<"$output" || ! grep -Fxq 'OK (1 test)' <<<"$output"; then
        echo "Location prompt phase failed: $phase" >&2
        exit 1
    fi
done
