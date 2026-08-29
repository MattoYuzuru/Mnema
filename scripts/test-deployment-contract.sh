#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
MAIN_WORKFLOW="$REPO_ROOT/.github/workflows/deploy.yaml"
PRODUCTION_WORKFLOW="$REPO_ROOT/.github/workflows/production-deploy.yaml"
STAGING_WORKFLOW="$REPO_ROOT/.github/workflows/staging-deploy.yaml"
ROLLBACK_DRILL_WORKFLOW="$REPO_ROOT/.github/workflows/staging-rollback-drill.yaml"
BOOTSTRAP="$REPO_ROOT/k8s/staging/bootstrap.yaml"
ADMISSION="$REPO_ROOT/k8s/staging/admission.yaml"
STAGING_DATA="$REPO_ROOT/k8s/staging/data.yaml"
STAGING_BUCKET_JOB="$REPO_ROOT/k8s/staging/minio-bucket-job.yaml"
STAGING_ROUTES="$REPO_ROOT/k8s/staging/routes.yaml"
ALLOY_CONFIG="$REPO_ROOT/k8s/observability/30-alloy-config.yaml"

grep -Eq '^[[:space:]]*targets[[:space:]]*=[[:space:]]*discovery\.relabel\.pods\.output[[:space:]]*$' \
  "$ALLOY_CONFIG"
if grep -Eq 'discovery\.relabel\.pods\.targets' "$ALLOY_CONFIG"; then
  echo 'Alloy must consume the discovery.relabel output export' >&2
  exit 1
fi

grep -Fq 'version: v1.36.0' "$STAGING_WORKFLOW"
grep -Fq 'workflow_dispatch:' "$MAIN_WORKFLOW"
grep -Fq 'name: Require exact main branch' "$MAIN_WORKFLOW"
test "$(grep -c 'needs: validate-main-ref' "$MAIN_WORKFLOW")" -eq 2
grep -Fq 'run: ./scripts/test-create-staging-kubeconfig.sh' "$MAIN_WORKFLOW"
grep -Fq 'run: ./scripts/test-create-staging-kubeconfig.sh' "$REPO_ROOT/.github/workflows/pull-request.yaml"
grep -Fq 'run: ./scripts/test-environment-secret-separation.sh' "$MAIN_WORKFLOW"
grep -Fq 'run: ./scripts/test-environment-secret-separation.sh' "$REPO_ROOT/.github/workflows/pull-request.yaml"
for contract_test in \
  test-staging-host-firewall.sh \
  test-staging-tls-boundary.sh \
  test-apply-staging-bootstrap.sh \
  test-staging-plan-preview.sh \
  test-kubernetes-secret-consumer-reconciliation.sh \
  test-kubernetes-secret-rollback.sh \
  test-production-telemetry-boundary.sh
do
  grep -Fq "run: ./scripts/$contract_test" "$MAIN_WORKFLOW"
  grep -Fq "run: ./scripts/$contract_test" "$REPO_ROOT/.github/workflows/pull-request.yaml"
done

for workflow in "$PRODUCTION_WORKFLOW" "$STAGING_WORKFLOW"; do
  grep -Fq './scripts/preserve-kubernetes-secret.py snapshot' "$workflow"
  grep -Fq './scripts/preserve-kubernetes-secret.py restore' "$workflow"
  grep -Fq 'id: app-secret-restore' "$workflow"
  grep -Fq "steps.app-secret-restore.outcome == 'success'" "$workflow"
  grep -Fq 'SMOKE_LOGIN,SMOKE_TURNSTILE_BYPASS_KEY' "$workflow"
done
grep -Fq 'resourceVersion: \"${APP_SECRET_RESOURCE_VERSION}\"' "$STAGING_WORKFLOW"
staging_rollback_smoke=$(sed -n '/name: Verify complete staging rollback/,/name: Upload staging failure evidence/p' "$STAGING_WORKFLOW")
printf '%s\n' "$staging_rollback_smoke" | grep -Fq 'SMOKE_PASSWORD: ${{ secrets.STAGING_SMOKE_PASSWORD }}'
if printf '%s\n' "$staging_rollback_smoke" | grep -Fq -- '--identity-only'; then
  echo 'Staging rollback must pass the complete authenticated smoke' >&2
  exit 1
