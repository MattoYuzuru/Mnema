#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
CAPTURE="$SCRIPT_DIR/capture-release-diff.sh"
VERIFY="$SCRIPT_DIR/verify-release-preview.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-release-diff.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

mkdir "$TEST_ROOT/bin"
cat >"$TEST_ROOT/bin/kubectl" <<'EOF'
#!/bin/sh
set -eu

namespace=
if [ "${1:-}" = -n ]; then
  namespace=$2
  shift 2
fi

case "$1" in
  create)
    if [ "$namespace" != observability ] || [ "${2:-}" != configmap ] || \
       [ "${3:-}" != grafana-dashboards ]; then
      echo 'capture must render the exact Grafana dashboards ConfigMap' >&2
      exit 65
    fi
    printf '%s\n' \
      'apiVersion: v1' \
      'kind: ConfigMap' \
      'metadata:' \
      '  name: grafana-dashboards' \
      '  namespace: observability'
    ;;
  apply)
    if [ "${2:-}" != '--dry-run=server' ] || [ "${3:-}" != '-f' ]; then
      echo 'capture must perform a server-side dry run before diffing' >&2
      exit 65
    fi
    for required in \
      k8s/namespace.yaml \
      k8s/cluster-issuers.yaml \
      k8s/postgres.yaml \
      k8s/redis.yaml \
      k8s/observability \
      grafana-dashboards.yaml \
      release.yaml
    do
      if ! printf '%s\n' "$*" | grep -Fq "$required"; then
        echo "production plan omitted $required" >&2
        exit 65
      fi
    done
    exit "${FAKE_DRY_RUN_STATUS:-0}"
    ;;
  diff)
    if [ "${2:-}" != '--show-secrets=false' ] || [ "${3:-}" != '-f' ]; then
      echo 'capture must keep secret values masked in the review artifact' >&2
      exit 65
    fi
    if [ -n "${FAKE_LIVE_PATH:-}" ] && [ -n "${FAKE_DESIRED_PATH:-}" ]; then
      "$KUBECTL_EXTERNAL_DIFF" "$FAKE_LIVE_PATH" "$FAKE_DESIRED_PATH"
      exit $?
    fi
    if [ -n "${FAKE_DIFF_BODY:-}" ]; then
      printf '%s\n' "$FAKE_DIFF_BODY"
    fi
    exit "${FAKE_DIFF_STATUS:-0}"
    ;;
  get)
    if [ "${FAKE_GET_STATUS:-0}" -ne 0 ]; then
      exit "$FAKE_GET_STATUS"
    fi
    case "${2:-}" in
      service)
        printf '%s' "${FAKE_LEGACY_SERVICE:-}"
        ;;
      endpointslice)
        printf '%s' "${FAKE_LEGACY_SLICES:-}"
        ;;
      *)
        exit 64
        ;;
    esac
    ;;
  *)
    exit 64
    ;;
esac
EOF
chmod +x "$TEST_ROOT/bin/kubectl"

manifest="$TEST_ROOT/release.yaml"
diff_file="$TEST_ROOT/release.diff"
printf '%s\n' 'apiVersion: v1' >"$manifest"

expected_no_change=$(printf 'No production resource changes.\n' | sha256sum | awk '{print $1}')
actual_no_change=$(PATH="$TEST_ROOT/bin:$PATH" FAKE_DIFF_STATUS=0 \
  "$CAPTURE" "$manifest" "$diff_file")
test "$actual_no_change" = "$expected_no_change"
grep -Fxq 'No production resource changes.' "$diff_file"

cat >"$TEST_ROOT/expected-change.diff" <<'EOF'
Production declarative resource diff:
sanitized-release-diff

Planned legacy resource removals:
None.
EOF
expected_change=$(sha256sum "$TEST_ROOT/expected-change.diff" | awk '{print $1}')
actual_change=$(PATH="$TEST_ROOT/bin:$PATH" FAKE_DIFF_STATUS=1 \
  FAKE_DIFF_BODY='sanitized-release-diff' \
  "$CAPTURE" "$manifest" "$diff_file")
test "$actual_change" = "$expected_change"
cmp "$TEST_ROOT/expected-change.diff" "$diff_file"

