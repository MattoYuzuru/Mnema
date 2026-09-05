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
ROUTE_ADMISSION="$REPO_ROOT/k8s/staging/application-route-boundary.yaml"
ROUTE_ACCESS="$REPO_ROOT/k8s/staging/application-route-access.yaml"
STAGING_DATA="$REPO_ROOT/k8s/staging/data.yaml"
STAGING_BUCKET_JOB="$REPO_ROOT/k8s/staging/minio-bucket-job.yaml"
STAGING_ROUTES="$REPO_ROOT/k8s/staging/routes.yaml"
ALLOY_CONFIG="$REPO_ROOT/k8s/observability/30-alloy-config.yaml"
IDENTITY_TEMPLATE="$REPO_ROOT/k8s/identity-account-deploy.yaml"
IDENTITY_PROPERTIES="$REPO_ROOT/backend/services/identity-account/src/main/resources/application.properties"
STAGING_RUNBOOK="$REPO_ROOT/docs/operations/staging-runbook.md"

grep -Eq '^[[:space:]]*targets[[:space:]]*=[[:space:]]*discovery\.relabel\.pods\.output[[:space:]]*$' \
  "$ALLOY_CONFIG"
if grep -Eq 'discovery\.relabel\.pods\.targets' "$ALLOY_CONFIG"; then
  echo 'Alloy must consume the discovery.relabel output export' >&2
  exit 1
fi

grep -Fq 'version: v1.35.8' "$STAGING_WORKFLOW"
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

for workflow in "$PRODUCTION_WORKFLOW"; do
  grep -Fq './scripts/preserve-kubernetes-secret.py snapshot' "$workflow"
  grep -Fq './scripts/preserve-kubernetes-secret.py restore' "$workflow"
  grep -Fq 'id: app-secret-restore' "$workflow"
  grep -Fq "steps.app-secret-restore.outcome == 'success'" "$workflow"
  grep -Fq 'SMOKE_LOGIN,SMOKE_TURNSTILE_BYPASS_KEY' "$workflow"
done
staging_rollback_smoke=$(sed -n '/name: Verify complete staging rollback/,/name: Upload staging failure evidence/p' "$STAGING_WORKFLOW")
printf '%s\n' "$staging_rollback_smoke" | grep -Fq 'SMOKE_PASSWORD: ${{ secrets.STAGING_SMOKE_PASSWORD }}'
if printf '%s\n' "$staging_rollback_smoke" | grep -Fq -- '--identity-only'; then
  echo 'Staging rollback must pass the complete authenticated smoke' >&2
  exit 1
fi
legacy_staging_rollback_smoke=$(sed -n '/name: Verify adopted legacy staging rollback identity/,/name: Verify complete staging rollback/p' "$STAGING_WORKFLOW")
maintenance_staging_rollback_smoke=$(sed -n '/name: Verify maintenance staging rollback/,/name: Verify previous maintenance staging rollback/p' "$STAGING_WORKFLOW")
previous_maintenance_staging_rollback_smoke=$(sed -n '/name: Verify previous maintenance staging rollback/,/name: Verify adopted legacy staging rollback identity/p' "$STAGING_WORKFLOW")
printf '%s\n' "$maintenance_staging_rollback_smoke" | grep -Fq "steps.rollback.outputs.maintenance_smoke_supported == 'true'"
if printf '%s\n' "$maintenance_staging_rollback_smoke" | grep -Fq -- '--skip-identity-protocol'; then
  echo 'Current maintenance rollback must verify the complete protocol contract' >&2
  exit 1
fi
printf '%s\n' "$previous_maintenance_staging_rollback_smoke" | grep -Fq "steps.rollback.outputs.maintenance_smoke_supported != 'true'"
printf '%s\n' "$previous_maintenance_staging_rollback_smoke" | grep -Fq -- '--skip-identity-protocol'
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
printf '%s\n' "$production_gate" | grep -Fq 'Block production promotion until issue 147'
if printf '%s\n%s\n' "$staging_gate" "$production_gate" | grep -Fq 'environment:'; then
  echo 'Untrusted predecessor and artifact validation must run before Environment access' >&2
  exit 1
