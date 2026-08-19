#!/bin/sh
set -eu

if [ "$#" -ne 4 ]; then
  echo "usage: $0 SECRET_MANIFEST DATA_MANIFEST BUCKET_JOB_MANIFEST RELEASE_MANIFEST" >&2
  exit 64
fi

preview_manifest() {
  manifest=$1
  hide_secrets=$2
  kubectl apply --dry-run=server -f "$manifest" >/dev/null
  set +e
  if [ "$hide_secrets" = true ]; then
    kubectl diff --show-secrets=false -f "$manifest"
  else
    kubectl diff -f "$manifest"
  fi
  status=$?
  set -e
  if [ "$status" -gt 1 ]; then
    echo "Unable to preview staging manifest: $manifest" >&2
    exit "$status"
  fi
}

preview_manifest "$1" true
preview_manifest "$2" false
preview_manifest "$3" false
preview_manifest "$4" false
printf 'staging_plan_preview=ok\n'