legacy_change=$(PATH="$TEST_ROOT/bin:$PATH" FAKE_DIFF_STATUS=0 \
  FAKE_LEGACY_SERVICE='service/mnema-ai-bridge' \
  "$CAPTURE" "$manifest" "$diff_file")
test "$legacy_change" = "$(sha256sum "$diff_file" | awk '{print $1}')"
grep -Fxq -- '- service/mnema-ai-bridge' "$diff_file"

mkdir -p \
  "$TEST_ROOT/first-live/apps.v1.Deployment.prod.mnema-auth" \
  "$TEST_ROOT/first-desired/apps.v1.Deployment.prod.mnema-auth" \
  "$TEST_ROOT/second-live/apps.v1.Deployment.prod.mnema-auth" \
  "$TEST_ROOT/second-desired/apps.v1.Deployment.prod.mnema-auth"
cat >"$TEST_ROOT/first-live/apps.v1.Deployment.prod.mnema-auth/object.yaml" <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  creationTimestamp: "2026-08-29T09:00:00Z"
  generation: 10
  name: mnema-auth
  resourceVersion: "100"
  uid: aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
spec:
  generation: preserve-spec-fields-with-the-same-name
  image: old@sha256:aaaaaaaa
EOF
cat >"$TEST_ROOT/first-desired/apps.v1.Deployment.prod.mnema-auth/object.yaml" <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  creationTimestamp: "2026-08-29T09:00:01Z"
  generation: 11
  name: mnema-auth
  resourceVersion: "101"
  uid: bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb
spec:
  generation: preserve-spec-fields-with-the-same-name
  image: new@sha256:bbbbbbbb
EOF
cat >"$TEST_ROOT/second-live/apps.v1.Deployment.prod.mnema-auth/object.yaml" <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  creationTimestamp: "2026-08-29T09:05:00Z"
  generation: 20
  name: mnema-auth
  resourceVersion: "200"
  uid: cccccccc-cccc-cccc-cccc-cccccccccccc
spec:
  generation: preserve-spec-fields-with-the-same-name
  image: old@sha256:aaaaaaaa
EOF
cat >"$TEST_ROOT/second-desired/apps.v1.Deployment.prod.mnema-auth/object.yaml" <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  creationTimestamp: "2026-08-29T09:05:01Z"
  generation: 21
  name: mnema-auth
  resourceVersion: "201"
  uid: dddddddd-dddd-dddd-dddd-dddddddddddd
spec:
  generation: preserve-spec-fields-with-the-same-name
  image: new@sha256:bbbbbbbb
EOF

first_tree_hash=$(PATH="$TEST_ROOT/bin:$PATH" \
  FAKE_LIVE_PATH="$TEST_ROOT/first-live" \
  FAKE_DESIRED_PATH="$TEST_ROOT/first-desired" \
  "$CAPTURE" "$manifest" "$TEST_ROOT/first.diff")
second_tree_hash=$(PATH="$TEST_ROOT/bin:$PATH" \
  FAKE_LIVE_PATH="$TEST_ROOT/second-live" \
  FAKE_DESIRED_PATH="$TEST_ROOT/second-desired" \
  "$CAPTURE" "$manifest" "$TEST_ROOT/second.diff")
test "$first_tree_hash" = "$second_tree_hash"
cmp "$TEST_ROOT/first.diff" "$TEST_ROOT/second.diff"
if grep -Eq 'creationTimestamp|resourceVersion|uid:' "$TEST_ROOT/first.diff"; then
  echo 'canonical diff must exclude volatile API-server metadata' >&2
  exit 1
fi
grep -Fq 'preserve-spec-fields-with-the-same-name' "$TEST_ROOT/first.diff"
if grep -Fq "$TEST_ROOT" "$TEST_ROOT/first.diff"; then
  echo 'canonical diff must not contain random temporary roots' >&2
  exit 1
fi

