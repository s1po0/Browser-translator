#!/usr/bin/env bash
# Keep build/test diagnostics visible even when the GitHub log archive cannot be reached.
set -uo pipefail
log=$(mktemp)
trap 'rm -f "$log"' EXIT
./gradlew --no-daemon "$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
if ((status != 0)) && [[ "${GITHUB_ACTIONS:-}" == 'true' ]]; then
  python3 - "$log" <<'PY'
import pathlib, re, sys
log = pathlib.Path(sys.argv[1]).read_text(errors='replace')
important = [line for line in log.splitlines() if re.search(r'^(e: |error: |> |.* FAILED|.*AssertionError|.*Timed out waiting)', line)]
excerpt = '\n'.join((important[-45:] or log.splitlines()[-45:]))[-6000:]
print('::error title=Android build or test failure::' + excerpt.replace('%','%25').replace('\r','%0D').replace('\n','%0A'))
PY
fi
exit "$status"
