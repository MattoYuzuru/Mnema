#!/bin/sh
set -eu

# Required inputs: DIGEST_DIR, OUTPUT, RELEASE_SHA and IMAGE_BASE. Optional
# environment/host/S3 inputs default to production. This command only renders
# OUTPUT locally; cluster inspection and application remain separate.
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
MANIFEST_ROOT="${MANIFEST_ROOT:-$REPO_ROOT/k8s}"
DIGEST_DIR="${DIGEST_DIR:?DIGEST_DIR must contain one <service>.digest file per release image}"
OUTPUT="${OUTPUT:?OUTPUT must name the rendered release manifest}"
RELEASE_SHA="${RELEASE_SHA:?RELEASE_SHA must be the exact tested commit}"
IMAGE_BASE="${IMAGE_BASE:?IMAGE_BASE must be the repository image prefix}"
RELEASE_NAMESPACE="${RELEASE_NAMESPACE:-prod}"
PUBLIC_HOST="${PUBLIC_HOST:-mnema.app}"
AUTH_HOST="${AUTH_HOST:-auth.mnema.app}"
APP_ENV="${APP_ENV:-prod}"
SPRING_PROFILES="${SPRING_PROFILES:-prod}"
S3_ENDPOINT="${S3_ENDPOINT:-https://storage.yandexcloud.net}"
S3_PUBLIC_ENDPOINT="${S3_PUBLIC_ENDPOINT:-$S3_ENDPOINT}"
S3_PATH_STYLE_ACCESS="${S3_PATH_STYLE_ACCESS:-false}"
INCLUDE_INGRESS="${INCLUDE_INGRESS:-true}"

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

case "$RELEASE_NAMESPACE" in
  "" | *[!a-z0-9-]* | -* | *-)
    echo "RELEASE_NAMESPACE must be a lowercase DNS label" >&2
    exit 1
    ;;
esac
if [ "${#RELEASE_NAMESPACE}" -gt 63 ]; then
  echo "RELEASE_NAMESPACE must be at most 63 characters" >&2
  exit 1
fi

for host in "$PUBLIC_HOST" "$AUTH_HOST"; do
  case "$host" in
    "" | *[!a-z0-9.-]* | .* | *. | *..*)
      echo "Release hosts must be lowercase DNS names" >&2
      exit 1
      ;;
  esac
done

case "$APP_ENV" in
  "" | *[!a-z0-9-]*)
    echo "APP_ENV must contain only lowercase letters, digits and dashes" >&2
    exit 1
    ;;
esac
case "$SPRING_PROFILES" in
  "" | *[!a-z0-9,-]*)
    echo "SPRING_PROFILES contains unsupported characters" >&2
    exit 1
    ;;
esac
for endpoint in "$S3_ENDPOINT" "$S3_PUBLIC_ENDPOINT"; do
  case "$endpoint" in
    http://* | https://*) ;;
    *)
      echo "S3 endpoints must use http or https" >&2
      exit 1
      ;;
  esac
  case "$endpoint" in
    *[!a-zA-Z0-9.:/_-]* | *'&'* | *'|'*)
      echo "S3 endpoint contains unsupported characters" >&2
      exit 1
      ;;
  esac
done
case "$S3_PATH_STYLE_ACCESS" in
  true | false) ;;
  *)
    echo "S3_PATH_STYLE_ACCESS must be true or false" >&2
    exit 1
    ;;
esac
case "$INCLUDE_INGRESS" in
  true | false) ;;
  *)
    echo "INCLUDE_INGRESS must be true or false" >&2
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

render_template() {
  sed \
    -e "s|release-namespace-placeholder|$RELEASE_NAMESPACE|g" \
    -e "s|release-public-host-placeholder|$PUBLIC_HOST|g" \
    -e "s|release-auth-host-placeholder|$AUTH_HOST|g" \
    -e "s|release-app-env-placeholder|$APP_ENV|g" \
    -e "s|release-spring-profiles-placeholder|$SPRING_PROFILES|g" \
    -e "s|release-s3-endpoint-placeholder|$S3_ENDPOINT|g" \
    -e "s|release-s3-public-endpoint-placeholder|$S3_PUBLIC_ENDPOINT|g" \
    -e "s|release-s3-path-style-placeholder|$S3_PATH_STYLE_ACCESS|g" \
    "$1"
}

{
  printf '%s\n' \
    'apiVersion: v1' \
    'kind: ConfigMap' \
    'metadata:' \
    '  name: mnema-release' \
    "  namespace: $RELEASE_NAMESPACE" \
    'data:' \
    "  releaseId: \"$RELEASE_SHA\"" \
    "  publicHost: \"$PUBLIC_HOST\"" \
    "  authHost: \"$AUTH_HOST\""

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
    render_template "$template" | sed \
      -e "s|$source_image|$ref|" \
      -e "s|release-placeholder|$RELEASE_SHA|g"
  done

  if [ "$INCLUDE_INGRESS" = true ]; then
    for template in "$MANIFEST_ROOT/ingress.yaml" "$MANIFEST_ROOT/auth-ingress.yaml"; do
      if [ ! -f "$template" ]; then
        echo "Missing routing manifest: $template" >&2
        exit 1
      fi
      printf '\n---\n'
      render_template "$template"
    done
  fi
} > "$tmp_output"

if grep -Eq 'release(-[a-z0-9]+)*-placeholder|ghcr\.io/mattoyuzuru/mnema/(frontend|auth|user|core|media|import):latest' "$tmp_output"; then
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
