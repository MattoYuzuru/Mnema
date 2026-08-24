#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
BACKUP_SCRIPTS="$REPO_ROOT/scripts/backup"
POLICY_RENDERER="$BACKUP_SCRIPTS/render_bucket_policy.py"
CRONJOB="$REPO_ROOT/k8s/backup/cronjob.yaml"
RESTORE_MANIFEST="$REPO_ROOT/k8s/backup/restore-drill.yaml"
RESTORE_BOUNDARY="$REPO_ROOT/k8s/backup/restore-boundary.yaml"
RECOVERY_WORKFLOW="$REPO_ROOT/.github/workflows/database-recovery.yaml"
PLATFORM_APPLY="$REPO_ROOT/scripts/apply-backup-platform.sh"
PROMETHEUS_RULES="$REPO_ROOT/k8s/observability/12-prometheus-rules.yaml"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-backup-contract.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

for script in backup.sh upload.sh download.sh restore.sh; do
  sh -n "$BACKUP_SCRIPTS/$script"
done
sh -n "$PLATFORM_APPLY"

grep -Fq 'concurrencyPolicy: Forbid' "$CRONJOB"
grep -Fq 'timeZone: Etc/UTC' "$CRONJOB"
grep -Fq 'automountServiceAccountToken: false' "$CRONJOB"
grep -Fq 'RETENTION_POLICY_ID' "$CRONJOB"
grep -Fq 'KMS_KEY_ID' "$CRONJOB"
grep -Fq 'pg_try_advisory_lock' "$BACKUP_SCRIPTS/backup.sh"
grep -Fq 'backup_error=lock_contended' "$BACKUP_SCRIPTS/backup.sh"
if grep -Eq '(^|[[:space:]])eval([[:space:]]|$)' \
  "$BACKUP_SCRIPTS/backup.sh" \
  "$BACKUP_SCRIPTS/upload.sh" \
  "$BACKUP_SCRIPTS/download.sh" \
  "$BACKUP_SCRIPTS/restore.sh"; then
  echo 'Backup scripts must not use eval for indirect environment reads' >&2
  exit 1
fi
if grep -Fq 'FROM pg_sequences' "$BACKUP_SCRIPTS/reconcile.sql"; then
  echo 'Snapshot reconciliation must not claim non-MVCC sequence state' >&2
  exit 1
fi
grep -Fq 'APPLY_CHANGES=${APPLY_CHANGES:-false}' "$PLATFORM_APPLY"
grep -Fq 'MINIMUM_FREE_GIB=${MINIMUM_FREE_GIB:-60}' "$PLATFORM_APPLY"
grep -Fq 'kubectl diff -f' "$PLATFORM_APPLY"
grep -Fq 'kubectl -n prod get secret mnema-backup-secrets' "$PLATFORM_APPLY"
grep -Fq 'rollout status statefulset/prometheus' "$PLATFORM_APPLY"
grep -Fq 'Production PostgreSQL must remain on the reviewed PostgreSQL 16 source boundary' "$PLATFORM_APPLY"
grep -Fq "storageclass local-path" "$PLATFORM_APPLY"
grep -Fq 'A production backup Job is active' "$PLATFORM_APPLY"
grep -Fq 'The restore boundary is busy' "$PLATFORM_APPLY"
grep -Fq -- '--from-file="$REPO_ROOT/scripts/backup/backup.sh"' "$PLATFORM_APPLY"
image_count=$(grep -E -c '^[[:space:]]+image:' "$CRONJOB" || true)
pinned_image_count=$(grep -E -c '^[[:space:]]+image: [^[:space:]]+@sha256:[0-9a-f]{64}$' "$CRONJOB" || true)
if [ "$image_count" -ne 2 ] || [ "$pinned_image_count" -ne "$image_count" ]; then
  echo 'Every backup image must be pinned by sha256 digest' >&2
  exit 1
fi

grep -Fq 'namespace: mnema-restore-drill' "$RESTORE_MANIFEST"
grep -Fq 'name: mnema-recovery' "$RESTORE_BOUNDARY"
grep -Fq 'name: mnema-restore-boundary' "$RESTORE_BOUNDARY"
grep -Fq 'pod-security.kubernetes.io/enforce: restricted' "$RESTORE_BOUNDARY"
grep -Fq 'requests.ephemeral-storage: 45Gi' "$RESTORE_BOUNDARY"
grep -Fq 'limits.ephemeral-storage: 50Gi' "$RESTORE_BOUNDARY"
if grep -Fq 'resourceNames: ["data-postgres-0"]' "$RESTORE_BOUNDARY"; then
  echo 'Recovery credential must not delete storage because drills use ephemeral data' >&2
  exit 1
fi
if grep -Eq '^kind: Cluster(Role|RoleBinding)$' "$RESTORE_BOUNDARY"; then
  echo 'Recovery boundary must not grant cluster-scoped RBAC' >&2
  exit 1
