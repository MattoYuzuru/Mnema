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
source_image=$(kubectl -n prod get statefulset postgres -o jsonpath='{.spec.template.spec.containers[?(@.name=="postgres")].image}')
case "$source_image" in
  postgres:16 | postgres:16-* | postgres:16@* | */postgres:16 | */postgres:16-* | */postgres:16@*) ;;
  *) echo "Production PostgreSQL must remain on the reviewed PostgreSQL 16 source boundary" >&2; exit 1 ;;
esac
test "$(kubectl get storageclass local-path -o jsonpath='{.provisioner}')" = rancher.io/local-path
test "$(kubectl get storageclass local-path -o jsonpath='{.metadata.annotations.storageclass\.kubernetes\.io/is-default-class}')" = true
test "$(kubectl -n prod get persistentvolumeclaim data-postgres-0 -o jsonpath='{.status.phase}')" = Bound
test "$(kubectl -n prod get persistentvolumeclaim data-postgres-0 -o jsonpath='{.spec.storageClassName}')" = local-path
test -n "$(kubectl -n prod get persistentvolumeclaim data-postgres-0 -o jsonpath='{.status.capacity.storage}')"
kubectl -n prod get secret mnema-secrets >/dev/null
kubectl -n prod get secret mnema-backup-secrets >/dev/null
test "$(kubectl -n observability get statefulset prometheus -o jsonpath='{.status.readyReplicas}')" = 1

active_backup_jobs=$(kubectl -n prod get jobs \
  -l app.kubernetes.io/name=mnema-postgres-backup \
  -o jsonpath='{range .items[*]}{.metadata.name}{"|"}{.status.active}{"\n"}{end}' | \
  awk -F'|' '$2 + 0 > 0 { print $1 }')
if [ -n "$active_backup_jobs" ]; then
  echo 'A production backup Job is active; refuse to change its mounted scripts or schedule' >&2
  exit 1
fi

restore_boundary_exists=false
restore_namespace=$(kubectl get namespace mnema-restore-drill --ignore-not-found -o name)
case "$restore_namespace" in
  '') ;;
  namespace/mnema-restore-drill) restore_boundary_exists=true ;;
  *)
    echo "Unexpected restore namespace lookup result: $restore_namespace" >&2
    exit 1
    ;;
esac
if [ "$restore_boundary_exists" = true ]; then
  busy_restore_resources=$(kubectl -n mnema-restore-drill get \
    pods,jobs.batch,statefulsets.apps,persistentvolumeclaims,services \
    -o name)
  if [ -n "$busy_restore_resources" ]; then
    echo 'The restore boundary is busy; refuse to change its policy or quota' >&2
    exit 1
  fi
fi

kubectl -n prod create configmap mnema-backup-scripts \
  --from-file="$REPO_ROOT/scripts/backup/backup.sh" \
  --from-file="$REPO_ROOT/scripts/backup/upload.sh" \
  --from-file="$REPO_ROOT/scripts/backup/reconcile.sql" \
  --from-file="$REPO_ROOT/scripts/backup/capacity.sql" \
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

if [ "$restore_boundary_exists" = true ]; then
  preview "$REPO_ROOT/k8s/backup/restore-boundary.yaml"
else
  # Namespaced objects cannot be server-diffed until their namespace exists.
  # On the first bootstrap every object in this boundary is necessarily new, so
  # client dry-run validates and lists the exact create set without mutating it.
  kubectl apply --dry-run=client --validate=strict \
    -f "$REPO_ROOT/k8s/backup/restore-boundary.yaml" -o name
  printf 'restore_boundary=planned-create namespace=mnema-restore-drill\n'
fi
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
