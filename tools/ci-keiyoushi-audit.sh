#!/usr/bin/env bash
# Opt-in network audit; the normal extension tests are offline.
set -euo pipefail

build_tools="${ANDROID_HOME:?}/build-tools/36.0.0"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
SIPAN_KEIYOUSHI_AUDIT="$work" tools/ci-gradle.sh :app:testDebugUnitTest --tests '*StoreCatalogReaderTest.liveKeiyoushiAudit' --rerun
mapfile -t metadata < "$work/identity.txt"
read -r package code signer count <<< "${metadata[0]}"
"$build_tools/apksigner" verify --min-sdk-version 26 --print-certs "$work/sample.apk" > "$work/signing.txt"
grep -Fx "Signer #1 certificate SHA-256 digest: $signer" "$work/signing.txt"
"$build_tools/aapt" dump badging "$work/sample.apk" > "$work/badging.txt"
grep -F "package: name='$package' versionCode='$code' " "$work/badging.txt"
grep -F "uses-feature: name='tachiyomi.extension'" "$work/badging.txt"
printf 'Keiyoushi live HTTPS catalog: %s extensions. Verified the MANGA Plus APK package, versionCode %s and signer. APK was not executed.\n' \
  "$count" "$code" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
if [[ ${GITHUB_ACTIONS:-} == true ]]; then
  printf '::notice title=Keiyoushi live audit::Catalog loaded (%s extensions); MANGA Plus APK versionCode %s and publisher signature verified.\n' "$count" "$code"
fi
