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
fi
exit "$status"
