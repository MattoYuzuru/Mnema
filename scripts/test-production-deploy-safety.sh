#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
CALLER="$REPO_ROOT/.github/workflows/deploy.yaml"
DEPLOY="$REPO_ROOT/.github/workflows/production-deploy.yaml"

grep -Fq 'group: production-deploy' "$CALLER"
grep -Fq 'cancel-in-progress: false' "$CALLER"
grep -Fq 'name: prod' "$DEPLOY"
grep -Fq 'deployment: false' "$DEPLOY"
grep -Fq 'needs: preview-production' "$DEPLOY"
grep -Fq "if: needs.preview-production.outputs.has_release_changes == 'true'" "$DEPLOY"
# shellcheck disable=SC2016 # GitHub expressions are literal contract markers.
grep -Fq 'production-release-preview-${{ github.run_id }}-${{ github.run_attempt }}' "$DEPLOY"
grep -Fq 'approved_diff_sha256=' "$DEPLOY"
grep -Fq 'needs.preview-production.outputs.release_diff_sha256' "$DEPLOY"
grep -Fq 'canonical-kubectl-diff.sh' "$REPO_ROOT/scripts/capture-release-diff.sh"
# shellcheck disable=SC2016 # GitHub expressions are literal contract markers.
grep -Fq 'artifact-ids: ${{ needs.preview-production.outputs.preview_artifact_id }}' "$DEPLOY"
grep -Fq 'digest-mismatch: error' "$DEPLOY"
grep -Fq './scripts/verify-release-preview.sh' "$DEPLOY"
test "$(grep -c './scripts/detect-kubernetes-secret-drift.py' "$DEPLOY")" -eq 4
# shellcheck disable=SC2016 # GitHub/shell expressions are literal contract markers.
grep -Fq 'secret_drift: ${{ steps.secret-preview.outputs.secret_drift }}' "$DEPLOY"
# shellcheck disable=SC2016 # GitHub expression is a literal contract marker.
grep -Fq 'secret_snapshot_hmac: ${{ steps.secret-preview.outputs.secret_snapshot_hmac }}' "$DEPLOY"
grep -Fq 'live_secret_snapshot_hmac: ${{ steps.secret-preview.outputs.live_secret_snapshot_hmac }}' "$DEPLOY"
grep -Fq 'app_secret_generation: ${{ steps.secret-preview.outputs.app_secret_generation }}' "$DEPLOY"
grep -Fq 'grafana_secret_generation: ${{ steps.secret-preview.outputs.grafana_secret_generation }}' "$DEPLOY"
# shellcheck disable=SC2016 # Workflow variables are literal contract markers.
grep -Fq 'if [ "$application_release_changes" = true ] || [ "$SECRET_DRIFT" = true ]; then' "$DEPLOY"
# shellcheck disable=SC2016 # Workflow variables are literal contract markers.
grep -Fq 'secret_drift=${SECRET_DRIFT}' "$DEPLOY"
grep -Fq 'Production Secret drift state changed after approval' "$DEPLOY"
grep -Fq 'Desired production Secret snapshot changed after approval' "$DEPLOY"
grep -Fq 'Live production Secret or reconciliation state changed after approval' "$DEPLOY"
grep -Fq 'run: ./scripts/test-secret-snapshot-binding.sh' "$REPO_ROOT/.github/workflows/pull-request.yaml"
grep -Fq 'run: ./scripts/test-secret-snapshot-binding.sh' "$CALLER"
grep -Fq 'run: ./scripts/test-kubernetes-bootstrap-secret-values.sh' "$REPO_ROOT/.github/workflows/pull-request.yaml"
grep -Fq 'run: ./scripts/test-kubernetes-bootstrap-secret-values.sh' "$CALLER"
grep -Fq 'run: ./scripts/test-detect-kubernetes-secret-drift.sh' "$REPO_ROOT/.github/workflows/pull-request.yaml"
grep -Fq 'run: ./scripts/test-detect-kubernetes-secret-drift.sh' "$CALLER"
grep -Fq 'run: ./scripts/test-kubernetes-live-release-binding.sh' "$REPO_ROOT/.github/workflows/pull-request.yaml"
grep -Fq 'run: ./scripts/test-kubernetes-live-release-binding.sh' "$CALLER"
# shellcheck disable=SC2016 # GitHub expressions are literal contract markers.
grep -Fq 'PROD_KUBECONFIG_B64: ${{ secrets.PROD_KUBECONFIG_B64 }}' "$DEPLOY"

if grep -Eq 'secrets\.KUBECONFIG_B64([^A-Z0-9_]|$)' "$CALLER" "$DEPLOY"; then
  echo 'Legacy repository-scoped KUBECONFIG_B64 must not authorize deployment workflows' >&2
  exit 1
fi

