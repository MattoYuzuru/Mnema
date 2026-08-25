#!/bin/sh
set -eu

if [ "$#" -ne 4 ]; then
  echo "usage: $0 SECRET_MANIFEST DATA_MANIFEST BUCKET_JOB_MANIFEST RELEASE_MANIFEST" >&2
  exit 64
fi

JOB_PREVIEW_MANIFEST=

cleanup() {
  if [ -n "$JOB_PREVIEW_MANIFEST" ]; then
    rm -f "$JOB_PREVIEW_MANIFEST"
  fi
}
trap cleanup EXIT HUP INT TERM

preview_diff() {
  manifest=$1
  hide_secrets=$2
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

preview_manifest() {
  manifest=$1
  hide_secrets=$2
  kubectl apply --dry-run=server -f "$manifest" >/dev/null
  preview_diff "$manifest" "$hide_secrets"
}

preview_recreated_job() {
  manifest=$1
  name_count=$(grep -c '^  name: minio-bucket-bootstrap$' "$manifest" || true)
  if [ "$name_count" -ne 1 ]; then
    echo "Unable to identify the staging bucket Job: $manifest" >&2
    exit 65
  fi

  JOB_PREVIEW_MANIFEST=$(mktemp "${TMPDIR:-/tmp}/mnema-staging-job-preview.XXXXXX")
  preview_name="minio-bucket-bootstrap-preview-$$"
  sed "s/^  name: minio-bucket-bootstrap$/  name: $preview_name/" \
    "$manifest" > "$JOB_PREVIEW_MANIFEST"

  # The real rollout deletes this one-shot Job before applying it. Validate the
  # corresponding create request under a temporary name so an existing Job's
  # immutable pod template cannot turn a mutation-free preview into an update.
  kubectl create --dry-run=server -f "$JOB_PREVIEW_MANIFEST" >/dev/null
  preview_diff "$JOB_PREVIEW_MANIFEST" false

  rm -f "$JOB_PREVIEW_MANIFEST"
  JOB_PREVIEW_MANIFEST=
}

preview_manifest "$1" true
preview_manifest "$2" false
preview_recreated_job "$3"
preview_manifest "$4" false
printf 'staging_plan_preview=ok\n'
