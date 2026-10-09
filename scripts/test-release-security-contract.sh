#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
MAIN="$REPO_ROOT/.github/workflows/deploy.yaml"
PULL_REQUEST="$REPO_ROOT/.github/workflows/pull-request.yaml"
EXCEPTIONS="$REPO_ROOT/security/release-image-exceptions.json"

build_job=$(sed -n '/^  build-and-push:/,/^  assemble-vps-candidate:/p' "$MAIN")
assemble_job=$(sed -n '/^  assemble-vps-candidate:/,/^  deploy-production:/p' "$MAIN")
deploy_job=$(sed -n '/^  deploy-production:/,/^  post-deploy-smoke:/p' "$MAIN")

printf '%s\n' "$build_job" | grep -Fq 'attestations: write'
printf '%s\n' "$build_job" | grep -Fq 'id-token: write'
printf '%s\n' "$build_job" | grep -Fq 'packages: write'
test "$(printf '%s\n' "$build_job" | grep -F -c 'provenance: mode=max')" -eq 1
test "$(printf '%s\n' "$build_job" | grep -F -c 'sbom: true')" -eq 1
test "$(printf '%s\n' "$build_job" | grep -E -c '          - name: (identity-account|learning)$')" -eq 2
test "$(printf '%s\n' "$build_job" | grep -F -c 'actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6 # v4.2.2')" -eq 2
test "$(printf '%s\n' "$build_job" | grep -F -c 'aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25 # v0.36.0')" -eq 1
# shellcheck disable=SC2016 # GitHub expression is a literal contract marker.
printf '%s\n' "$build_job" | grep -Fq 'image-ref: ${{ steps.release-image.outputs.image }}'
printf '%s\n' "$build_job" | grep -Fq 'severity: UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL'
printf '%s\n' "$build_job" | grep -Fq 'exit-code: "0"'
printf '%s\n' "$build_job" | grep -Fq 'version: v0.70.0'
printf '%s\n' "$build_job" | grep -Fq -- '--format sarif'
printf '%s\n' "$build_job" | grep -Fq -- '--skip-db-update'
printf '%s\n' "$build_job" | grep -Fq 'verify_release_security_evidence.py evaluate'
python3 "$REPO_ROOT/scripts/verify_release_security_evidence.py" validate-workflow \
  --workflow "$MAIN"

evaluate_line=$(printf '%s\n' "$build_job" | grep -n 'verify_release_security_evidence.py evaluate' | cut -d: -f1)
digest_upload_line=$(printf '%s\n' "$build_job" | grep -n 'name: Upload immutable image digest' | cut -d: -f1)
if [ "$evaluate_line" -ge "$digest_upload_line" ]; then
  echo 'Vulnerability policy must pass before the releasable digest artifact is uploaded' >&2
  exit 1
fi

printf '%s\n' "$assemble_job" | grep -Fq 'verify_release_security_evidence.py aggregate --include-frontend'
printf '%s\n' "$assemble_job" | grep -Fq 'render_vps_candidate.py'
# The approved job re-verifies the exact digests before it reads any production credential.
verify_line=$(printf '%s\n' "$deploy_job" | grep -n 'gh attestation verify' | head -n 1 | cut -d: -f1)
ssh_line=$(printf '%s\n' "$deploy_job" | grep -n 'PROD_DEPLOY_SSH_KEY' | head -n 1 | cut -d: -f1)
if [ -z "$verify_line" ] || [ -z "$ssh_line" ] || [ "$verify_line" -ge "$ssh_line" ]; then
  echo 'Attestations must be re-verified before production credentials are read' >&2
  exit 1
fi
printf '%s\n' "$deploy_job" | grep -Fq -- '--deny-self-hosted-runners'
printf '%s\n' "$deploy_job" | grep -Fq -- '--source-ref refs/heads/main'
printf '%s\n' "$deploy_job" | grep -Fq 'name: prod'
if printf '%s\n%s\n' "$build_job" "$assemble_job" | grep -Fq 'environment:'; then
  echo 'Only the approved deploy job may enter the prod Environment' >&2
  exit 1
fi
grep -Fq 'run: ./scripts/test-release-security-contract.sh' "$MAIN"
grep -Fq 'run: ./scripts/test-release-security-contract.sh' "$PULL_REQUEST"
python3 "$REPO_ROOT/scripts/verify_release_security_evidence.py" aggregate --help >/dev/null
python3 -m json.tool "$EXCEPTIONS" >/dev/null

echo 'release_security_contract=ok'
