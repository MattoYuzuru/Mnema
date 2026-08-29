#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
MAIN="$REPO_ROOT/.github/workflows/deploy.yaml"
STAGING="$REPO_ROOT/.github/workflows/staging-deploy.yaml"
PRODUCTION="$REPO_ROOT/.github/workflows/production-deploy.yaml"
PULL_REQUEST="$REPO_ROOT/.github/workflows/pull-request.yaml"
EXCEPTIONS="$REPO_ROOT/security/release-image-exceptions.json"

build_job=$(sed -n '/^  build-and-push:/,/^  render-release:/p' "$MAIN")
render_job=$(sed -n '/^  render-release:/,$p' "$MAIN")
staging_preflight=$(sed -n '/^  validate-main-ci:/,/^  deploy-staging:/p' "$STAGING")
production_preflight=$(sed -n '/^  validate-staging-deploy:/,/^  preview-production:/p' "$PRODUCTION")

printf '%s\n' "$build_job" | grep -Fq 'attestations: write'
printf '%s\n' "$build_job" | grep -Fq 'id-token: write'
printf '%s\n' "$build_job" | grep -Fq 'packages: write'
test "$(printf '%s\n' "$build_job" | grep -F -c 'provenance: mode=max')" -eq 2
test "$(printf '%s\n' "$build_job" | grep -F -c 'sbom: true')" -eq 2
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

printf '%s\n' "$render_job" | grep -Fq 'verify_release_security_evidence.py aggregate'
test "$(printf '%s\n' "$render_job" | grep -F -c 'verify_release_security_evidence.py verify-release')" -eq 1
# shellcheck disable=SC2016 # GitHub expression is a literal contract marker.
test "$(printf '%s\n' "$render_job" | grep -F -c '${{ runner.temp }}/release-security-evidence.json')" -eq 4
test "$(printf '%s\n' "$staging_preflight" | grep -F -c 'verify_release_security_evidence.py verify-release')" -eq 2
# shellcheck disable=SC2016 # Workflow variable is a literal contract marker.
printf '%s\n' "$staging_preflight" | grep -Fq -- '--expected-run-id "$UPSTREAM_RUN_ID"'
test "$(grep -F -c 'production-promotion/release-security-evidence.json' "$STAGING")" -eq 2
test "$(printf '%s\n' "$production_preflight" | grep -F -c 'verify_release_security_evidence.py verify-release')" -eq 1
test "$(grep -F -c 'verify_release_security_evidence.py verify-release' "$PRODUCTION")" -eq 3

if printf '%s\n%s\n' "$staging_preflight" "$production_preflight" | grep -Fq 'environment:'; then
  echo 'Security evidence preflight must complete before Environment access' >&2
  exit 1
fi

grep -Fq 'run: ./scripts/test-release-security-contract.sh' "$MAIN"
grep -Fq 'run: ./scripts/test-release-security-contract.sh' "$PULL_REQUEST"
python3 "$REPO_ROOT/scripts/verify_release_security_evidence.py" aggregate --help >/dev/null
python3 -m json.tool "$EXCEPTIONS" >/dev/null

echo 'release_security_contract=ok'