fi
grep -Fq 'postgres.mnema-restore-drill.svc.cluster.local' "$RESTORE_MANIFEST"
grep -Fq 'sizeLimit: 20Gi' "$RESTORE_MANIFEST"
if grep -Fq 'volumeClaimTemplates:' "$RESTORE_MANIFEST"; then
  echo 'Restore drills must not leave persistent backing volumes' >&2
  exit 1
fi
grep -Fq 'name: default-deny-ingress' "$RESTORE_BOUNDARY"
grep -Fq 'name: default-deny-egress' "$RESTORE_BOUNDARY"
grep -Fq 'cidr: ::/0' "$RESTORE_BOUNDARY"
for forbidden_range in \
  10.0.0.0/8 \
  100.64.0.0/10 \
  127.0.0.0/8 \
  169.254.0.0/16 \
  172.16.0.0/12 \
  192.168.0.0/16 \
  fc00::/7 \
  fe80::/10
do
  grep -Fq -- "- $forbidden_range" "$RESTORE_BOUNDARY"
done
if grep -Fq 'resources: ["networkpolicies"]' "$RESTORE_BOUNDARY"; then
  echo 'Recovery credential must not mutate persistent network policy boundaries' >&2
  exit 1
fi
if grep -Eq 'namespace:[[:space:]]+prod|postgres\.prod\.' "$RESTORE_MANIFEST"; then
  echo 'Restore manifest must never reference production' >&2
  exit 1
fi

grep -Fq 'secrets.PROD_RECOVERY_KUBECONFIG_B64' "$RECOVERY_WORKFLOW"
if grep -Fq 'secrets.PROD_KUBECONFIG_B64' "$RECOVERY_WORKFLOW"; then
  echo 'Recovery workflow must not use the production deployment credential' >&2
  exit 1
fi
for required in \
  PROD_BACKUP_S3_ENDPOINT \
  PROD_BACKUP_S3_REGION \
  PROD_BACKUP_S3_ACCESS_KEY_ID \
  PROD_BACKUP_S3_SECRET_ACCESS_KEY \
  PROD_BACKUP_S3_BUCKET \
  PROD_BACKUP_S3_PREFIX \
  PROD_BACKUP_KMS_KEY_ID; do
  grep -Fq "secrets.$required" "$RECOVERY_WORKFLOW"
done
grep -Fq 'AWS_ENDPOINT_URL" != https://storage.yandexcloud.net' "$RECOVERY_WORKFLOW"
if grep -Fq -- '--from-literal=POSTGRES_PASSWORD=' "$RECOVERY_WORKFLOW"; then
  echo 'Generated restore passwords must not be placed in process arguments' >&2
  exit 1
fi
grep -Fq -- '--from-file=scripts/backup/download.sh' "$RECOVERY_WORKFLOW"
grep -Fq -- '--from-file=scripts/backup/restore.sh' "$RECOVERY_WORKFLOW"
grep -Fq -- '--from-file=scripts/backup/reconcile.sql' "$RECOVERY_WORKFLOW"
if grep -Eq -- '--from-file=scripts/backup([[:space:]]|$)' "$RECOVERY_WORKFLOW"; then
  echo 'Recovery ConfigMap must not mount unrelated backup scripts' >&2
  exit 1
fi
grep -Fq -- '--no-sign-request' "$BACKUP_SCRIPTS/upload.sh"
grep -Fq -- '--no-sign-request' "$BACKUP_SCRIPTS/download.sh"
grep -Fq -- "--if-none-match '*'" "$BACKUP_SCRIPTS/upload.sh"
grep -Fq -- '--if-match "$current_etag"' "$BACKUP_SCRIPTS/upload.sh"
grep -Fq -- '--server-side-encryption aws:kms' "$BACKUP_SCRIPTS/upload.sh"
grep -Fq -- '--acl private' "$BACKUP_SCRIPTS/upload.sh"
grep -Fq 'bucket_versioning_must_be_enabled' "$BACKUP_SCRIPTS/upload.sh"
grep -Fq 'write_once_policy_allows_unconditional_overwrite' "$BACKUP_SCRIPTS/upload.sh"
grep -Fq 'policy_probe_prefix="$S3_PREFIX/postgres/$policy_probe_backup_id"' "$BACKUP_SCRIPTS/upload.sh"
grep -Fq 'for file in database.dump reconciliation.csv capacity.csv checksums.sha256 metadata.env' "$BACKUP_SCRIPTS/upload.sh"
grep -Fq 'CONFIRMATION" != RESTORE_IN_ISOLATED_NAMESPACE' "$RECOVERY_WORKFLOW"
grep -Fq 'name: Refuse a busy restore boundary' "$RECOVERY_WORKFLOW"
grep -Fq 'get configmap mnema-restore-boundary' "$RECOVERY_WORKFLOW"
grep -Fq 'get configmap kube-root-ca.crt' "$RECOVERY_WORKFLOW"
grep -Fq 'timeout-minutes: 210' "$RECOVERY_WORKFLOW"
grep -Fq 'timeout-minutes: 4' "$RECOVERY_WORKFLOW"
grep -Fq -- '--ignore-not-found=true --wait=false' "$RECOVERY_WORKFLOW"
grep -Fq 'kubectl create --request-timeout=30s -f k8s/backup/restore-drill.yaml' "$RECOVERY_WORKFLOW"
if grep -Fq 'delete persistentvolumeclaim data-postgres-0' "$RECOVERY_WORKFLOW"; then
  echo 'Restore cleanup must not depend on deleting persistent storage' >&2
  exit 1
