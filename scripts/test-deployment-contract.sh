#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
PRODUCTION_WORKFLOW="$REPO_ROOT/.github/workflows/production-deploy.yaml"
STAGING_WORKFLOW="$REPO_ROOT/.github/workflows/staging-deploy.yaml"
ROLLBACK_DRILL_WORKFLOW="$REPO_ROOT/.github/workflows/staging-rollback-drill.yaml"
BOOTSTRAP="$REPO_ROOT/k8s/staging/bootstrap.yaml"
STAGING_DATA="$REPO_ROOT/k8s/staging/data.yaml"
STAGING_BUCKET_JOB="$REPO_ROOT/k8s/staging/minio-bucket-job.yaml"

assert_secret_prefix() {
  workflow="$1"
  prefix="$2"
  names=$(grep -Eo 'secrets\.[A-Z0-9_]+' "$workflow" | sed 's/^secrets\.//' | sort -u)
  if [ -z "$names" ]; then
    echo "No environment secret references found in $workflow" >&2
    exit 1
  fi
  for name in $names; do
    case "$name" in
      "$prefix"*) ;;
      *)
        echo "Secret $name in $workflow does not use the isolated $prefix prefix" >&2
        exit 1
        ;;
    esac
  done
}

assert_secret_prefix "$PRODUCTION_WORKFLOW" PROD_
assert_secret_prefix "$STAGING_WORKFLOW" STAGING_
assert_secret_prefix "$ROLLBACK_DRILL_WORKFLOW" STAGING_

if grep -Fq 'secrets: inherit' "$REPO_ROOT/.github/workflows/deploy.yaml"; then
  echo "Deployment callers must not inherit repository secrets" >&2
  exit 1
fi

if grep -Eq 'openssl rand|CURRENT_USER_INTERNAL_TOKEN|USER_INTERNAL_TOKEN_VALUE' "$PRODUCTION_WORKFLOW" "$STAGING_WORKFLOW"; then
  echo "Deployment workflows must not generate or recover an implicit internal token" >&2
  exit 1
fi

for token in MEDIA_INTERNAL_TOKEN CORE_INTERNAL_TOKEN USER_INTERNAL_TOKEN; do
  grep -Fq "key: $token" "$REPO_ROOT/k8s/core-deploy.yaml" || {
    echo "Core deployment is missing mandatory $token injection" >&2
    exit 1
  }
done

for workflow in "$PRODUCTION_WORKFLOW" "$STAGING_WORKFLOW"; do
  grep -Fq 'scripts/smoke/release_smoke.py' "$workflow"
  grep -Fq 'scripts/smoke/release_state.py snapshot' "$workflow"
  grep -Fq 'scripts/smoke/release_state.py rollback' "$workflow"
  grep -Fq "steps.apply.outputs.attempted == 'true'" "$workflow"
  grep -Fq "AUTO_ROLLBACK_ENABLED:" "$workflow"
  grep -Fq 'release-record-${{ github.run_id }}' "$workflow"
  if grep -E 'secret_names=.*SMOKE_PASSWORD' "$workflow" >/dev/null; then
    echo "Smoke account passwords must never be persisted in Kubernetes application secrets" >&2
    exit 1
  fi
done

for key in SMOKE_LOGIN SMOKE_TURNSTILE_BYPASS_KEY; do
  grep -Fq "key: $key" "$REPO_ROOT/k8s/auth-deploy.yaml" || {
    echo "Auth deployment is missing mandatory $key injection" >&2
    exit 1
  }
done

grep -Fq 'RUN_STAGING_ROLLBACK_DRILL' "$ROLLBACK_DRILL_WORKFLOW"
grep -Fq 'name: staging' "$ROLLBACK_DRILL_WORKFLOW"
if grep -Eq 'PROD_|namespace:[[:space:]]+prod|NS:[[:space:]]+prod' "$ROLLBACK_DRILL_WORKFLOW"; then
  echo "The destructive rollback drill must remain staging-only" >&2
  exit 1
fi

grep -Fq 'kind: ResourceQuota' "$BOOTSTRAP"
grep -Fq 'kind: LimitRange' "$BOOTSTRAP"
grep -Fq 'kind: Role' "$BOOTSTRAP"
grep -Fq 'kind: RoleBinding' "$BOOTSTRAP"
if grep -Eq '^kind: Cluster(Role|RoleBinding)$' "$BOOTSTRAP"; then
  echo "Staging bootstrap must not grant cluster-scoped RBAC" >&2
  exit 1
fi

image_count=0
pinned_image_count=0
for manifest in "$STAGING_DATA" "$STAGING_BUCKET_JOB"; do
  manifest_image_count=$(grep -E -c '^[[:space:]]+image:' "$manifest" || true)
  manifest_pinned_count=$(grep -E -c '^[[:space:]]+image: [^[:space:]]+@sha256:[0-9a-f]{64}$' "$manifest" || true)
  image_count=$((image_count + manifest_image_count))
  pinned_image_count=$((pinned_image_count + manifest_pinned_count))
done
if [ "$image_count" -ne 4 ] || [ "$pinned_image_count" -ne "$image_count" ]; then
  echo "Every staging data image must be pinned by sha256 digest" >&2
  exit 1
fi

printf 'deployment_contract=ok\n'
