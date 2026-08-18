#!/bin/sh
set -eu

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "usage: $0 RELEASE_MANIFEST DIFF_OUTPUT [EXPECTED_DIFF_SHA256]" >&2
  exit 64
fi

release_manifest=$1
diff_output=$2
expected_diff_sha256=${3-}
verify_expected=false
if [ "$#" -eq 3 ]; then
  verify_expected=true
fi

kubectl apply --dry-run=server -f "$release_manifest" >/dev/null

set +e
KUBECTL_EXTERNAL_DIFF='diff -u -N --label LIVE --label DESIRED' \
  kubectl diff --show-secrets=false -f "$release_manifest" >"$diff_output"
diff_status=$?
set -e

if [ "$diff_status" -gt 1 ]; then
  echo "Unable to capture the complete release diff (kubectl status $diff_status)" >&2
  exit "$diff_status"
fi

if [ "$diff_status" -eq 0 ]; then
  printf 'No application release changes.\n' >"$diff_output"
fi

actual_diff_sha256=$(sha256sum "$diff_output" | awk '{print $1}')
if [ "$verify_expected" = true ] && \
   [ "$actual_diff_sha256" != "$expected_diff_sha256" ]; then
  echo "Production release diff changed after preview approval" >&2
  echo "Approved $expected_diff_sha256; current $actual_diff_sha256" >&2
  exit 1
fi

printf '%s\n' "$actual_diff_sha256"