fi
if grep -Eq 'kubectl (create|delete) namespace' "$RECOVERY_WORKFLOW"; then
  echo 'Scoped recovery workflow must not create or delete namespaces' >&2
  exit 1
fi
cleanup_line=$(grep -n 'name: Remove only fixed restore drill resources' "$RECOVERY_WORKFLOW" | cut -d: -f1)
artifact_line=$(grep -n 'name: Upload restore drill evidence' "$RECOVERY_WORKFLOW" | cut -d: -f1)
if [ "$cleanup_line" -ge "$artifact_line" ]; then
  echo 'Restore resources must be removed before the potentially slow artifact upload' >&2
  exit 1
fi

grep -Fq 'MnemaPostgresPersistentVolumeFreeSpaceLow' "$PROMETHEUS_RULES"
grep -Fq 'MnemaPostgresPersistentVolumeFreeSpaceCritical' "$PROMETHEUS_RULES"
grep -Fq 'MnemaPostgresBackupMissing' "$PROMETHEUS_RULES"
grep -Fq 'MnemaPostgresBackupStale' "$PROMETHEUS_RULES"

mkdir -p "$TEST_ROOT/bin" "$TEST_ROOT/object-store" "$TEST_ROOT/backup" "$TEST_ROOT/restore"
S3_BUCKET=test-bucket S3_PREFIX=mnema-backups \
  python3 "$POLICY_RENDERER" > "$TEST_ROOT/policy-fragment.json"
python3 - "$TEST_ROOT/policy-fragment.json" <<'PY'
import json
import sys

fragment = json.load(open(sys.argv[1], encoding="utf-8"))
statements = {statement["Sid"]: statement for statement in fragment["Statement"]}
immutable = statements["DenyImmutableWritesWithoutIfNoneMatch"]
assert immutable["Effect"] == "Deny"
assert immutable["Principal"] == "*"
assert immutable["Action"] == "s3:PutObject"
assert immutable["Resource"] == "arn:aws:s3:::test-bucket/mnema-backups/postgres/*/*"
assert immutable["Condition"] == {"Null": {"s3:if-none-match": "true"}}
latest = statements["DenyUnconditionalLatestPointerWrites"]
assert latest["Resource"] == "arn:aws:s3:::test-bucket/mnema-backups/postgres/latest.env"
assert latest["Condition"] == {
    "Null": {"s3:if-match": "true", "s3:if-none-match": "true"}
}
assert len(statements) == 2
PY
if S3_BUCKET=test-bucket S3_PREFIX='../unsafe' \
  python3 "$POLICY_RENDERER" >/dev/null 2>&1; then
  echo 'Policy renderer must reject an unsafe backup prefix' >&2
  exit 1
fi
cat > "$TEST_ROOT/bin/aws" <<'MOCK_AWS'
#!/bin/sh
set -eu

operation="${1:-} ${2:-}"

value_after() {
  wanted="$1"
  shift
  while [ "$#" -gt 0 ]; do
    if [ "$1" = "$wanted" ]; then
      shift
      printf '%s' "${1:-}"
      return 0
    fi
    shift
  done
  return 1
}

object_etag() {
  sha256sum "$1" | awk '{ print "\"" $1 "\"" }'
}

next_version() {
  counter_file="$AWS_MOCK_ROOT/.version-counter"
  current=0
  if [ -f "$counter_file" ]; then current=$(cat "$counter_file"); fi
  current=$((current + 1))
  printf '%s\n' "$current" > "$counter_file"
  printf 'version-%s' "$current"
}