mkdir -p "$TEST_ROOT/unreadable-live/blocked" "$TEST_ROOT/unreadable-desired"
printf '%s\n' 'must-not-be-skipped' >"$TEST_ROOT/unreadable-live/blocked/object.yaml"
chmod 000 "$TEST_ROOT/unreadable-live/blocked"
set +e
PATH="$TEST_ROOT/bin:$PATH" \
  FAKE_LIVE_PATH="$TEST_ROOT/unreadable-live" \
  FAKE_DESIRED_PATH="$TEST_ROOT/unreadable-desired" \
  "$CAPTURE" "$manifest" "$TEST_ROOT/unreadable.diff" >/dev/null 2>&1
unreadable_status=$?
set -e
chmod 700 "$TEST_ROOT/unreadable-live/blocked"
if [ "$unreadable_status" -le 1 ]; then
  echo 'resource traversal failures must stop capture as operational errors' >&2
  exit 1
fi

printf '%s  %s\n' \
  'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc' \
  'production-release.yaml' >"$TEST_ROOT/production-release.yaml.sha256"
cat >"$TEST_ROOT/production-release-preview.txt" <<EOF
release_sha=dddddddddddddddddddddddddddddddddddddddd
release_manifest_sha256=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
release_diff_sha256=${first_tree_hash}
production_resource_changes=true
secret_drift=false
secret_snapshot_hmac=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
live_secret_snapshot_hmac=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
app_secret_generation=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
grafana_secret_generation=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
has_release_changes=true
kubectl_version=v1.36.0
run_id=12345
run_attempt=2
EOF

"$VERIFY" \
  "$TEST_ROOT/production-release-preview.txt" \
  "$TEST_ROOT/first.diff" \
  "$TEST_ROOT/production-release.yaml.sha256" \
  dddddddddddddddddddddddddddddddddddddddd \
  "$first_tree_hash" \
  v1.36.0 \
  12345 \
  2 \
  false \
  eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee \
  ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff \
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  true >/dev/null

printf '%s\n' 'tampered-after-preview' >>"$TEST_ROOT/first.diff"
if "$VERIFY" \
  "$TEST_ROOT/production-release-preview.txt" \
  "$TEST_ROOT/first.diff" \
  "$TEST_ROOT/production-release.yaml.sha256" \
  dddddddddddddddddddddddddddddddddddddddd \
  "$first_tree_hash" \
  v1.36.0 \
  12345 \
  2 \
  false \
  eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee \
  ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff \
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  true >/dev/null 2>&1; then
  echo 'tampered approved preview contents must fail verification' >&2
  exit 1
fi

PATH="$TEST_ROOT/bin:$PATH" FAKE_DIFF_STATUS=1 \
  FAKE_DIFF_BODY='sanitized-release-diff' \
  "$CAPTURE" "$manifest" "$diff_file" "$expected_change" >/dev/null

if PATH="$TEST_ROOT/bin:$PATH" FAKE_DIFF_STATUS=1 \
  FAKE_DIFF_BODY='unexpected-valid-release-diff' \
  "$CAPTURE" "$manifest" "$diff_file" "$expected_change" >/dev/null 2>&1; then
  echo 'a changed valid diff must fail before production mutation' >&2
  exit 1
fi

if PATH="$TEST_ROOT/bin:$PATH" FAKE_DIFF_STATUS=1 \
  FAKE_DIFF_BODY='sanitized-release-diff' \
  "$CAPTURE" "$manifest" "$diff_file" '' >/dev/null 2>&1; then
  echo 'a missing approved diff hash must fail before production mutation' >&2
  exit 1
fi

if PATH="$TEST_ROOT/bin:$PATH" FAKE_DIFF_STATUS=2 \
  "$CAPTURE" "$manifest" "$diff_file" >/dev/null 2>&1; then
  echo 'kubectl diff errors must fail the preview' >&2
  exit 1
fi

if PATH="$TEST_ROOT/bin:$PATH" FAKE_DRY_RUN_STATUS=1 \
  "$CAPTURE" "$manifest" "$diff_file" >/dev/null 2>&1; then
  echo 'server-side dry-run errors must fail the preview' >&2
  exit 1
fi

if PATH="$TEST_ROOT/bin:$PATH" FAKE_GET_STATUS=1 \
  "$CAPTURE" "$manifest" "$diff_file" >/dev/null 2>&1; then
  echo 'legacy removal inventory failures must fail the preview' >&2
  exit 1
fi

printf 'capture_release_diff=ok\n'