fi
printf '%s\n' "$staging_deploy_header" | grep -Fq 'needs: validate-main-ci'
printf '%s\n' "$production_preview_header" | grep -Fq 'needs: validate-staging-deploy'
if printf '%s\n' "$staging_deploy_header" | grep -Eq '^    if:'; then
  echo 'Environment jobs must not turn a rejected predecessor into a successful skipped workflow' >&2
  exit 1
fi
grep -Fq 'RELEASE_SHA: ${{ github.event.workflow_run.head_sha }}' "$STAGING_WORKFLOW"
test "$(grep -c 'RELEASE_SHA: ${{ github.event.workflow_run.head_sha }}' "$PRODUCTION_WORKFLOW")" -eq 3
test "$(grep -c 'run-id: ${{ github.event.workflow_run.id }}' "$STAGING_WORKFLOW")" -eq 4
test "$(grep -c 'run-id: ${{ github.event.workflow_run.id }}' "$PRODUCTION_WORKFLOW")" -eq 2
test "$(grep -c 'github-token: ${{ github.token }}' "$STAGING_WORKFLOW")" -eq 4
test "$(grep -c 'github-token: ${{ github.token }}' "$PRODUCTION_WORKFLOW")" -eq 2
grep -Fq 'Staging release artifact does not match the tested revision' "$STAGING_WORKFLOW"
grep -Fq "production_eligible: 'false'" "$PRODUCTION_WORKFLOW"
grep -Fq "needs.validate-staging-deploy.outputs.production_eligible == 'true'" "$PRODUCTION_WORKFLOW"
if grep -Fq 'policy-id: staging-production-promotion' "$STAGING_WORKFLOW"; then
  echo 'Maintenance staging must not publish a production promotion artifact' >&2
  exit 1
fi
grep -Fq '${{ runner.temp }}/production-promotion/production-release.yaml' "$STAGING_WORKFLOW"
grep -Fq -- '--environment production-promotion' "$STAGING_WORKFLOW"
grep -Fq 'Production promotion manifest does not match the tested revision' "$STAGING_WORKFLOW"
test "$(grep -c 'Production release artifact does not match the staging-approved revision' "$PRODUCTION_WORKFLOW")" -eq 2
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

# New runtime configuration cannot depend on legacy service credentials.
for service in identity-account learning; do
  template="$REPO_ROOT/k8s/${service}-deploy.yaml"
  if grep -Eq 'INTERNAL_TOKEN|TURNSTILE|USER_BASE_URL|AUTH_JWT_' "$template"; then
    echo 'Maintenance shells must not consume legacy application credentials' >&2
    exit 1
  fi
done

for value in \
  MNEMA_IDENTITY_SIGNING_JWK_SET_FILE IDENTITY_SIGNING_ACTIVE_KID \
  MNEMA_IDENTITY_FRONTEND_ORIGIN MNEMA_IDENTITY_REDIRECT_URI \
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID \
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID \
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_ID \
  MNEMA_AVATAR_BUCKET MNEMA_AVATAR_ALLOW_STAGING_MINIO_HTTP \
  MNEMA_IDENTITY_TRUSTED_PROXY_CIDRS
do
  grep -Fq "$value" "$IDENTITY_TEMPLATE"
done
grep -Fq 'key: IDENTITY_SIGNING_JWK_SET' "$IDENTITY_TEMPLATE"
grep -Fq 'mountPath: /var/run/secrets/mnema-identity' "$IDENTITY_TEMPLATE"
grep -Fq 'identity.avatar.allow-staging-minio-http=${MNEMA_AVATAR_ALLOW_STAGING_MINIO_HTTP:false}' \
  "$IDENTITY_PROPERTIES"
for value in IDENTITY_SIGNING_JWK_SET IDENTITY_SIGNING_ACTIVE_KID POSTBOX_ACCESS_KEY POSTBOX_SECRET_KEY; do
  test "$(grep -c "'$value'" "$ADMISSION")" -eq 2