case "$operation" in
  's3api get-bucket-encryption')
    case "$*" in
      *SSEAlgorithm*) printf '%s\n' "${AWS_MOCK_BUCKET_ENCRYPTION:-aws:kms}" ;;
      *KMSMasterKeyID*) printf '%s\n' "${AWS_MOCK_BUCKET_KMS_KEY:-kms-test}" ;;
      *) exit 2 ;;
    esac
    ;;
  's3api get-bucket-lifecycle-configuration')
    case "$*" in
      *Filter.Prefix*) printf '%s\n' "${AWS_MOCK_RETENTION_PREFIX:-mnema-backups/postgres/}" ;;
      *NoncurrentVersionExpiration.NoncurrentDays*) printf '%s\n' "${AWS_MOCK_NONCURRENT_DAYS:-30}" ;;
      *AbortIncompleteMultipartUpload.DaysAfterInitiation*) printf '%s\n' '7' ;;
      *Expiration.Days*) printf '%s\n' '30' ;;
      *Status*) printf '%s\n' 'Enabled' ;;
      *) exit 2 ;;
    esac
    ;;
  's3api get-bucket-versioning')
    printf '%s\n' "${AWS_MOCK_VERSIONING_STATE:-Enabled}"
    ;;
  's3api put-object')
    body=$(value_after --body "$@")
    key=$(value_after --key "$@")
    encryption=$(value_after --server-side-encryption "$@")
    kms_key=$(value_after --ssekms-key-id "$@")
    acl=$(value_after --acl "$@")
    destination="$AWS_MOCK_ROOT/$key"
    if [ "$encryption" != aws:kms ] || [ "$kms_key" != kms-test ] || [ "$acl" != private ]; then
      printf '%s\n' 'An error occurred (400) when calling PutObject: missing explicit object controls' >&2
      exit 254
    fi

    if_none=false
    if_match=''
    case "$*" in *'--if-none-match *'*) if_none=true ;; esac
    if value=$(value_after --if-match "$@" 2>/dev/null); then if_match=$value; fi
    policy_enforced=${AWS_MOCK_ENFORCE_WRITE_ONCE:-true}
    case "$key" in */postgres/latest.env) pointer=true ;; *) pointer=false ;; esac

    if [ -f "$destination" ]; then
      current_etag=$(object_etag "$destination")
      if [ "$if_none" = true ]; then
        printf '%s\n' 'An error occurred (412) when calling PutObject: Precondition Failed' >&2
        exit 254
      fi
      if [ -n "$if_match" ]; then
        if [ "$if_match" != "$current_etag" ]; then
          printf '%s\n' 'An error occurred (412) when calling PutObject: Precondition Failed' >&2
          exit 254
        fi
        if [ "$policy_enforced" = true ] && [ "$pointer" = false ] && \
           [ "${AWS_MOCK_ALLOW_IMMUTABLE_IF_MATCH:-false}" != true ]; then
          printf '%s\n' 'An error occurred (403) when calling PutObject: AccessDenied' >&2
          exit 254
        fi
      elif [ "$policy_enforced" = true ]; then
        printf '%s\n' 'An error occurred (403) when calling PutObject: AccessDenied' >&2
        exit 254
      fi
    else
      if [ -n "$if_match" ]; then
        printf '%s\n' 'An error occurred (404) when calling PutObject: Not Found' >&2
        exit 254
      fi
      if [ "$if_none" = false ] && [ "$policy_enforced" = true ]; then
        printf '%s\n' 'An error occurred (403) when calling PutObject: AccessDenied' >&2
        exit 254
      fi
    fi

    mkdir -p "$(dirname -- "$destination")"
    cp "$body" "$destination"
    version_id=$(next_version)
    etag=$(object_etag "$destination")
    printf '%s\t%s\n' "$version_id" "$etag"
    ;;
  's3api head-object')
    key=$(value_after --key "$@")
    destination="$AWS_MOCK_ROOT/$key"
    case "$*" in
      *--no-sign-request*)
        if [ "${AWS_MOCK_PUBLIC_OBJECT:-false}" = true ]; then exit 0; fi
        printf '%s\n' 'An error occurred (403) when calling HeadObject: AccessDenied' >&2
        exit 254
        ;;
    esac
    if [ ! -f "$destination" ]; then
      printf '%s\n' 'An error occurred (404) when calling HeadObject: Not Found' >&2
      exit 254
    fi
    case "$*" in
      *SSEKMSKeyId*) printf '%s\n' "${AWS_MOCK_OBJECT_KMS_KEY:-kms-test}" ;;
      *ServerSideEncryption*) printf '%s\n' "${AWS_MOCK_OBJECT_ENCRYPTION:-aws:kms}" ;;
      *ETag*) object_etag "$destination"; printf '\n' ;;
      *) exit 2 ;;
    esac
    ;;
  's3api get-object')
    key=$(value_after --key "$@")
    source_path="$AWS_MOCK_ROOT/$key"
    destination_path=''
    previous=''
    for argument in "$@"; do
      case "$previous" in
        --bucket|--key|--endpoint-url|--query|--output) previous=''; continue ;;
      esac
      case "$argument" in
        --bucket|--key|--endpoint-url|--query|--output) previous=$argument ;;
        s3api|get-object) ;;
        --*) ;;
        *) destination_path=$argument ;;
      esac
    done
    if [ ! -f "$source_path" ]; then
      printf '%s\n' 'An error occurred (404) when calling GetObject: NoSuchKey' >&2
      exit 254
    fi
    cp "$source_path" "$destination_path"
    object_etag "$source_path"
    printf '\n'
    ;;
  's3 cp')
    source_path=$3
    destination_path=$4
    case "$source_path" in
      s3://test-bucket/*)
        object_key=${source_path#s3://test-bucket/}
        cp "$AWS_MOCK_ROOT/$object_key" "$destination_path"
        ;;
      *)
        printf '%s\n' 'Unexpected high-level upload' >&2
        exit 2
        ;;
    esac
    ;;
  *)
    printf 'unexpected aws call: %s\n' "$*" >&2
    exit 2
    ;;