fi
legacy_staging_rollback_smoke=$(sed -n '/name: Verify adopted legacy staging rollback identity/,/name: Verify complete staging rollback/p' "$STAGING_WORKFLOW")
printf '%s\n' "$legacy_staging_rollback_smoke" | grep -Fq "steps.rollback.outputs.authenticated_smoke_supported != 'true'"
printf '%s\n' "$legacy_staging_rollback_smoke" | grep -Fq -- '--identity-only'
printf '%s\n' "$staging_rollback_smoke" | grep -Fq "steps.rollback.outputs.authenticated_smoke_supported == 'true'"
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
assert_secret_prefix "$ROLLBACK_DRILL_WORKFLOW" STAGING_

grep -Fq 'workflows: [Main CI]' "$STAGING_WORKFLOW"
grep -Fq 'workflows: [Staging Deploy]' "$PRODUCTION_WORKFLOW"
staging_gate=$(sed -n '/^  validate-main-ci:/,/^  deploy-staging:/p' "$STAGING_WORKFLOW")
production_gate=$(sed -n '/^  validate-staging-deploy:/,/^  preview-production:/p' "$PRODUCTION_WORKFLOW")
staging_deploy_header=$(sed -n '/^  deploy-staging:/,/^    steps:/p' "$STAGING_WORKFLOW")
production_preview_header=$(sed -n '/^  preview-production:/,/^    steps:/p' "$PRODUCTION_WORKFLOW")
printf '%s\n' "$staging_gate" | grep -Fq 'UPSTREAM_CONCLUSION: ${{ github.event.workflow_run.conclusion }}'
printf '%s\n' "$staging_gate" | grep -Fq 'if [ "$UPSTREAM_CONCLUSION" != success ]; then'
printf '%s\n' "$staging_gate" | grep -Fq 'push | workflow_dispatch'
printf '%s\n' "$staging_gate" | grep -Fq 'exit 1'
printf '%s\n' "$staging_gate" | grep -Fq 'Verify exact release artifacts before staging access'
printf '%s\n' "$production_gate" | grep -Fq 'UPSTREAM_CONCLUSION: ${{ github.event.workflow_run.conclusion }}'
printf '%s\n' "$production_gate" | grep -Fq 'if [ "$UPSTREAM_CONCLUSION" != success ]; then'
printf '%s\n' "$production_gate" | grep -Fq '[ "$UPSTREAM_EVENT" != workflow_run ]'
printf '%s\n' "$production_gate" | grep -Fq 'exit 1'
printf '%s\n' "$production_gate" | grep -Fq 'Verify exact release artifact before production access'
if printf '%s\n%s\n' "$staging_gate" "$production_gate" | grep -Fq 'environment:'; then
  echo 'Untrusted predecessor and artifact validation must run before Environment access' >&2
  exit 1
fi
printf '%s\n' "$staging_deploy_header" | grep -Fq 'needs: validate-main-ci'
printf '%s\n' "$production_preview_header" | grep -Fq 'needs: validate-staging-deploy'
if printf '%s\n%s\n' "$staging_deploy_header" "$production_preview_header" | grep -Eq '^    if:'; then
  echo 'Environment jobs must not turn a rejected predecessor into a successful skipped workflow' >&2
  exit 1
