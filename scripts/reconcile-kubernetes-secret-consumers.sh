#!/bin/sh
set -eu

if [ "$#" -lt 3 ]; then
  echo "Usage: $0 <namespace> <secret> <deployment>..." >&2
  exit 64
fi

namespace=$1
secret=$2
shift 2

secret_resource_version=$(kubectl -n "$namespace" get secret "$secret" \
  -o jsonpath='{.metadata.resourceVersion}')
if [ -z "$secret_resource_version" ]; then
  echo "Secret ${namespace}/${secret} has no resourceVersion" >&2
  exit 1
fi

patch=$(jq -cn --arg generation "$secret_resource_version" \
  '{"spec":{"template":{"metadata":{"annotations":{"mnema.app/secret-resource-version":$generation}}}}}')

for consumer in "$@"; do
  kubectl -n "$namespace" patch deployment "$consumer" --type=merge -p "$patch" >/dev/null
done

for consumer in "$@"; do
  observed=$(kubectl -n "$namespace" get deployment "$consumer" \
    -o jsonpath='{.spec.template.metadata.annotations.mnema\.app/secret-resource-version}')
  if [ "$observed" != "$secret_resource_version" ]; then
    echo "deployment/${consumer} does not reference Secret generation ${secret_resource_version}" >&2
    exit 1
  fi
done

printf 'secret_consumer_generation=%s\n' "$secret_resource_version"
