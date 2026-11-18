#!/usr/bin/env bash
# Summarize stage timings from synthetic fixtures, not from user pages or device logs in general.
set -uo pipefail
tools/ci-gradle.sh :app:connectedDebugAndroidTest "$@"
status=$?
if [[ "${GITHUB_ACTIONS:-}" == 'true' ]]; then
  timings=$(adb logcat -d -v brief -s SipanComicSmoke:I '*:S' | grep '^I/SipanComicSmoke' | tail -12 || true)
  if [[ -n "$timings" ]]; then
    printf '### English comic fixture timings (emulator; no live MT)\n\n```text\n%s\n```\n' "$timings" >> "$GITHUB_STEP_SUMMARY"
    escaped=${timings//%/%25}; escaped=${escaped//$'\r'/%0D}; escaped=${escaped//$'\n'/%0A}
    printf '::notice title=English comic fixture timings::%s\n' "$escaped"
  fi
  if ((status != 0)); then
    # Do not rely on the downloadable log archive to tell a fixture failure from an emulator exit.
    crash=$(adb logcat -d -b crash -v brief | tail -18 || true)
    last_exit=$(adb shell dumpsys activity exit-info dev.rojink.browser.debug | head -25 || true)
    diagnostic=$(printf '%s\n%s' "$crash" "$last_exit" | cut -c1-300)
    escaped=${diagnostic//%/%25}; escaped=${escaped//$'\r'/%0D}; escaped=${escaped//$'\n'/%0A}
    printf '::notice title=Android test process diagnostics::%s\n' "$escaped"
  fi
fi
exit "$status"