esac
MOCK_AWS
chmod +x "$TEST_ROOT/bin/aws"

printf '%s' 'deterministic-test-dump' > "$TEST_ROOT/backup/database.dump"
printf '%s\n' 'kind,object_name,row_count,checksum_left,checksum_right' 'table,auth.users,2,10,20' > "$TEST_ROOT/backup/reconciliation.csv"
printf '%s\n' 'object_name,total_bytes,table_bytes,index_bytes' 'auth.users,4096,2048,2048' > "$TEST_ROOT/backup/capacity.csv"
(
  cd "$TEST_ROOT/backup"
  sha256sum database.dump reconciliation.csv capacity.csv > checksums.sha256
)
dump_sha=$(awk '$2 == "database.dump" { print $1 }' "$TEST_ROOT/backup/checksums.sha256")
reconciliation_sha=$(awk '$2 == "reconciliation.csv" { print $1 }' "$TEST_ROOT/backup/checksums.sha256")
capacity_sha=$(awk '$2 == "capacity.csv" { print $1 }' "$TEST_ROOT/backup/checksums.sha256")
dump_bytes=$(wc -c < "$TEST_ROOT/backup/database.dump" | tr -d ' ')
cat > "$TEST_ROOT/backup/metadata.env" <<EOF
FORMAT_VERSION=1
BACKUP_ID=20260819T020304Z-00000000-0000-4000-8000-000000000001
BACKUP_SNAPSHOT_EPOCH=100
BACKUP_DUMP_COMPLETED_EPOCH=110
SOURCE_SERVER_VERSION_NUM=160010
ACCOUNT_COUNT=2
DUMP_BYTES=$dump_bytes
DUMP_SHA256=$dump_sha
RECONCILIATION_SHA256=$reconciliation_sha
CAPACITY_SHA256=$capacity_sha
EOF
touch "$TEST_ROOT/backup/READY"

set_backup_identity() {
  backup_id="$1"
  snapshot_epoch="$2"
  completed_epoch="$3"
  awk -F= -v backup_id="$backup_id" -v snapshot_epoch="$snapshot_epoch" -v completed_epoch="$completed_epoch" '
    $1 == "BACKUP_ID" { print "BACKUP_ID=" backup_id; next }
    $1 == "BACKUP_SNAPSHOT_EPOCH" { print "BACKUP_SNAPSHOT_EPOCH=" snapshot_epoch; next }
    $1 == "BACKUP_DUMP_COMPLETED_EPOCH" { print "BACKUP_DUMP_COMPLETED_EPOCH=" completed_epoch; next }
    { print }
  ' "$TEST_ROOT/backup/metadata.env" > "$TEST_ROOT/backup/metadata.env.next"
  mv "$TEST_ROOT/backup/metadata.env.next" "$TEST_ROOT/backup/metadata.env"
  rm -f "$TEST_ROOT/backup/UPLOAD_FAILED" "$TEST_ROOT/backup/UPLOADED"
}

export PATH="$TEST_ROOT/bin:$PATH"
export AWS_MOCK_ROOT="$TEST_ROOT/object-store"
export AWS_ENDPOINT_URL=https://storage.example.test
export EXPECTED_AWS_ENDPOINT_URL=https://storage.example.test
export AWS_REGION=ru-central1
export AWS_ACCESS_KEY_ID=test-key
export AWS_SECRET_ACCESS_KEY=test-secret
export S3_BUCKET=test-bucket
export S3_PREFIX=mnema-backups
export KMS_KEY_ID=kms-test

BACKUP_DIR="$TEST_ROOT/backup" RETENTION_POLICY_ID=retention-test \
  "$BACKUP_SCRIPTS/upload.sh" > "$TEST_ROOT/backup-report.json"
python3 "$BACKUP_SCRIPTS/validate_report.py" --kind backup --report "$TEST_ROOT/backup-report.json"

BACKUP_DIR="$TEST_ROOT/restore" BACKUP_ID=latest \
  "$BACKUP_SCRIPTS/download.sh"
cmp "$TEST_ROOT/backup/database.dump" "$TEST_ROOT/restore/database.dump"
cmp "$TEST_ROOT/backup/reconciliation.csv" "$TEST_ROOT/restore/reconciliation.csv"

if BACKUP_DIR="$TEST_ROOT/backup" RETENTION_POLICY_ID=retention-test \
  UPDATE_LATEST_POINTER=false \
  "$BACKUP_SCRIPTS/upload.sh" >/dev/null 2> "$TEST_ROOT/duplicate-upload.err"; then
  echo 'Uploader must not overwrite an existing immutable backup set' >&2
  exit 1
fi
grep -Fxq 'upload_error=immutable_object_already_exists' "$TEST_ROOT/duplicate-upload.err"