done
for identity_policy_guard in \
  'select(.kind == "ValidatingAdmissionPolicy" and .metadata.name == "mnema-staging-secret-boundary")' \
  'select(.kind == "ValidatingAdmissionPolicyBinding" and .metadata.name == "mnema-staging-secret-boundary")' \
  'kubectl apply --dry-run=server -f "$identity_policy"' \
  'kubectl diff -f "$identity_policy"' \
  'kubectl diff -f "$identity_binding"' \
  '.status.observedGeneration == .metadata.generation' \
  '.status.typeChecking.expressionWarnings' \
  '.spec.policyName == "mnema-staging-secret-boundary"' \
  '.spec.validationActions == ["Deny"]' \
  '.spec.matchResources.namespaceSelector.matchLabels["mnema.app/environment"] == "staging"'
do
  grep -Fq "$identity_policy_guard" "$STAGING_RUNBOOK"
done
grep -Fq 'STAGING_POSTBOX_ACCESS_KEY' "$STAGING_RUNBOOK"
grep -Fq 'live outbound delivery is not claimed by the maintenance smoke' "$STAGING_RUNBOOK"
grep -Fq 'path: /' "$REPO_ROOT/k8s/auth-ingress.yaml"
if grep -R -E 'UserApiClient|USER_BASE_URL|app\.user\.base-url' \
  "$REPO_ROOT/backend/services/core/src/main" >/dev/null; then
  echo 'Production sources must not call the deleted standalone user runtime' >&2
  exit 1
fi

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

grep -Fq "steps.snapshot.outputs.previous_available == 'true'" "$STAGING_WORKFLOW"
grep -Fq -- '--candidate-manifest "$RELEASE_MANIFEST"' "$STAGING_WORKFLOW"
grep -Fq -- '--replacement' "$STAGING_WORKFLOW"
if grep -Eq 'preserve-kubernetes-secret|secret_names=|STAGING_SECRET_MANIFEST|delete -f' "$STAGING_WORKFLOW"; then
  echo 'Replacement staging must leave Secrets/data untouched and use exact application cleanup' >&2
  exit 1
fi
if grep -Fq -- '--allow-empty' "$PRODUCTION_WORKFLOW"; then
  echo 'Production must never accept an empty previous release boundary' >&2
  exit 1
fi

grep -Fq -- '--mode maintenance' "$ROLLBACK_DRILL_WORKFLOW"
grep -Fq 'deployment/mnema-identity-account --timeout=180s' "$ROLLBACK_DRILL_WORKFLOW"
rollback_drill_smoke=$(sed -n '/name: Verify complete rollback release/,/name: Upload complete rollback drill evidence/p' "$ROLLBACK_DRILL_WORKFLOW")
printf '%s\n' "$rollback_drill_smoke" | grep -Fq 'steps.rollback.outputs.maintenance_smoke_supported'
printf '%s\n' "$rollback_drill_smoke" | grep -Fq -- '--skip-identity-protocol'
printf '%s\n' "$rollback_drill_smoke" | grep -Fq "true) set --"
printf '%s\n' "$rollback_drill_smoke" | grep -Fq "false) set -- --skip-identity-protocol"

