#!/bin/sh
set -eu

APPLY_CHANGES=${APPLY_CHANGES:-false}
MINIMUM_FREE_GIB=${MINIMUM_FREE_GIB:-60}
STORAGE_PATH=${STORAGE_PATH:-/var/lib/rancher/k3s/storage}

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-backup-platform.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

case "$APPLY_CHANGES" in
  true | false) ;;
  *) echo 'APPLY_CHANGES must be true or false' >&2; exit 64 ;;
esac
case "$MINIMUM_FREE_GIB" in
  '' | *[!0-9]*) echo 'MINIMUM_FREE_GIB must be a positive integer' >&2; exit 64 ;;
esac
if [ "$MINIMUM_FREE_GIB" -lt 60 ]; then
  echo 'MINIMUM_FREE_GIB must not be lower than the 60 GiB recovery envelope' >&2
  exit 64
fi
if [ ! -d "$STORAGE_PATH" ]; then
  echo "Storage path does not exist: $STORAGE_PATH" >&2
  exit 1
fi

available_kib=$(df -Pk "$STORAGE_PATH" | awk 'NR == 2 { print $4 }')
required_kib=$((MINIMUM_FREE_GIB * 1024 * 1024))
case "$available_kib" in
  '' | *[!0-9]*) echo 'Unable to measure storage headroom' >&2; exit 1 ;;
esac
if [ "$available_kib" -lt "$required_kib" ]; then
  echo "At least ${MINIMUM_FREE_GIB} GiB free is required on $STORAGE_PATH" >&2
  exit 1
fi

test "$(kubectl -n prod get statefulset postgres -o jsonpath='{.status.readyReplicas}')" = 1
kubectl -n prod get secret mnema-secrets >/dev/null
kubectl -n prod get secret mnema-backup-secrets >/dev/null
kubectl -n observability get statefulset prometheus >/dev/null

kubectl -n prod create configmap mnema-backup-scripts \
  --from-file="$REPO_ROOT/scripts/backup" \
  --dry-run=client -o yaml > "$TEST_ROOT/backup-scripts.yaml"

preview() {
  manifest=$1
  if kubectl diff -f "$manifest"; then
    return
  else
    result=$?
  fi
  if [ "$result" -ne 1 ]; then
    echo "kubectl diff failed for $manifest" >&2
    exit "$result"
  fi
}

preview "$REPO_ROOT/k8s/backup/restore-boundary.yaml"
preview "$TEST_ROOT/backup-scripts.yaml"
preview "$REPO_ROOT/k8s/backup/cronjob.yaml"
preview "$REPO_ROOT/k8s/observability/10-prometheus-config.yaml"
preview "$REPO_ROOT/k8s/observability/12-prometheus-rules.yaml"
preview "$REPO_ROOT/k8s/observability/11-prometheus.yaml"

if [ "$APPLY_CHANGES" = false ]; then
  printf 'backup_platform=previewed available_kib=%s\n' "$available_kib"
  exit 0
fi

kubectl apply -f "$REPO_ROOT/k8s/backup/restore-boundary.yaml"
kubectl apply -f "$TEST_ROOT/backup-scripts.yaml"
kubectl apply -f "$REPO_ROOT/k8s/backup/cronjob.yaml"
kubectl apply -f "$REPO_ROOT/k8s/observability/10-prometheus-config.yaml"
kubectl apply -f "$REPO_ROOT/k8s/observability/12-prometheus-rules.yaml"
kubectl apply -f "$REPO_ROOT/k8s/observability/11-prometheus.yaml"
kubectl -n observability rollout restart statefulset/prometheus
kubectl -n observability rollout status statefulset/prometheus --timeout=10m
kubectl -n prod get cronjob mnema-postgres-backup >/dev/null
test "$(kubectl -n mnema-restore-drill get configmap mnema-restore-boundary -o jsonpath='{.data.contractVersion}')" = 1
printf 'backup_platform=applied available_kib=%s\n' "$available_kib"
