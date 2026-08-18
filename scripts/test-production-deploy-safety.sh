#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
CALLER="$REPO_ROOT/.github/workflows/deploy.yaml"
DEPLOY="$REPO_ROOT/.github/workflows/production-deploy.yaml"

grep -Fq 'group: production-deploy' "$CALLER"
grep -Fq 'cancel-in-progress: false' "$CALLER"
grep -Fq 'name: prod' "$DEPLOY"
grep -Fq 'PROD_KUBECONFIG_B64: ${{ secrets.PROD_KUBECONFIG_B64 }}' "$DEPLOY"

if grep -Eq 'secrets\.KUBECONFIG_B64([^A-Z0-9_]|$)' "$CALLER" "$DEPLOY"; then
  echo 'Legacy repository-scoped KUBECONFIG_B64 must not authorize deployment workflows' >&2
  exit 1
fi

guard_line=$(grep -n 'name: Reject a stale release' "$DEPLOY" | cut -d: -f1)
kubeconfig_line=$(grep -n 'PROD_KUBECONFIG_B64:.*secrets.PROD_KUBECONFIG_B64' "$DEPLOY" | cut -d: -f1)
if [ -z "$guard_line" ] || [ -z "$kubeconfig_line" ] || [ "$guard_line" -ge "$kubeconfig_line" ]; then
  echo 'The stale-release guard must run before the prod kubeconfig is exposed' >&2
  exit 1
fi

printf 'production_deploy_safety=ok\n'
