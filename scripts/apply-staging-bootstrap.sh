#!/bin/sh
set -eu

# Owner-only, fail-closed preview/apply helper. Every manifest in a phase is
# successfully diffed before the first mutation in that phase.
PHASE=${PHASE:-}
APPLY_CHANGES=${APPLY_CHANGES:-false}
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)

case "$PHASE" in
  namespace)
    manifests="$REPO_ROOT/k8s/staging/namespace.yaml"
    ;;
  boundary)
    kubectl get namespace mnema-staging >/dev/null
    manifests="$REPO_ROOT/k8s/staging/admission.yaml $REPO_ROOT/k8s/staging/bootstrap.yaml $REPO_ROOT/k8s/cluster-issuers.yaml $REPO_ROOT/k8s/staging/routes.yaml"
    ;;
  *)
    echo "PHASE must be namespace or boundary" >&2
    exit 64
    ;;
esac
case "$APPLY_CHANGES" in
  true | false) ;;
  *) echo "APPLY_CHANGES must be true or false" >&2; exit 64 ;;
esac

for manifest in $manifests; do
  set +e
  kubectl diff -f "$manifest"
  diff_status=$?
  set -e
  if [ "$diff_status" -gt 1 ]; then
    echo "Unable to preview staging bootstrap manifest: $manifest" >&2
    exit "$diff_status"
  fi
done

if [ "$APPLY_CHANGES" = false ]; then
  printf 'staging_bootstrap_phase=%s preview=ok apply=skipped\n' "$PHASE"
  exit 0
fi

for manifest in $manifests; do
  kubectl apply -f "$manifest"
done
printf 'staging_bootstrap_phase=%s apply=ok\n' "$PHASE"
