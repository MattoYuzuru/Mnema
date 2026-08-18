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

preview_job=$(sed -n '/^  preview-production:/,/^  deploy-production:/p' "$DEPLOY")
if printf '%s\n' "$preview_job" | grep -Eq 'kubectl (apply|delete)|kubectl .*rollout restart'; then
  echo 'The review-only production preview job must not mutate the cluster' >&2
  exit 1
fi

drift_guard_line=$(grep -n 'name: Reject release or cluster drift after approval' "$DEPLOY" | cut -d: -f1)
first_mutation_line=$(grep -n -m1 'name: Ensure namespace exists' "$DEPLOY" | cut -d: -f1)
if [ -z "$drift_guard_line" ] || [ -z "$first_mutation_line" ] || \
   [ "$drift_guard_line" -ge "$first_mutation_line" ]; then
  echo 'The approved diff must be revalidated before any production mutation' >&2
  exit 1
fi

printf 'production_deploy_safety=ok\n'