public_backup_id=20260819T020305Z-00000000-0000-4000-8000-000000000002
set_backup_identity "$public_backup_id" 200 210
if AWS_MOCK_PUBLIC_OBJECT=true \
  BACKUP_DIR="$TEST_ROOT/backup" \
  RETENTION_POLICY_ID=retention-test \
  UPDATE_LATEST_POINTER=false \
  "$BACKUP_SCRIPTS/upload.sh" >/dev/null 2> "$TEST_ROOT/public-object.err"; then
  echo 'Uploader must reject an anonymously readable backup object' >&2
  exit 1
fi
grep -Fxq 'upload_error=object_anonymously_readable' "$TEST_ROOT/public-object.err"
test ! -e "$TEST_ROOT/object-store/mnema-backups/postgres/$public_backup_id/database.dump"

wrong_kms_backup_id=20260819T020306Z-00000000-0000-4000-8000-000000000003
set_backup_identity "$wrong_kms_backup_id" 300 310
if AWS_MOCK_BUCKET_KMS_KEY=wrong-kms \
  BACKUP_DIR="$TEST_ROOT/backup" \
  RETENTION_POLICY_ID=retention-test \
  "$BACKUP_SCRIPTS/upload.sh" >/dev/null 2> "$TEST_ROOT/wrong-kms.err"; then
  echo 'Uploader must reject a mismatched bucket KMS key before uploading data' >&2
  exit 1
fi
grep -Fxq 'upload_error=bucket_default_kms_key_mismatch' "$TEST_ROOT/wrong-kms.err"
test ! -e "$TEST_ROOT/object-store/mnema-backups/postgres/$wrong_kms_backup_id/database.dump"

wrong_object_kms_backup_id=20260819T020312Z-00000000-0000-4000-8000-000000000009
set_backup_identity "$wrong_object_kms_backup_id" 350 360
if AWS_MOCK_OBJECT_KMS_KEY=wrong-kms \
  BACKUP_DIR="$TEST_ROOT/backup" \
  RETENTION_POLICY_ID=retention-test \
  "$BACKUP_SCRIPTS/upload.sh" >/dev/null 2> "$TEST_ROOT/wrong-object-kms.err"; then
  echo 'Uploader must validate the harmless boundary object before uploading data' >&2
  exit 1
fi
grep -Fxq 'upload_error=object_not_encrypted_with_expected_kms_key' "$TEST_ROOT/wrong-object-kms.err"
test ! -e "$TEST_ROOT/object-store/mnema-backups/postgres/$wrong_object_kms_backup_id/database.dump"

unsafe_policy_backup_id=20260819T020307Z-00000000-0000-4000-8000-000000000004
set_backup_identity "$unsafe_policy_backup_id" 400 410
if AWS_MOCK_ENFORCE_WRITE_ONCE=false \
  BACKUP_DIR="$TEST_ROOT/backup" \
  RETENTION_POLICY_ID=retention-test \
  "$BACKUP_SCRIPTS/upload.sh" >/dev/null 2> "$TEST_ROOT/unsafe-policy.err"; then
  echo 'Uploader must reject a bucket that allows unconditional overwrite' >&2
  exit 1
fi
grep -Fxq 'upload_error=write_once_policy_allows_unconditional_overwrite' "$TEST_ROOT/unsafe-policy.err"
test ! -e "$TEST_ROOT/object-store/mnema-backups/postgres/$unsafe_policy_backup_id/database.dump"

unsafe_if_match_backup_id=20260819T020313Z-00000000-0000-4000-8000-00000000000a
set_backup_identity "$unsafe_if_match_backup_id" 450 460
if AWS_MOCK_ALLOW_IMMUTABLE_IF_MATCH=true \
  BACKUP_DIR="$TEST_ROOT/backup" \
  RETENTION_POLICY_ID=retention-test \
  "$BACKUP_SCRIPTS/upload.sh" >/dev/null 2> "$TEST_ROOT/unsafe-if-match.err"; then
  echo 'Uploader must reject a bucket that allows If-Match overwrite of backup sets' >&2
  exit 1
fi
grep -Fxq 'upload_error=write_once_policy_allows_if_match_overwrite' "$TEST_ROOT/unsafe-if-match.err"
test ! -e "$TEST_ROOT/object-store/mnema-backups/postgres/$unsafe_if_match_backup_id/database.dump"

unversioned_backup_id=20260819T020308Z-00000000-0000-4000-8000-000000000005
set_backup_identity "$unversioned_backup_id" 500 510
if AWS_MOCK_VERSIONING_STATE=Suspended \
  BACKUP_DIR="$TEST_ROOT/backup" \
  RETENTION_POLICY_ID=retention-test \
  "$BACKUP_SCRIPTS/upload.sh" >/dev/null 2> "$TEST_ROOT/unversioned.err"; then
  echo 'Uploader must require active bucket versioning before uploading data' >&2
  exit 1
fi
grep -Fxq 'upload_error=bucket_versioning_must_be_enabled' "$TEST_ROOT/unversioned.err"
test ! -e "$TEST_ROOT/object-store/mnema-backups/postgres/$unversioned_backup_id/database.dump"