fi
grep -Fq 'RELEASE_SHA: ${{ github.event.workflow_run.head_sha }}' "$STAGING_WORKFLOW"
test "$(grep -c 'RELEASE_SHA: ${{ github.event.workflow_run.head_sha }}' "$PRODUCTION_WORKFLOW")" -eq 3
test "$(grep -c 'run-id: ${{ github.event.workflow_run.id }}' "$STAGING_WORKFLOW")" -eq 4
test "$(grep -c 'run-id: ${{ github.event.workflow_run.id }}' "$PRODUCTION_WORKFLOW")" -eq 3
test "$(grep -c 'github-token: ${{ github.token }}' "$STAGING_WORKFLOW")" -eq 4
test "$(grep -c 'github-token: ${{ github.token }}' "$PRODUCTION_WORKFLOW")" -eq 3
grep -Fq 'Staging release artifact does not match the tested revision' "$STAGING_WORKFLOW"
grep -Fq 'name: Relay the staging-approved production release' "$STAGING_WORKFLOW"
grep -Fq '${{ runner.temp }}/production-promotion/production-release.yaml' "$STAGING_WORKFLOW"
grep -Fq -- '--environment production-promotion' "$STAGING_WORKFLOW"
grep -Fq 'Production promotion manifest does not match the tested revision' "$STAGING_WORKFLOW"
test "$(grep -c 'Production release artifact does not match the staging-approved revision' "$PRODUCTION_WORKFLOW")" -eq 3
test "$(grep -c 'group: production-deploy' "$PRODUCTION_WORKFLOW")" -eq 1
production_deploy_header=$(sed -n '/^  deploy-production:/,/^    env:/p' "$PRODUCTION_WORKFLOW")
printf '%s\n' "$production_deploy_header" | grep -Fq 'concurrency:'
printf '%s\n' "$production_deploy_header" | grep -Fq 'cancel-in-progress: false'
if grep -Fq 'workflow_call:' "$STAGING_WORKFLOW" "$PRODUCTION_WORKFLOW" || \
   grep -Fq 'uses: ./.github/workflows/' "$MAIN_WORKFLOW"; then
  echo "Environment deployment jobs must run directly, not behind workflow_call" >&2
  exit 1
fi

if grep -Fq 'secrets: inherit' "$MAIN_WORKFLOW" "$STAGING_WORKFLOW" "$PRODUCTION_WORKFLOW"; then
  echo "Deployment workflows must not inherit repository secrets" >&2
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
  grep -Fq "steps.mutation-start.outputs.attempted == 'true'" "$workflow"
  grep -Fq 'id: mutation-start' "$workflow"
  grep -Fq "AUTO_ROLLBACK_ENABLED:" "$workflow"
  grep -Fq 'release-record-${{ github.run_id }}' "$workflow"
  if grep -E 'secret_names=.*SMOKE_PASSWORD' "$workflow" >/dev/null; then
    echo "Smoke account passwords must never be persisted in Kubernetes application secrets" >&2
    exit 1
  fi
done

grep -Fq -- '--allow-empty' "$STAGING_WORKFLOW"
grep -Fq "steps.snapshot.outputs.previous_available == 'true'" "$STAGING_WORKFLOW"
app_secret_restore_step=$(sed -n '/name: Restore the previous staging application Secret/,/name: Roll back failed staging candidate/p' "$STAGING_WORKFLOW")
printf '%s\n' "$app_secret_restore_step" | grep -Fq "steps.snapshot.outputs.previous_available == 'true'"
if printf '%s\n' "$app_secret_restore_step" | grep -Fq "steps.snapshot.outputs.previous_available != 'true'"; then
  echo 'A failed first staging release must keep the initialized data-service Secret' >&2
  exit 1
fi
grep -Fq 'name: Remove a failed first staging application candidate' "$STAGING_WORKFLOW"
grep -Fq 'kubectl delete -f "$RELEASE_MANIFEST" --ignore-not-found=true --wait=true' "$STAGING_WORKFLOW"
if grep -Fq -- '--allow-empty' "$PRODUCTION_WORKFLOW"; then
  echo 'Production must never accept an empty previous release boundary' >&2
  exit 1
fi

for key in SMOKE_LOGIN SMOKE_TURNSTILE_BYPASS_KEY; do
  grep -Fq "key: $key" "$REPO_ROOT/k8s/auth-deploy.yaml" || {
    echo "Auth deployment is missing mandatory $key injection" >&2
    exit 1
  }
