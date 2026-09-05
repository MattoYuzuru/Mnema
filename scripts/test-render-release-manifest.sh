#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-release-render.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

DIGEST_DIR="$TEST_ROOT/digests"
MANIFEST_ROOT="$TEST_ROOT/manifests"
mkdir -p "$DIGEST_DIR" "$MANIFEST_ROOT"

index=1
for service in identity-account learning; do
  printf 'sha256:%064x\n' "$index" > "$DIGEST_DIR/${service}.digest"
  index=$((index + 1))
  {
    printf '%s\n' \
      'apiVersion: apps/v1' \
      'kind: Deployment' \
      'metadata:' \
      "  name: mnema-$service" \
      '  namespace: release-namespace-placeholder' \
      'spec:' \
      '  template:' \
      '    spec:' \
      '      containers:' \
      "        - name: $service" \
      "          image: ghcr.io/mattoyuzuru/mnema/$service:release-placeholder" \
      '          env:' \
      '            - name: MNEMA_BUILD_ID' \
      '              value: "release-placeholder"' \
      '---' \
      'apiVersion: v1' \
      'kind: Service' \
      'metadata:' \
      "  name: mnema-$service" \
      '  namespace: release-namespace-placeholder'
  } > "$MANIFEST_ROOT/${service}-deploy.yaml"
done

{
  printf '%s\n' \
    'apiVersion: networking.k8s.io/v1' \
    'kind: Ingress' \
    'metadata:' \
    '  name: mnema' \
    '  namespace: release-namespace-placeholder' \
    'spec:' \
    '  tls:' \
    '    - hosts: [release-public-host-placeholder]' \
    '      secretName: release-public-tls-secret-placeholder' \
    '  rules:' \
    '    - host: release-public-host-placeholder'
} > "$MANIFEST_ROOT/ingress.yaml"

{
  printf '%s\n' \
    'apiVersion: networking.k8s.io/v1' \
    'kind: Ingress' \
    'metadata:' \
    '  name: mnema-auth' \
    '  namespace: release-namespace-placeholder' \
    'spec:' \
    '  tls:' \
    '    - hosts: [release-auth-host-placeholder]' \
    '      secretName: release-auth-tls-secret-placeholder' \
    '  rules:' \
    '    - host: release-auth-host-placeholder'
} > "$MANIFEST_ROOT/auth-ingress.yaml"

release_sha=0123456789abcdef0123456789abcdef01234567
image_base=ghcr.io/mattoyuzuru/mnema

render() {
  output="$1"
  DIGEST_DIR="$DIGEST_DIR" \
  MANIFEST_ROOT="$MANIFEST_ROOT" \
  OUTPUT="$output" \
  RELEASE_SHA="$release_sha" \
  IMAGE_BASE="$image_base" \
    "$SCRIPT_DIR/render-release-manifest.sh"
}

render "$TEST_ROOT/release-a.yaml"
render "$TEST_ROOT/release-b.yaml"
cmp "$TEST_ROOT/release-a.yaml" "$TEST_ROOT/release-b.yaml"

DIGEST_DIR="$DIGEST_DIR" \
MANIFEST_ROOT="$MANIFEST_ROOT" \
OUTPUT="$TEST_ROOT/release-staging.yaml" \
RELEASE_SHA="$release_sha" \
IMAGE_BASE="$image_base" \
RELEASE_NAMESPACE=mnema-staging \
PUBLIC_HOST=staging.mnema.app \
AUTH_HOST=auth.staging.mnema.app \
APP_ENV=staging \
SPRING_PROFILES=prod \
S3_ENDPOINT=http://minio:9000 \
S3_PUBLIC_ENDPOINT=https://storage.staging.mnema.app \
S3_PATH_STYLE_ACCESS=true \
INCLUDE_INGRESS=true \
  "$SCRIPT_DIR/render-release-manifest.sh"