mkdir -p "$TEST_ROOT/restore-public"
if AWS_MOCK_PUBLIC_OBJECT=true \
  BACKUP_DIR="$TEST_ROOT/restore-public" \
  BACKUP_ID=20260819T020304Z-00000000-0000-4000-8000-000000000001 \
  "$BACKUP_SCRIPTS/download.sh" >/dev/null 2> "$TEST_ROOT/public-download.err"; then
  echo 'Downloader must reject an anonymously readable backup object' >&2
  exit 1
fi
grep -Fxq 'download_error=object_anonymously_readable' "$TEST_ROOT/public-download.err"

if AWS_ENDPOINT_URL=https://unexpected.example.test \
  BACKUP_DIR="$TEST_ROOT/backup" \
  RETENTION_POLICY_ID=retention-test \
  "$BACKUP_SCRIPTS/upload.sh" >/dev/null 2> "$TEST_ROOT/unexpected-endpoint.err"; then
  echo 'Uploader must reject an unexpected object-storage origin' >&2
  exit 1
fi
grep -Fxq 'upload_error=unexpected_object_storage_endpoint' "$TEST_ROOT/unexpected-endpoint.err"

newer_backup_id=20260819T020309Z-00000000-0000-4000-8000-000000000006
set_backup_identity "$newer_backup_id" 600 610
BACKUP_DIR="$TEST_ROOT/backup" RETENTION_POLICY_ID=retention-test \
  "$BACKUP_SCRIPTS/upload.sh" > "$TEST_ROOT/newer-backup-report.json"
python3 "$BACKUP_SCRIPTS/validate_report.py" --kind backup --report "$TEST_ROOT/newer-backup-report.json"
grep -Fxq "BACKUP_ID=$newer_backup_id" "$TEST_ROOT/object-store/mnema-backups/postgres/latest.env"

cp "$TEST_ROOT/object-store/mnema-backups/postgres/latest.env" "$TEST_ROOT/latest-before.env"
older_backup_id=20260819T020310Z-00000000-0000-4000-8000-000000000007
set_backup_identity "$older_backup_id" 550 560
BACKUP_DIR="$TEST_ROOT/backup" RETENTION_POLICY_ID=retention-test \
  "$BACKUP_SCRIPTS/upload.sh" > "$TEST_ROOT/older-backup-report.json"
python3 "$BACKUP_SCRIPTS/validate_report.py" --kind backup --report "$TEST_ROOT/older-backup-report.json"
python3 -c 'import json, sys; report = json.load(open(sys.argv[1], encoding="utf-8")); assert report["latestPointerUpdated"] is False' "$TEST_ROOT/older-backup-report.json"
cmp "$TEST_ROOT/latest-before.env" "$TEST_ROOT/object-store/mnema-backups/postgres/latest.env"

manual_backup_id=20260819T020311Z-00000000-0000-4000-8000-000000000008
set_backup_identity "$manual_backup_id" 700 710
BACKUP_DIR="$TEST_ROOT/backup" RETENTION_POLICY_ID=retention-test UPDATE_LATEST_POINTER=false \
  "$BACKUP_SCRIPTS/upload.sh" > "$TEST_ROOT/manual-backup-report.json"
python3 "$BACKUP_SCRIPTS/validate_report.py" --kind backup --report "$TEST_ROOT/manual-backup-report.json"
python3 -c 'import json, sys; report = json.load(open(sys.argv[1], encoding="utf-8")); assert report["latestPointerUpdated"] is False' "$TEST_ROOT/manual-backup-report.json"
cmp "$TEST_ROOT/latest-before.env" "$TEST_ROOT/object-store/mnema-backups/postgres/latest.env"

printf '%s\n' 'UNEXPECTED_FIELD=must-not-be-sourced' >> "$TEST_ROOT/object-store/mnema-backups/postgres/latest.env"
if BACKUP_DIR="$TEST_ROOT/restore" BACKUP_ID=latest \
  "$BACKUP_SCRIPTS/download.sh" >/dev/null 2>&1; then
  echo 'Tampered latest pointer must be rejected' >&2
  exit 1
fi

mkdir -p "$TEST_ROOT/restore-corrupt"
printf '%s  %s\n' 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' '../../etc/passwd' >> \
  "$TEST_ROOT/object-store/mnema-backups/postgres/20260819T020304Z-00000000-0000-4000-8000-000000000001/checksums.sha256"
if BACKUP_DIR="$TEST_ROOT/restore-corrupt" \
  BACKUP_ID=20260819T020304Z-00000000-0000-4000-8000-000000000001 \
  "$BACKUP_SCRIPTS/download.sh" >/dev/null 2>&1; then
  echo 'Downloader must reject unexpected checksum paths' >&2
  exit 1
fi

if TARGET_NAMESPACE=prod \
  DRILL_STARTED_EPOCH=100 \
  PGHOST=postgres.prod.svc.cluster.local \
  PGPORT=5432 \
  PGDATABASE=mnema \
  PGUSER=mnema \
  PGPASSWORD=test-password \
  "$BACKUP_SCRIPTS/restore.sh" >/dev/null 2>&1; then
  echo 'Restore script must reject production targets before connecting' >&2
  exit 1