done

grep -Fq 'RUN_STAGING_ROLLBACK_DRILL' "$ROLLBACK_DRILL_WORKFLOW"
grep -Fq 'name: staging' "$ROLLBACK_DRILL_WORKFLOW"
grep -Fq 'version: v1.36.0' "$ROLLBACK_DRILL_WORKFLOW"
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
NAMESPACE_MANIFEST="$REPO_ROOT/k8s/staging/namespace.yaml"
grep -Fq 'pod-security.kubernetes.io/enforce: baseline' "$NAMESPACE_MANIFEST"
grep -Fq 'pod-security.kubernetes.io/audit: restricted' "$NAMESPACE_MANIFEST"
grep -Fq 'pod-security.kubernetes.io/warn: restricted' "$NAMESPACE_MANIFEST"
grep -Fq 'assert_pod_security_rejects privileged' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'assert_pod_security_rejects hostPath' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'assert_pod_security_rejects hostNetwork' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
test "$(grep -c '^kind: Pod$' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh")" -eq 4
grep -Fq 'assert_limit_range_rejects excessive-ephemeral-storage' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'maximum ephemeral-storage usage per Container is 4Gi' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
if grep -Fq 'assert_admission_rejects excessive-ephemeral-storage' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"; then
  echo 'LimitRange must be verified against Pod admission, not a controller template' >&2
  exit 1
fi
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
grep -Fq 'The Mnema staging application Secret must not request a ServiceAccount token' \
  "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
if grep -Fq '"type":"kubernetes.io/service-account-token"' \
  "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"; then
  echo 'Secret boundary probe must not be preempted by immutable-type validation' >&2
  exit 1
fi
grep -Fq "mnema.app/bootstrap-state: uninitialized" "$BOOTSTRAP"
grep -Fq "mnema.app/bootstrap-state: initialized" "$STAGING_WORKFLOW"
grep -Fq "oldObject.metadata.annotations['mnema.app/bootstrap-state']" "$ADMISSION"
grep -Fq 'resourceNames: ["mnema-secrets"]' "$BOOTSTRAP"
grep -Fq 'retry() {' "$STAGING_BUCKET_JOB"
grep -Fq "if [ \"\$attempts\" -ge 30 ]; then" "$STAGING_BUCKET_JOB"
grep -Fq 'sleep 2' "$STAGING_BUCKET_JOB"
grep -Fq 'retry mc alias set staging http://minio:9000' "$STAGING_BUCKET_JOB"
grep -Fq "retry mc mb --ignore-existing \"staging/\$AWS_BUCKET_NAME\"" "$STAGING_BUCKET_JOB"
"$SCRIPT_DIR/test-staging-minio-bucket-retry.sh"
if grep -Fq 'resources: ["configmaps", "secrets", "services"]' "$BOOTSTRAP"; then
  echo 'Scoped staging CI must not have unrestricted Secret CRUD' >&2
  exit 1
fi
grep -Fq 'kind: NetworkPolicy' "$BOOTSTRAP"
grep -Fq 'name: mnema-staging-default-deny' "$BOOTSTRAP"
grep -Fq 'name: mnema-staging-allowed-traffic' "$BOOTSTRAP"
grep -Fq '169.254.0.0/16' "$BOOTSTRAP"
grep -Fq 'fe80::/10' "$BOOTSTRAP"
if grep -Eq '^[[:space:]]+- ::ffff:' "$BOOTSTRAP"; then
  echo 'Kubernetes rejects IPv4-mapped CIDRs inside an IPv6 NetworkPolicy ipBlock' >&2
  exit 1
fi
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
deployment_manifests="frontend auth user core media import"
deployment_count=0
for service in $deployment_manifests; do
  manifest="$REPO_ROOT/k8s/${service}-deploy.yaml"
  grep -Fq 'revisionHistoryLimit: 2' "$manifest"
  deployment_count=$((deployment_count + 1))