kubectl_version_count=$(grep -c 'version: v1.36.0' "$DEPLOY")
deployment_record_count=$(grep -c 'deployment: true' "$DEPLOY")
preview_only_count=$(grep -c 'deployment: false' "$DEPLOY")
if [ "$kubectl_version_count" -ne 2 ] || [ "$deployment_record_count" -ne 1 ] || \
   [ "$preview_only_count" -ne 1 ]; then
  echo 'Both jobs must use one pinned kubectl version and only the mutating job may create the production record' >&2
  exit 1
fi

guard_count=$(grep -c 'name: Reject a stale release' "$DEPLOY")
kubeconfig_count=$(grep -c 'PROD_KUBECONFIG_B64:.*secrets.PROD_KUBECONFIG_B64' "$DEPLOY")
if [ "$guard_count" -ne 2 ] || [ "$kubeconfig_count" -ne 2 ]; then
  echo 'Both production jobs must reject stale releases before reading prod credentials' >&2
  exit 1
fi

awk '
  /name: Reject a stale release/ { guarded = 1 }
  /PROD_KUBECONFIG_B64:.*secrets.PROD_KUBECONFIG_B64/ {
    if (!guarded) {
      exit 1
    }
    guarded = 0
  }
  END {
    if (guarded) {
      exit 1
    }
  }
' "$DEPLOY" || {
  echo 'The stale-release guard must precede prod credential access in each job' >&2
  exit 1
}

production_secret_names='AUTH_ISSUER AUTH_ISSUER_URI AUTH_JWT_PUBLIC_KEY AUTH_JWT_PRIVATE_KEY TURNSTILE_SITE_KEY TURNSTILE_SECRET_KEY GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET GITHUB_CLIENT_ID GITHUB_CLIENT_SECRET YANDEX_CLIENT_ID YANDEX_CLIENT_SECRET POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD AWS_REGION AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_BUCKET_NAME MEDIA_INTERNAL_TOKEN CORE_INTERNAL_TOKEN USER_INTERNAL_TOKEN GF_SECURITY_ADMIN_USER GF_SECURITY_ADMIN_PASSWORD RELEASE_BINDING_KEY'
validation_count=$(grep -c 'name: Validate required production secrets' "$DEPLOY")
required_list_count=$(grep -F -c "required_names=\"$production_secret_names\"" "$DEPLOY")
issuer_check_count=$(grep -F -c 'Production issuer secrets must identify auth.mnema.app' "$DEPLOY")
database_check_count=$(grep -F -c 'PROD_POSTGRES_DB must match the release database contract' "$DEPLOY")
if [ "$validation_count" -ne 2 ] || [ "$required_list_count" -ne 2 ] || \
   [ "$issuer_check_count" -ne 2 ] || [ "$database_check_count" -ne 2 ]; then
  echo 'Both production jobs must validate every consumed secret and its semantic issuer/database contract' >&2
  exit 1
fi

first_validation_line=$(grep -n 'name: Validate required production secrets' "$DEPLOY" | head -n 1 | cut -d: -f1)
second_validation_line=$(grep -n 'name: Validate required production secrets' "$DEPLOY" | tail -n 1 | cut -d: -f1)
first_kubeconfig_line=$(grep -n 'name: Write kubeconfig (main cluster)' "$DEPLOY" | head -n 1 | cut -d: -f1)
second_kubeconfig_line=$(grep -n 'name: Write kubeconfig (main cluster)' "$DEPLOY" | tail -n 1 | cut -d: -f1)
first_mutation_line=$(grep -n -m1 'name: Ensure namespace exists' "$DEPLOY" | cut -d: -f1)
if [ "$first_validation_line" -ge "$first_kubeconfig_line" ] || \
   [ "$second_validation_line" -ge "$second_kubeconfig_line" ] || \
   [ "$second_validation_line" -ge "$first_mutation_line" ]; then
  echo 'Fresh production secret validation must precede credential access and every mutation in both jobs' >&2
  exit 1
fi

preview_job=$(sed -n '/^  preview-production:/,/^  deploy-production:/p' "$DEPLOY")
if printf '%s\n' "$preview_job" | grep -Eq 'kubectl (apply|delete)|kubectl .*rollout restart'; then
  echo 'The review-only production preview job must not mutate the cluster' >&2
  exit 1
fi

drift_guard_line=$(grep -n 'name: Reject release or cluster drift after approval' "$DEPLOY" | cut -d: -f1)
if [ -z "$drift_guard_line" ] || [ -z "$first_mutation_line" ] || \
   [ "$drift_guard_line" -ge "$first_mutation_line" ]; then
  echo 'The approved diff must be revalidated before any production mutation' >&2
  exit 1
fi
secret_preview_line=$(grep -n 'name: Detect production Secret drift without exposing values' "$DEPLOY" | cut -d: -f1)
if [ -z "$secret_preview_line" ] || [ "$secret_preview_line" -ge "$first_mutation_line" ]; then
  echo 'Secret-only drift must be detected before deciding whether to create a production deployment' >&2
  exit 1