fi

if AWS_MOCK_RETENTION_PREFIX=unrelated-prefix/ \
  BACKUP_DIR="$TEST_ROOT/backup" \
  RETENTION_POLICY_ID=retention-test \
  "$BACKUP_SCRIPTS/upload.sh" >/dev/null 2>&1; then
  echo 'Uploader must reject a lifecycle rule for another prefix' >&2
  exit 1
fi

mkdir -p "$TEST_ROOT/storage"
cat > "$TEST_ROOT/bin/df" <<'MOCK_DF'
#!/bin/sh
set -eu
printf '%s\n' \
  'Filesystem 1024-blocks Used Available Capacity Mounted on' \
  "mock 200000000 1 100000000 1% $2"
MOCK_DF
cat > "$TEST_ROOT/bin/kubectl" <<'MOCK_KUBECTL'
#!/bin/sh
set -eu

case "$*" in
  '-n prod get statefulset postgres -o jsonpath={.status.readyReplicas}') printf '1' ;;
  *'get statefulset postgres -o jsonpath={.spec.template.spec.containers'*'.image}')
    printf '%s' "${FAKE_POSTGRES_IMAGE:-postgres:16-alpine@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}"
    ;;
  'get storageclass local-path -o jsonpath={.provisioner}') printf 'rancher.io/local-path' ;;
  'get storageclass local-path -o jsonpath={.metadata.annotations.storageclass\.kubernetes\.io/is-default-class}') printf 'true' ;;
  '-n prod get persistentvolumeclaim data-postgres-0 -o jsonpath={.status.phase}') printf 'Bound' ;;
  '-n prod get persistentvolumeclaim data-postgres-0 -o jsonpath={.spec.storageClassName}') printf 'local-path' ;;
  '-n prod get persistentvolumeclaim data-postgres-0 -o jsonpath={.status.capacity.storage}') printf '15Gi' ;;
  '-n prod get secret mnema-secrets' | '-n prod get secret mnema-backup-secrets') ;;
  '-n observability get statefulset prometheus -o jsonpath={.status.readyReplicas}') printf '1' ;;
  '-n prod get jobs -l app.kubernetes.io/name=mnema-postgres-backup -o jsonpath={range .items[*]}{.metadata.name}{"|"}{.status.active}{"\n"}{end}')
    if [ "${FAKE_ACTIVE_BACKUP:-false}" = true ]; then printf 'active-backup|1\n'; fi
    ;;
  'get namespace mnema-restore-drill') ;;
  '-n mnema-restore-drill get pods,jobs.batch,statefulsets.apps,persistentvolumeclaims,services -o name')
    if [ "${FAKE_BUSY_RESTORE:-false}" = true ]; then printf 'pod/busy-restore\n'; fi
    ;;
  '-n prod create configmap mnema-backup-scripts '*'-o yaml')
    printf '%s\n' 'apiVersion: v1' 'kind: ConfigMap' 'metadata:' '  name: mnema-backup-scripts'
    ;;
  'diff -f '*) ;;
  *)
    printf 'unexpected kubectl call: %s\n' "$*" >&2
    exit 2
    ;;
esac
MOCK_KUBECTL
chmod +x "$TEST_ROOT/bin/df" "$TEST_ROOT/bin/kubectl"

STORAGE_PATH="$TEST_ROOT/storage" \
  "$PLATFORM_APPLY" > "$TEST_ROOT/platform-preview.out"
grep -Fq 'backup_platform=previewed' "$TEST_ROOT/platform-preview.out"

if FAKE_ACTIVE_BACKUP=true STORAGE_PATH="$TEST_ROOT/storage" \
  "$PLATFORM_APPLY" >/dev/null 2> "$TEST_ROOT/active-backup.err"; then
  echo 'Platform apply must reject an active production backup Job' >&2
  exit 1
fi
grep -Fxq 'A production backup Job is active; refuse to change its mounted scripts or schedule' \
  "$TEST_ROOT/active-backup.err"

if FAKE_BUSY_RESTORE=true STORAGE_PATH="$TEST_ROOT/storage" \
  "$PLATFORM_APPLY" >/dev/null 2> "$TEST_ROOT/busy-restore.err"; then
  echo 'Platform apply must reject a busy restore boundary' >&2
  exit 1
fi
grep -Fxq 'The restore boundary is busy; refuse to change its policy or quota' \
  "$TEST_ROOT/busy-restore.err"

if FAKE_POSTGRES_IMAGE=postgres:18 STORAGE_PATH="$TEST_ROOT/storage" \
  "$PLATFORM_APPLY" >/dev/null 2> "$TEST_ROOT/wrong-source.err"; then
  echo 'Platform apply must reject an unreviewed PostgreSQL source major' >&2
  exit 1
fi
grep -Fxq 'Production PostgreSQL must remain on the reviewed PostgreSQL 16 source boundary' \
  "$TEST_ROOT/wrong-source.err"

printf 'backup_contract=ok\n'