done
replicaset_quota=$(awk '$1 == "count/replicasets.apps:" {gsub(/"/, "", $2); print $2; exit}' "$BOOTSTRAP")
maximum_retained_replicasets=$((deployment_count * 3))
if [ -z "$replicaset_quota" ] || \
  [ "$replicaset_quota" -lt $((maximum_retained_replicasets + deployment_count * 3)) ]; then
  echo 'ReplicaSet quota must fit retained revisions, two template changes, and one recovery revision per Deployment' >&2
  exit 1
fi
grep -Fq 'verify-staging-network-boundary.sh' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'reconcile-staging-host-firewall.sh' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'verify-staging-tls-boundary.sh' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'verify-production-telemetry-boundary.py' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'ExecStartPost=/usr/local/libexec/mnema/verify-production-telemetry-boundary.py' \
  "$REPO_ROOT/deploy/systemd/mnema-staging-host-boundary.service"
grep -Fq 'mnema-staging-host-boundary.service' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'mnema-staging-host-boundary.timer' "$REPO_ROOT/scripts/create-staging-kubeconfig.sh"
grep -Fq 'OnUnitActiveSec=1min' "$REPO_ROOT/deploy/systemd/mnema-staging-host-boundary.timer"
grep -Fq 'serviceType: ClusterIP' "$REPO_ROOT/k8s/cluster-issuers.yaml"
grep -Fq 'staging.mnema.app' "$REPO_ROOT/k8s/cluster-issuers.yaml"
grep -Fq 'count/secrets: "12"' "$BOOTSTRAP"
grep -Fq './scripts/verify-kubernetes-bootstrap-secret-values.py' "$STAGING_WORKFLOW"
grep -Fq 'MEDIA_INTERNAL_TOKEN CORE_INTERNAL_TOKEN USER_INTERNAL_TOKEN >/dev/null' "$STAGING_WORKFLOW"
preview_plan_line=$(grep -n 'name: Preview complete staging plan before mutation' "$STAGING_WORKFLOW" | cut -d: -f1)
final_stale_line=$(grep -n 'name: Reject a stale release immediately before staging mutation' "$STAGING_WORKFLOW" | cut -d: -f1)
first_staging_mutation_line=$(grep -n 'name: Apply staged application Secret' "$STAGING_WORKFLOW" | cut -d: -f1)
if [ -z "$preview_plan_line" ] || [ -z "$final_stale_line" ] || \
   [ -z "$first_staging_mutation_line" ] || \
   [ "$preview_plan_line" -ge "$final_stale_line" ] || \
   [ "$final_stale_line" -ge "$first_staging_mutation_line" ]; then
  echo 'The complete staging plan and final stale guard must precede every mutation' >&2
  exit 1
fi
staging_prefix=$(sed -n "1,${first_staging_mutation_line}p" "$STAGING_WORKFLOW")
if printf '%s\n' "$staging_prefix" | grep -Eq 'kubectl (apply|delete)|kubectl .*rollout restart'; then
  echo 'Staging must not mutate before the complete plan preview and final stale guard' >&2
  exit 1
fi
staging_reconcile_step=$(sed -n '/name: Reconcile staging application Secret consumers/,/name: Verify staging service rollouts/p' "$STAGING_WORKFLOW")
printf '%s\n' "$staging_reconcile_step" | grep -Fq './scripts/reconcile-kubernetes-secret-consumers.sh'
if printf '%s\n' "$staging_reconcile_step" | grep -Fq 'if: steps.secret-preview.outputs.app_secret_drift'; then
  echo 'Secret consumers must reconcile on retries even when desired Secret drift is now empty' >&2
  exit 1
fi
for consumer in mnema-auth mnema-user mnema-core mnema-media mnema-import; do
  printf '%s\n' "$staging_reconcile_step" | grep -Fq "$consumer"
done
if printf '%s\n' "$staging_reconcile_step" | grep -Fq 'mnema-frontend'; then
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