test "$(grep -E -c 'image: ghcr\.io/mattoyuzuru/mnema/(identity-account|learning)@sha256:[0-9a-f]{64}$' "$TEST_ROOT/release-a.yaml")" -eq 2
test "$(grep -E -c '^[[:space:]]+image:' "$TEST_ROOT/release-a.yaml")" -eq 2
test "$(grep -F -c "value: \"$release_sha\"" "$TEST_ROOT/release-a.yaml")" -eq 2
test "$(grep -F -c "releaseId: \"$release_sha\"" "$TEST_ROOT/release-a.yaml")" -eq 1
test "$(grep -F -c 'releaseTopology: "identity-learning"' "$TEST_ROOT/release-a.yaml")" -eq 1
test "$(grep -F -c 'releaseMode: "maintenance"' "$TEST_ROOT/release-a.yaml")" -eq 1
test "$(grep -F -c 'productionEligible: "false"' "$TEST_ROOT/release-a.yaml")" -eq 1
if grep -Eq 'release(-[a-z0-9]+)*-placeholder|ghcr\.io/mattoyuzuru/mnema/(frontend|auth|user|core|media|import|ai)(:|@)' "$TEST_ROOT/release-a.yaml"; then
  echo "rendered release contains a legacy image or unresolved placeholder" >&2
  exit 1
fi

test "$(grep -F -c 'namespace: mnema-staging' "$TEST_ROOT/release-staging.yaml")" -eq 7
test "$(grep -F -c 'kind: Ingress' "$TEST_ROOT/release-staging.yaml")" -eq 2
test "$(grep -F -c 'secretName: staging-mnema-app-tls' "$TEST_ROOT/release-staging.yaml")" -eq 1
test "$(grep -F -c 'secretName: auth-staging-mnema-app-tls' "$TEST_ROOT/release-staging.yaml")" -eq 1
grep -E '^[[:space:]]+image: ghcr\.io/mattoyuzuru/mnema/' "$TEST_ROOT/release-a.yaml" > "$TEST_ROOT/production-images.txt"
grep -E '^[[:space:]]+image: ghcr\.io/mattoyuzuru/mnema/' "$TEST_ROOT/release-staging.yaml" > "$TEST_ROOT/staging-images.txt"
if ! diff -u "$TEST_ROOT/production-images.txt" "$TEST_ROOT/staging-images.txt" >/dev/null; then
  echo "production and staging application image digests differ" >&2
  exit 1
fi

if command -v yq >/dev/null 2>&1; then
  yq eval-all 'true' "$TEST_ROOT/release-a.yaml" >/dev/null
fi

printf 'sha256:not-a-digest\n' > "$DIGEST_DIR/identity-account.digest"
if render "$TEST_ROOT/invalid-digest.yaml" 2>/dev/null; then
  echo "renderer accepted an invalid image digest" >&2
  exit 1
fi
printf 'sha256:%064x\n' 1 > "$DIGEST_DIR/identity-account.digest"

if DIGEST_DIR="$DIGEST_DIR" MANIFEST_ROOT="$MANIFEST_ROOT" \
  OUTPUT="$TEST_ROOT/invalid-release.yaml" RELEASE_SHA=abc123 IMAGE_BASE="$image_base" \
    "$SCRIPT_DIR/render-release-manifest.sh" 2>/dev/null; then
  echo "renderer accepted an invalid release SHA" >&2
  exit 1
fi

if DIGEST_DIR="$DIGEST_DIR" MANIFEST_ROOT="$MANIFEST_ROOT" \
  OUTPUT="$TEST_ROOT/invalid-mode.yaml" RELEASE_SHA="$release_sha" IMAGE_BASE="$image_base" \
  RELEASE_MODE=active "$SCRIPT_DIR/render-release-manifest.sh" 2>/dev/null; then
  echo "renderer accepted a non-maintenance mode" >&2
  exit 1
fi

if DIGEST_DIR="$DIGEST_DIR" MANIFEST_ROOT="$MANIFEST_ROOT" \
  OUTPUT="$TEST_ROOT/invalid-production-eligibility.yaml" RELEASE_SHA="$release_sha" IMAGE_BASE="$image_base" \
  PRODUCTION_ELIGIBLE=true "$SCRIPT_DIR/render-release-manifest.sh" 2>/dev/null; then
  echo "renderer accepted a production-eligible maintenance release" >&2
  exit 1
fi

if DIGEST_DIR="$DIGEST_DIR" MANIFEST_ROOT="$MANIFEST_ROOT" \
  OUTPUT="$TEST_ROOT/invalid-staging-tls.yaml" RELEASE_SHA="$release_sha" IMAGE_BASE="$image_base" \
  APP_ENV=staging PUBLIC_TLS_SECRET=wrong-tls "$SCRIPT_DIR/render-release-manifest.sh" 2>/dev/null; then
  echo "renderer accepted a staging TLS Secret outside the existing certificate boundary" >&2
  exit 1
fi

printf 'release_renderer=ok\n'
