#!/usr/bin/env bash
# Keep build/test diagnostics visible even when the GitHub log archive cannot be reached.
set -uo pipefail
log=$(mktemp)
trap 'rm -f "$log"' EXIT
./gradlew --no-daemon "$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
if ((status != 0)) && [[ "${GITHUB_ACTIONS:-}" == 'true' ]]; then
  python3 - "$log" <<'PY'
import pathlib, re, sys, xml.etree.ElementTree as ET

# AGP can withhold individual emulator failures from Gradle's console output.
failures = []
for directory in ('app/build/test-results', 'app/build/outputs/androidTest-results'):
    for report in pathlib.Path(directory).rglob('TEST-*.xml'):
        try:
            cases = ET.parse(report).iter('testcase')
            for case in cases:
                for failure in list(case.findall('failure')) + list(case.findall('error')):
                    details = (failure.get('message') or failure.text or '').strip().splitlines()
                    reason = '\n'.join(details[:8])[:800]
                    failures.append(f'{case.get("classname")}.{case.get("name")}: {reason}')
        except ET.ParseError:
            continue

log = pathlib.Path(sys.argv[1]).read_text(errors='replace').splitlines()
important = [line for line in log if re.search(r'^(e: |error: |.* FAILED|.*(?:AssertionError|ComparisonFailure)|.*Timed out waiting)', line)]
excerpt = '\n'.join(failures[:6] or (important[-25:] or log[-25:]))[:6000]
print('::error title=Android build or test failure::' + excerpt.replace('%','%25').replace('\r','%0D').replace('\n','%0A'))
PY
fi
exit "$status"
