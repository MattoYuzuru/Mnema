#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
PRODUCTION_WORKFLOW="$REPO_ROOT/.github/workflows/production-deploy.yaml"
STAGING_WORKFLOW="$REPO_ROOT/.github/workflows/staging-deploy.yaml"
BOOTSTRAP="$REPO_ROOT/k8s/staging/bootstrap.yaml"
ADMISSION="$REPO_ROOT/k8s/staging/admission.yaml"
STAGING_DATA="$REPO_ROOT/k8s/staging/data.yaml"
STAGING_BUCKET_JOB="$REPO_ROOT/k8s/staging/minio-bucket-job.yaml"
STAGING_ROUTES="$REPO_ROOT/k8s/staging/routes.yaml"

grep -Fq 'version: v1.36.0' "$STAGING_WORKFLOW"
grep -Fq 'run: ./scripts/test-create-staging-kubeconfig.sh' "$REPO_ROOT/.github/workflows/deploy.yaml"
grep -Fq 'run: ./scripts/test-create-staging-kubeconfig.sh' "$REPO_ROOT/.github/workflows/pull-request.yaml"
grep -Fq 'run: ./scripts/test-environment-secret-separation.sh' "$REPO_ROOT/.github/workflows/deploy.yaml"
grep -Fq 'run: ./scripts/test-environment-secret-separation.sh' "$REPO_ROOT/.github/workflows/pull-request.yaml"
for contract_test in \
  test-staging-host-firewall.sh \
  test-staging-tls-boundary.sh \
  test-apply-staging-bootstrap.sh
do
  grep -Fq "run: ./scripts/$contract_test" "$REPO_ROOT/.github/workflows/deploy.yaml"
  grep -Fq "run: ./scripts/$contract_test" "$REPO_ROOT/.github/workflows/pull-request.yaml"
done
grep -Fq 'After revocation, never blindly revert' "$REPO_ROOT/docs/operations/staging-runbook.md"
grep -Fq './scripts/verify-environment-secret-separation.py --desired' "$REPO_ROOT/docs/operations/staging-runbook.md"

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
grep -Fq 'kind: ResourceQuota' "$BOOTSTRAP"
grep -Fq 'kind: LimitRange' "$BOOTSTRAP"
grep -Fq 'kind: Role' "$BOOTSTRAP"
grep -Fq 'kind: RoleBinding' "$BOOTSTRAP"
if grep -Eq '^kind: Cluster(Role|RoleBinding)$' "$BOOTSTRAP"; then
  echo "Staging bootstrap must not grant cluster-scoped RBAC" >&2
  exit 1
fi
NAMESPACE_MANIFEST="$REPO_ROOT/k8s/staging/namespace.yaml"
grep -Fq 'pod-security.kubernetes.io/enforce: baseline' "$NAMESPACE_MANIFEST"
grep -Fq 'pod-security.kubernetes.io/audit: restricted' "$NAMESPACE_MANIFEST"
grep -Fq 'pod-security.kubernetes.io/warn: restricted' "$NAMESPACE_MANIFEST"
grep -Fq 'assert_pod_security_rejects privileged' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'assert_pod_security_rejects hostPath' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'assert_pod_security_rejects hostNetwork' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
test "$(grep -c '^kind: ValidatingAdmissionPolicy$' "$ADMISSION")" -eq 4
test "$(grep -c '^kind: ValidatingAdmissionPolicyBinding$' "$ADMISSION")" -eq 4
test "$(grep -c 'failurePolicy: Fail' "$ADMISSION")" -eq 4
test "$(grep -c 'validationActions: \[Deny\]' "$ADMISSION")" -eq 4
grep -Fq "object.spec.type == 'ClusterIP'" "$ADMISSION"
grep -Fq 'object.spec.externalIPs.size() == 0' "$ADMISSION"
grep -Fq "serviceAccountName != 'mnema-deployer'" "$ADMISSION"
grep -Fq 'automountServiceAccountToken == false' "$ADMISSION"
grep -Fq "object.type == 'Opaque'" "$ADMISSION"
grep -Fq 'kubernetes.io/service-account.name' "$ADMISSION"
grep -Fq 'resourceNames: ["mnema-secrets"]' "$BOOTSTRAP"
if grep -Fq 'resources: ["configmaps", "secrets", "services"]' "$BOOTSTRAP"; then
  echo 'Scoped staging CI must not have unrestricted Secret CRUD' >&2
  exit 1
fi
grep -Fq 'kind: NetworkPolicy' "$BOOTSTRAP"
grep -Fq 'name: mnema-staging-default-deny' "$BOOTSTRAP"
grep -Fq 'name: mnema-staging-allowed-traffic' "$BOOTSTRAP"
grep -Fq '169.254.0.0/16' "$BOOTSTRAP"
grep -Fq 'fe80::/10' "$BOOTSTRAP"
grep -Fq 'requests.ephemeral-storage: 12Gi' "$BOOTSTRAP"
grep -Fq 'limits.ephemeral-storage: 40Gi' "$BOOTSTRAP"
grep -Fq 'ephemeral-storage: 256Mi' "$BOOTSTRAP"
grep -Fq 'ephemeral-storage: 4Gi' "$BOOTSTRAP"
for quota_key in \
  count/configmaps \
  count/secrets \
  count/deployments.apps \
  count/replicasets.apps \
  count/statefulsets.apps \
  count/jobs.batch
do
  grep -Fq "$quota_key:" "$BOOTSTRAP"
done
grep -Fq 'verify-staging-network-boundary.sh' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'reconcile-staging-host-firewall.sh' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'verify-staging-tls-boundary.sh' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'mnema-staging-host-boundary.service' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'mnema-staging-host-boundary.timer' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'OnUnitActiveSec=1min' "$REPO_ROOT/deploy/systemd/mnema-staging-host-boundary.timer"
grep -Fq 'serviceType: ClusterIP' "$REPO_ROOT/k8s/cluster-issuers.yaml"
grep -Fq 'staging.mnema.app' "$REPO_ROOT/k8s/cluster-issuers.yaml"
grep -Fq 'count/secrets: "12"' "$BOOTSTRAP"
grep -Fq './scripts/verify-kubernetes-bootstrap-secret-values.py' "$STAGING_WORKFLOW"
staging_restart_step=$(sed -n '/name: Restart staging application Secret consumers/,/name: Verify staging service rollouts/p' "$STAGING_WORKFLOW")
printf '%s\n' "$staging_restart_step" | grep -Fq "if: steps.secret-preview.outputs.app_secret_drift == 'true'"
for consumer in mnema-auth mnema-user mnema-core mnema-media mnema-import; do
  printf '%s\n' "$staging_restart_step" | grep -Fq "deployment/$consumer"
done
if printf '%s\n' "$staging_restart_step" | grep -Fq 'deployment/mnema-frontend'; then
  echo 'Staging frontend must not restart for a Secret it does not consume' >&2
  exit 1
fi
if grep -Fq 'ingresses' "$BOOTSTRAP"; then
  echo "Scoped staging CI must not be able to replace shared-ingress host routing" >&2
  exit 1
fi
if grep -Fq 'kind: Ingress' "$STAGING_DATA"; then
  echo "Staging data reconciliation must not delegate ingress mutation to CI" >&2
  exit 1
fi
test "$(grep -c '^kind: Ingress$' "$STAGING_ROUTES")" -eq 3
for host in staging.mnema.app auth.staging.mnema.app storage.staging.mnema.app; do
  test "$(grep -F -c "host: $host" "$STAGING_ROUTES")" -eq 1
done

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