grep -Fq 'RUN_STAGING_ROLLBACK_DRILL' "$ROLLBACK_DRILL_WORKFLOW"
grep -Fq 'name: staging' "$ROLLBACK_DRILL_WORKFLOW"
grep -Fq 'version: v1.35.8' "$ROLLBACK_DRILL_WORKFLOW"
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
grep -Fq 'mnema.app/bootstrap-state' "$STAGING_WORKFLOW"
grep -Fq '== "initialized"' "$STAGING_WORKFLOW"
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
deployment_manifests="identity-account learning"
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
preview_plan_line=$(grep -n 'name: Preview complete staging plan before mutation' "$STAGING_WORKFLOW" | cut -d: -f1)
secret_preview_line=$(grep -n 'name: Preview immutable staging Identity signing bootstrap' "$STAGING_WORKFLOW" | cut -d: -f1)
final_stale_line=$(grep -n 'name: Reject a stale release immediately before staging mutation' "$STAGING_WORKFLOW" | cut -d: -f1)
first_staging_mutation_line=$(grep -n 'name: Bootstrap immutable staging Identity signing secret' "$STAGING_WORKFLOW" | cut -d: -f1)
if [ -z "$preview_plan_line" ] || [ -z "$secret_preview_line" ] || [ -z "$final_stale_line" ] || \
   [ -z "$first_staging_mutation_line" ] || \
   [ "$preview_plan_line" -ge "$secret_preview_line" ] || \
   [ "$secret_preview_line" -ge "$final_stale_line" ] || \
   [ "$final_stale_line" -ge "$first_staging_mutation_line" ]; then
  echo 'The complete staging and signing previews plus final stale guard must precede every mutation' >&2
  exit 1
fi
staging_prefix=$(sed -n "1,${first_staging_mutation_line}p" "$STAGING_WORKFLOW")
if printf '%s\n' "$staging_prefix" | grep -Eq 'kubectl (apply|delete)|kubectl .*rollout restart'; then
  echo 'Staging must not mutate before the complete plan preview and final stale guard' >&2
  exit 1
fi
grep -Fq 'resourceNames: ["mnema", "mnema-auth"]' "$ROUTE_ACCESS"
grep -Fq 'verbs: ["get", "patch", "update"]' "$ROUTE_ACCESS"
grep -Fq "object.metadata.name in ['mnema', 'mnema-auth']" "$ROUTE_ADMISSION"
grep -Fq 'failurePolicy: Fail' "$ROUTE_ADMISSION"
grep -Fq 'validationActions: [Deny]' "$ROUTE_ADMISSION"
grep -Fq 'Application routes must retain their single exact staging host' "$ROUTE_ADMISSION"
grep -Fq 'Application TLS hosts and Secrets are fixed' "$ROUTE_ADMISSION"
grep -Fq 'Application paths and backends must match one complete allowed topology' "$ROUTE_ADMISSION"
if grep -Fq 'ingresses' "$BOOTSTRAP"; then
  echo 'Route access must be granted separately after the admission probes pass' >&2
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
python3 - "$STAGING_ROUTES" <<'PY_STAGING_ROUTES'
from pathlib import Path
import sys

documents = Path(sys.argv[1]).read_text().split("\n---\n")
learning = next(document for document in documents if "name: mnema\n" in document)
identity = next(document for document in documents if "name: mnema-auth\n" in document)
assert "          - path: /api\n" in learning
assert "name: mnema-learning" in learning
assert "          - path: /\n" in identity
assert "          - path: /api\n" not in identity
assert "name: mnema-identity-account" in identity
PY_STAGING_ROUTES

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

# Validate the executable local contract without starting Docker or reading .env.
python3 - "$REPO_ROOT" <<'PY_LOCAL_COMPOSE'
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

root = Path(sys.argv[1])
compose = root / "docker-compose.yml"
command = ["docker", "compose", "--env-file", os.devnull, "-f", str(compose),
           "config", "--format", "json"]
environment = {key: value for key, value in os.environ.items()
               if not key.startswith(("MNEMA_LOCAL_", "COMPOSE_"))}
# Old credentials must never satisfy the replacement password requirement.
environment.update(POSTGRES_PASSWORD="legacy-test-value", POSTGRES_USER="legacy-user",
                   POSTGRES_DB="legacy-db")
missing = subprocess.run(command, env=environment, capture_output=True, text=True, check=False)
assert missing.returncode != 0 and "MNEMA_LOCAL_POSTGRES_PASSWORD" in missing.stderr

