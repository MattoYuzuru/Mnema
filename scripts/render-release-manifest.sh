#!/bin/sh
set -eu

# Inputs: DIGEST_DIR, OUTPUT, RELEASE_SHA and IMAGE_BASE. This command only
# renders OUTPUT locally; cluster inspection and application remain separate.
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
MANIFEST_ROOT="${MANIFEST_ROOT:-$REPO_ROOT/k8s}"
DIGEST_DIR="${DIGEST_DIR:?DIGEST_DIR must contain one <service>.digest file per release image}"
OUTPUT="${OUTPUT:?OUTPUT must name the rendered release manifest}"
RELEASE_SHA="${RELEASE_SHA:?RELEASE_SHA must be the exact tested commit}"
IMAGE_BASE="${IMAGE_BASE:?IMAGE_BASE must be the repository image prefix}"

if [ "${#RELEASE_SHA}" -ne 40 ]; then
  echo "RELEASE_SHA must be exactly 40 lowercase hexadecimal characters" >&2
  exit 1
fi
case "$RELEASE_SHA" in
  *[!0-9a-f]*)
    echo "RELEASE_SHA must be exactly 40 lowercase hexadecimal characters" >&2
    exit 1
    ;;
esac

case "$IMAGE_BASE" in
  "" | *[!a-z0-9./_-]*)
    echo "IMAGE_BASE contains unsupported characters: $IMAGE_BASE" >&2
    exit 1
    ;;
esac

services="frontend auth user core media import"
mkdir -p "$(dirname -- "$OUTPUT")"
tmp_output=$(mktemp "${OUTPUT}.tmp.XXXXXX")
trap 'rm -f "$tmp_output"' EXIT HUP INT TERM

image_ref() {
  service="$1"
  digest_file="$DIGEST_DIR/${service}.digest"
  if [ ! -f "$digest_file" ]; then
    echo "Missing digest for $service: $digest_file" >&2
    exit 1
  fi

  digest=$(tr -d '\r\n' < "$digest_file")
  case "$digest" in
    sha256:*) digest_hex=${digest#sha256:} ;;
    *)
      echo "Invalid sha256 digest for $service" >&2
      exit 1
      ;;
  esac
  if [ "${#digest_hex}" -ne 64 ]; then
    echo "Invalid sha256 digest for $service" >&2
    exit 1
  fi
  case "$digest_hex" in
    *[!0-9a-f]*)
      echo "Invalid sha256 digest for $service" >&2
      exit 1
      ;;
  esac

  printf '%s/%s@%s' "$IMAGE_BASE" "$service" "$digest"
}

{
  printf '%s\n' \
    'apiVersion: v1' \
    'kind: ConfigMap' \
    'metadata:' \
    '  name: mnema-release' \
    '  namespace: prod' \
    'data:' \
    "  releaseId: \"$RELEASE_SHA\""

  for service in $services; do
    ref=$(image_ref "$service")
    printf '  %sImage: "%s"\n' "$service" "$ref"
  done

  for service in $services; do
    template="$MANIFEST_ROOT/${service}-deploy.yaml"
    source_image="ghcr.io/mattoyuzuru/mnema/${service}:release-placeholder"
    if [ ! -f "$template" ]; then
      echo "Missing deployment template: $template" >&2
      exit 1
    fi
    if [ "$(grep -F -c "$source_image" "$template")" -ne 1 ]; then
      echo "Expected exactly one release image placeholder for $service in $template" >&2
      exit 1
    fi

    ref=$(image_ref "$service")
    printf '\n---\n'
    sed \
      -e "s|$source_image|$ref|" \
      -e "s|release-placeholder|$RELEASE_SHA|g" \
      "$template"
  done

  for template in "$MANIFEST_ROOT/ingress.yaml" "$MANIFEST_ROOT/auth-ingress.yaml"; do
    if [ ! -f "$template" ]; then
      echo "Missing routing manifest: $template" >&2
      exit 1
    fi
    printf '\n---\n'
    cat "$template"
  done
} > "$tmp_output"

if grep -Eq 'release-placeholder|ghcr\.io/mattoyuzuru/mnema/(frontend|auth|user|core|media|import):latest' "$tmp_output"; then
  echo "Rendered manifest still contains a mutable Mnema image or placeholder" >&2
  exit 1
fi

image_count=$(grep -E -c '^[[:space:]]+image:' "$tmp_output")
pinned_image_count=$(grep -E -c '^[[:space:]]+image: [^[:space:]]+@sha256:[0-9a-f]{64}$' "$tmp_output")
if [ "$image_count" -ne "$pinned_image_count" ]; then
  echo "Rendered manifest contains an image that is not pinned by sha256 digest" >&2
  exit 1
fi

for service in $services; do
  ref=$(image_ref "$service")
  if [ "$(grep -F -c "image: $ref" "$tmp_output")" -ne 1 ]; then
    echo "Rendered manifest does not contain exactly one digest-pinned $service container" >&2
    exit 1
  fi
done

mv "$tmp_output" "$OUTPUT"
trap - EXIT HUP INT TERM
