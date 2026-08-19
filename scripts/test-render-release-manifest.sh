#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-release-render.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

DIGEST_DIR="$TEST_ROOT/digests"
mkdir -p "$DIGEST_DIR"

index=1
for service in frontend auth user core media import; do
  printf 'sha256:%064x\n' "$index" > "$DIGEST_DIR/${service}.digest"
  index=$((index + 1))
done

release_sha=0123456789abcdef0123456789abcdef01234567
image_base=ghcr.io/mattoyuzuru/mnema

render() {
  output="$1"
  DIGEST_DIR="$DIGEST_DIR" \
  OUTPUT="$output" \
  RELEASE_SHA="$release_sha" \
  IMAGE_BASE="$image_base" \
    "$SCRIPT_DIR/render-release-manifest.sh"
}

render "$TEST_ROOT/release-a.yaml"
render "$TEST_ROOT/release-b.yaml"
cmp "$TEST_ROOT/release-a.yaml" "$TEST_ROOT/release-b.yaml"

test "$(grep -E -c 'image: ghcr\.io/mattoyuzuru/mnema/(frontend|auth|user|core|media|import)@sha256:[0-9a-f]{64}$' "$TEST_ROOT/release-a.yaml")" -eq 6
test "$(grep -E -c '^[[:space:]]+image:' "$TEST_ROOT/release-a.yaml")" -eq 11
test "$(grep -E -c '^[[:space:]]+image: [^[:space:]]+@sha256:[0-9a-f]{64}$' "$TEST_ROOT/release-a.yaml")" -eq 11
test "$(grep -F -c "value: \"$release_sha\"" "$TEST_ROOT/release-a.yaml")" -eq 6
test "$(grep -F -c "releaseId: \"$release_sha\"" "$TEST_ROOT/release-a.yaml")" -eq 1
if grep -Eq 'release-placeholder|ghcr\.io/mattoyuzuru/mnema/(frontend|auth|user|core|media|import):latest' "$TEST_ROOT/release-a.yaml"; then
  echo "rendered release contains a mutable image or unresolved placeholder" >&2
  exit 1
fi

if command -v yq >/dev/null 2>&1; then
  yq eval-all 'true' "$TEST_ROOT/release-a.yaml" >/dev/null
fi

printf 'sha256:not-a-digest\n' > "$DIGEST_DIR/frontend.digest"
if render "$TEST_ROOT/invalid-digest.yaml" 2>/dev/null; then
  echo "renderer accepted an invalid image digest" >&2
  exit 1
fi
printf 'sha256:%064x\n' 1 > "$DIGEST_DIR/frontend.digest"

if DIGEST_DIR="$DIGEST_DIR" \
  OUTPUT="$TEST_ROOT/invalid-release.yaml" \
  RELEASE_SHA=abc123 \
  IMAGE_BASE="$image_base" \
    "$SCRIPT_DIR/render-release-manifest.sh" 2>/dev/null; then
  echo "renderer accepted an invalid release SHA" >&2
  exit 1
fi

printf 'release_renderer=ok\n'
