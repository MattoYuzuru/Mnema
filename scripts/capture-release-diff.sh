#!/bin/sh
set -eu

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "usage: $0 RELEASE_MANIFEST DIFF_OUTPUT [EXPECTED_DIFF_SHA256]" >&2
  exit 64
fi

release_manifest=$1
diff_output=$2
expected_diff_sha256=${3-}
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH='' cd -- "$script_dir/.." && pwd)
verify_expected=false
if [ "$#" -eq 3 ]; then
  verify_expected=true
fi

test_root=$(mktemp -d "${TMPDIR:-/tmp}/mnema-production-plan.XXXXXX")
trap 'rm -rf "$test_root"' EXIT HUP INT TERM
dashboard_manifest="$test_root/grafana-dashboards.yaml"
resource_diff="$test_root/resources.diff"
legacy_inventory_raw="$test_root/legacy-removals.raw"
legacy_inventory="$test_root/legacy-removals.txt"

kubectl -n observability create configmap grafana-dashboards \
  --from-file="$repo_root/k8s/observability/dashboards" \
  --dry-run=client -o yaml >"$dashboard_manifest"

set -- \
  -f "$repo_root/k8s/namespace.yaml" \
  -f "$repo_root/k8s/cluster-issuers.yaml" \
  -f "$repo_root/k8s/postgres.yaml" \
  -f "$repo_root/k8s/redis.yaml" \
  -f "$repo_root/k8s/observability" \
  -f "$dashboard_manifest" \
  -f "$release_manifest"

# Validate every declarative resource that the mutating job will apply, not only
# the rendered application release. This stays read-only while exercising the
# live API server's admission and immutable-field checks.
kubectl apply --dry-run=server "$@" >/dev/null

set +e
KUBECTL_EXTERNAL_DIFF="$script_dir/canonical-kubectl-diff.sh" \
  kubectl diff --show-secrets=false "$@" >"$resource_diff"
diff_status=$?
set -e

if [ "$diff_status" -gt 1 ]; then
  echo "Unable to capture the complete release diff (kubectl status $diff_status)" >&2
  exit "$diff_status"
fi

# The actual deploy also removes the retired AI bridge. Bind that imperative
# deletion to the same approval and re-check it immediately before mutation.
kubectl -n prod get service mnema-ai-bridge \
  --ignore-not-found=true -o name >"$legacy_inventory_raw"
kubectl -n prod get endpointslice \
  -l kubernetes.io/service-name=mnema-ai-bridge \
  --ignore-not-found=true -o name >>"$legacy_inventory_raw"
LC_ALL=C sort -u "$legacy_inventory_raw" >"$legacy_inventory"

if [ "$diff_status" -eq 0 ] && [ ! -s "$legacy_inventory" ]; then
  printf 'No production resource changes.\n' >"$diff_output"
else
  {
    printf 'Production declarative resource diff:\n'
    if [ -s "$resource_diff" ]; then
      cat "$resource_diff"
    else
      printf 'No declarative resource changes.\n'
    fi
    printf '\nPlanned legacy resource removals:\n'
    if [ -s "$legacy_inventory" ]; then
      sed 's/^/- /' "$legacy_inventory"
    else
      printf 'None.\n'
    fi
  } >"$diff_output"
fi

actual_diff_sha256=$(sha256sum "$diff_output" | awk '{print $1}')
if [ "$verify_expected" = true ] && \
   [ "$actual_diff_sha256" != "$expected_diff_sha256" ]; then
  echo "Production release diff changed after preview approval" >&2
  echo "Approved $expected_diff_sha256; current $actual_diff_sha256" >&2
  exit 1
fi

printf '%s\n' "$actual_diff_sha256"
