#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
RECONCILE="$SCRIPT_DIR/reconcile-kubernetes-secret-consumers.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-secret-consumers.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM
mkdir "$TEST_ROOT/bin"

cat >"$TEST_ROOT/bin/jq" <<'EOF'
#!/bin/sh
set -eu
test "$1" = -cn
test "$2" = --arg
test "$3" = generation
printf '{"spec":{"template":{"metadata":{"annotations":{"mnema.app/secret-resource-version":"%s"}}}}}\n' "$4"
EOF

cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu
printf 'kubectl %s\n' "$*" >>"$FAKE_KUBECTL_LOG"
case "$*" in
  '-n mnema-staging get secret mnema-secrets -o jsonpath={.metadata.resourceVersion}')
    printf '%s' "${FAKE_SECRET_RESOURCE_VERSION-41}"
    ;;
  '-n mnema-staging patch deployment '*' --type=merge -p '*)
    ;;
  '-n mnema-staging get deployment '*' -o jsonpath={.spec.template.metadata.annotations.mnema\.app/secret-resource-version}')
    printf '%s' "${FAKE_OBSERVED_RESOURCE_VERSION-${FAKE_SECRET_RESOURCE_VERSION-41}}"
    ;;
  *) exit 64 ;;
esac
EOF
chmod +x "$TEST_ROOT/bin/jq" "$TEST_ROOT/bin/kubectl" "$RECONCILE"

log="$TEST_ROOT/kubectl.log"
PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$log" "$RECONCILE" \
  mnema-staging mnema-secrets \
  mnema-auth mnema-user mnema-core mnema-media mnema-import >/dev/null
test "$(grep -c ' patch deployment ' "$log")" -eq 5
test "$(grep -c ' get deployment ' "$log")" -eq 5
if grep -Fq 'mnema-frontend' "$log"; then
  echo 'the frontend must not restart for a Secret it does not consume' >&2
  exit 1
fi

# A retry after Secret apply must still reconcile the same live generation;
# it must not depend on the now-empty desired-vs-live Secret drift.
: >"$log"
PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$log" "$RECONCILE" \
  mnema-staging mnema-secrets mnema-auth mnema-user >/dev/null
test "$(grep -c ' patch deployment ' "$log")" -eq 2

: >"$log"
if PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$log" \
  FAKE_SECRET_RESOURCE_VERSION='' "$RECONCILE" \
  mnema-staging mnema-secrets mnema-auth >/dev/null 2>&1; then
  echo 'a missing live Secret generation must fail closed' >&2
  exit 1
fi
if grep -Fq ' patch deployment ' "$log"; then
  echo 'a missing Secret generation must fail before workload mutation' >&2
  exit 1
fi

if PATH="$TEST_ROOT/bin:$PATH" FAKE_KUBECTL_LOG="$log" \
  FAKE_OBSERVED_RESOURCE_VERSION=40 "$RECONCILE" \
  mnema-staging mnema-secrets mnema-auth >/dev/null 2>&1; then
  echo 'a consumer with the wrong Secret generation must fail verification' >&2
  exit 1
fi

printf 'kubernetes_secret_consumer_reconciliation=ok\n'