fi

preview_reference_line=$(grep -n 'name: Validate approved preview artifact reference' "$DEPLOY" | cut -d: -f1)
preview_download_line=$(grep -n 'name: Download approved production preview by immutable ID' "$DEPLOY" | cut -d: -f1)
preview_verify_line=$(grep -n 'name: Verify approved production preview contents' "$DEPLOY" | cut -d: -f1)
kubeconfig_step_line=$(grep -n 'name: Write kubeconfig (main cluster)' "$DEPLOY" | tail -n 1 | cut -d: -f1)
if [ "$preview_reference_line" -ge "$kubeconfig_step_line" ] || \
   [ "$preview_download_line" -ge "$kubeconfig_step_line" ] || \
   [ "$preview_verify_line" -ge "$kubeconfig_step_line" ]; then
  echo 'Approved preview identity, availability and contents must be verified before prod credentials' >&2
  exit 1
fi

snapshot_guard_line=$(grep -n 'name: Verify approved desired Secret snapshot before cluster access' "$DEPLOY" | cut -d: -f1)
if [ -z "$snapshot_guard_line" ] || [ "$snapshot_guard_line" -ge "$kubeconfig_step_line" ] || \
   [ "$snapshot_guard_line" -ge "$first_mutation_line" ]; then
  echo 'The exact desired Secret snapshot must remain bound to the approval before cluster access' >&2
  exit 1
fi
test "$(grep -c './scripts/create-secret-snapshot-binding.py' "$DEPLOY")" -eq 6
test "$(grep -c './scripts/create-kubernetes-live-release-binding.py' "$DEPLOY")" -eq 2
test "$(grep -c './scripts/detect-kubernetes-reconciliation-drift.py' "$DEPLOY")" -eq 4
test "$(grep -c './scripts/replace-kubernetes-secret-if-current.py' "$DEPLOY")" -eq 1
test "$(grep -c './scripts/verify-kubernetes-bootstrap-secret-values.py' "$DEPLOY")" -eq 4
test "$(grep -c 'MEDIA_INTERNAL_TOKEN CORE_INTERNAL_TOKEN USER_INTERNAL_TOKEN >/dev/null' "$DEPLOY")" -eq 2

apply_release_line=$(grep -n 'name: Apply complete application release once' "$DEPLOY" | cut -d: -f1)
restart_consumers_line=$(grep -n 'name: Reconcile application Secret consumers' "$DEPLOY" | cut -d: -f1)
verify_rollouts_line=$(grep -n 'name: Verify service rollouts' "$DEPLOY" | cut -d: -f1)
verify_observability_line=$(grep -n 'name: Verify observability rollouts' "$DEPLOY" | cut -d: -f1)
record_reconciliation_line=$(grep -n 'name: Record successful Secret reconciliation generations' "$DEPLOY" | cut -d: -f1)
if [ "$apply_release_line" -ge "$restart_consumers_line" ] || \
   [ "$restart_consumers_line" -ge "$verify_rollouts_line" ] || \
   [ "$verify_rollouts_line" -ge "$verify_observability_line" ] || \
   [ "$verify_observability_line" -ge "$record_reconciliation_line" ]; then
  echo 'Secret reconciliation must be recorded only after every consumer rollout succeeds' >&2
  exit 1
fi
restart_step=$(sed -n '/name: Reconcile application Secret consumers/,/name: Verify service rollouts/p' "$DEPLOY")
printf '%s\n' "$restart_step" | grep -Fq "if: steps.drift-guard.outputs.app_reconciliation_drift == 'true'"
for consumer in mnema-auth mnema-user mnema-core mnema-media mnema-import; do
  printf '%s\n' "$restart_step" | grep -Fq "$consumer"
done
if printf '%s\n' "$restart_step" | grep -Fq 'deployment/mnema-frontend'; then
  echo 'Frontend must not restart for a Secret it does not consume' >&2
  exit 1
fi
grep -Fq 'resourceVersion: \"${RECONCILIATION_RESOURCE_VERSION}\"' "$DEPLOY"
grep -Fq 'steps.drift-guard.outputs.app_secret_resource_version' "$DEPLOY"
if grep -Fq 'steps.drift-guard.outputs.grafana_secret_resource_version' "$DEPLOY"; then
  echo 'Generic deploy must not pretend that a Grafana bootstrap password is rotatable' >&2
  exit 1
fi

grep -Fq 'run: ./scripts/test-capture-release-diff.sh' "$REPO_ROOT/.github/workflows/pull-request.yaml"
grep -Fq 'run: ./scripts/test-capture-release-diff.sh' "$CALLER"

printf 'production_deploy_safety=ok\n'