environment["MNEMA_LOCAL_POSTGRES_PASSWORD"] = "replacement-test-value"
environment["MNEMA_LOCAL_IDENTITY_SIGNING_ACTIVE_KID"] = "local-test-kid"
environment["MNEMA_LOCAL_IDENTITY_SIGNING_JWK_SET_FILE"] = os.devnull
result = subprocess.run(command, env=environment, capture_output=True, text=True, check=False)
assert result.returncode == 0, "Replacement Compose config must render successfully"
config = json.loads(result.stdout)
assert config["name"] == "mnema-replacement"
assert set(config["services"]) == {"postgres", "identity-account", "learning"}
assert set(config["volumes"]) == {"replacement_postgres_data"}
assert config["volumes"]["replacement_postgres_data"]["name"] == "mnema-replacement_replacement_postgres_data"
postgres = config["services"]["postgres"]
assert postgres["environment"]["POSTGRES_DB"] == "mnema"
assert postgres["environment"]["POSTGRES_USER"] == "mnema"
assert postgres["environment"]["POSTGRES_PASSWORD"] == "replacement-test-value"
assert postgres["volumes"][0]["source"] == "replacement_postgres_data"
assert postgres["volumes"][0]["target"] == "/var/lib/postgresql"
staging_postgres = re.search(r"image: (postgres:18@sha256:[0-9a-f]{64})",
                             (root / "k8s/staging/data.yaml").read_text()).group(1)
assert postgres["image"] == staging_postgres
for service in config["services"].values():
    assert all(port["host_ip"] == "127.0.0.1" for port in service.get("ports", []))
    assert "env_file" not in service
stages = set(re.findall(r"(?im)^FROM .* AS ([a-z0-9-]+)$",
                        (root / "backend/Dockerfile").read_text()))
for name in ("identity-account", "learning"):
    service = config["services"][name]
    assert service["build"]["target"] == name + "-runtime"
    assert service["build"]["target"] in stages
    assert service["environment"]["SPRING_DATASOURCE_PASSWORD"] == "replacement-test-value"
assert config["services"]["identity-account"]["environment"]["MNEMA_IDENTITY_ISSUER"] == "https://localhost:18081"
identity_mount = config["services"]["identity-account"]["volumes"][0]
assert identity_mount["source"] == os.devnull
assert identity_mount["target"] == "/var/run/secrets/mnema-identity/identity-signing-jwk-set.json"
assert identity_mount["read_only"] is True
source = compose.read_text()
assert all(name.startswith("MNEMA_LOCAL_") for name in re.findall(r"\$\{([A-Z_]+)", source))

# Execute retired Bash entrypoints only in a disposable tree. Any old mkdir or
# Docker call fails the test; no existing local stack or config can be touched.
with tempfile.TemporaryDirectory() as directory:
    sandbox = Path(directory)
    (sandbox / "scripts").mkdir()
    (sandbox / "bin").mkdir()
    docker = sandbox / "bin/docker"
    docker.write_text("#!/bin/sh\nprintf 'called' > \"$MNEMA_TEST_DOCKER_MARKER\"\nexit 1\n")
    docker.chmod(0o700)
    for name in ("mnema-local", "mnema-public"):
        script = sandbox / "scripts" / (name + ".sh")
        shutil.copy2(root / "scripts" / script.name, script)
        child_env = {**environment, "PATH": str(sandbox / "bin") + os.pathsep + os.environ["PATH"],
                     "MNEMA_TEST_DOCKER_MARKER": str(sandbox / "docker-called")}
        refused = subprocess.run(["bash", str(script)], cwd=sandbox, env=child_env,
                                 capture_output=True, text=True, timeout=10, check=False)
        assert refused.returncode == 64 and "retired" in refused.stderr
        assert not (sandbox / ".mnema").exists()
        assert not (sandbox / "docker-called").exists()
        # PowerShell may not be installed on CI; its refusal must precede all
        # legacy path resolution, file creation and stack-stop commands.
        powershell = (root / "scripts" / (name + ".ps1")).read_text()
        prefix = powershell.split("exit 64", 1)[0]
        assert "[Console]::Error.WriteLine(" in prefix and "retired" in prefix
        assert len(prefix.splitlines()) == 2
print("local_replacement_contract=ok")
PY_LOCAL_COMPOSE

printf 'deployment_contract=ok\n'
