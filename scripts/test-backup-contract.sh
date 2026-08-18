#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
BACKUP_SCRIPTS="$REPO_ROOT/scripts/backup"
CRONJOB="$REPO_ROOT/k8s/backup/cronjob.yaml"
RESTORE_MANIFEST="$REPO_ROOT/k8s/backup/restore-drill.yaml"
PRODUCTION_WORKFLOW="$REPO_ROOT/.github/workflows/production-deploy.yaml"
RECOVERY_WORKFLOW="$REPO_ROOT/.github/workflows/database-recovery.yaml"
PROMETHEUS_RULES="$REPO_ROOT/k8s/observability/12-prometheus-rules.yaml"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-backup-contract.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT HUP INT TERM

for script in backup.sh upload.sh download.sh restore.sh; do
  sh -n "$BACKUP_SCRIPTS/$script"
done

grep -Fq 'concurrencyPolicy: Forbid' "$CRONJOB"
grep -Fq 'timeZone: Etc/UTC' "$CRONJOB"
grep -Fq 'automountServiceAccountToken: false' "$CRONJOB"
grep -Fq 'RETENTION_POLICY_ID' "$CRONJOB"
grep -Fq 'KMS_KEY_ID' "$CRONJOB"
image_count=$(grep -E -c '^[[:space:]]+image:' "$CRONJOB" || true)
pinned_image_count=$(grep -E -c '^[[:space:]]+image: [^[:space:]]+@sha256:[0-9a-f]{64}$' "$CRONJOB" || true)
if [ "$image_count" -ne 2 ] || [ "$pinned_image_count" -ne "$image_count" ]; then
  echo 'Every backup image must be pinned by sha256 digest' >&2
  exit 1
fi

grep -Fq 'namespace: mnema-restore-drill' "$RESTORE_MANIFEST"
grep -Fq 'postgres.mnema-restore-drill.svc.cluster.local' "$RESTORE_MANIFEST"
grep -Fq 'name: default-deny-ingress' "$RESTORE_MANIFEST"
grep -Fq 'name: default-deny-egress' "$RESTORE_MANIFEST"
if grep -Eq 'namespace:[[:space:]]+prod|postgres\.prod\.' "$RESTORE_MANIFEST"; then
  echo 'Restore manifest must never reference production' >&2
  exit 1
fi

for required in \
  PROD_BACKUP_S3_ENDPOINT \
  PROD_BACKUP_S3_REGION \
  PROD_BACKUP_S3_ACCESS_KEY_ID \
  PROD_BACKUP_S3_SECRET_ACCESS_KEY \
  PROD_BACKUP_S3_BUCKET \
  PROD_BACKUP_S3_PREFIX \
  PROD_BACKUP_RETENTION_POLICY_ID \
  PROD_BACKUP_KMS_KEY_ID; do
  grep -Fq "secrets.$required" "$PRODUCTION_WORKFLOW"
  grep -Fq "secrets.$required" "$RECOVERY_WORKFLOW"
done
grep -Fq 'kubectl apply -f k8s/backup/cronjob.yaml' "$PRODUCTION_WORKFLOW"
grep -Fq 'pre-migration-backup:BACKUP_PRODUCTION_DATABASE' "$RECOVERY_WORKFLOW"
grep -Fq 'restore-drill:RESTORE_IN_ISOLATED_NAMESPACE' "$RECOVERY_WORKFLOW"
grep -Fq 'UPDATE_LATEST_POINTER' "$RECOVERY_WORKFLOW"
grep -Fq 'pointer.update({"name": "UPDATE_LATEST_POINTER", "value": "false"})' "$RECOVERY_WORKFLOW"
grep -Fq 'Refuse a pre-existing restore namespace' "$RECOVERY_WORKFLOW"
grep -Fq 'recovery-run-id' "$RECOVERY_WORKFLOW"

grep -Fq 'MnemaPostgresPersistentVolumeFreeSpaceLow' "$PROMETHEUS_RULES"
grep -Fq 'MnemaPostgresPersistentVolumeFreeSpaceCritical' "$PROMETHEUS_RULES"
grep -Fq 'MnemaPostgresBackupMissing' "$PROMETHEUS_RULES"
grep -Fq 'MnemaPostgresBackupStale' "$PROMETHEUS_RULES"

mkdir -p "$TEST_ROOT/bin" "$TEST_ROOT/object-store" "$TEST_ROOT/backup" "$TEST_ROOT/restore"
cat > "$TEST_ROOT/bin/aws" <<'MOCK_AWS'
#!/bin/sh
set -eu

if [ "$1 $2" = 's3api get-bucket-encryption' ]; then
  case "$*" in
    *SSEAlgorithm*) printf '%s\n' 'aws:kms' ;;
    *KMSMasterKeyID*) printf '%s\n' 'kms-test' ;;
    *) exit 2 ;;
  esac
  exit 0
fi
if [ "$1 $2" = 's3api get-bucket-lifecycle-configuration' ]; then
  case "$*" in
    *Filter.Prefix*) printf '%s\n' "${AWS_MOCK_RETENTION_PREFIX:-mnema-backups/postgres/}" ;;
    *NoncurrentVersionExpiration.NoncurrentDays*) printf '%s\n' '30' ;;
    *AbortIncompleteMultipartUpload.DaysAfterInitiation*) printf '%s\n' '7' ;;
    *Expiration.Days*) printf '%s\n' '30' ;;
    *Status*) printf '%s\n' 'Enabled' ;;
    *) exit 2 ;;
  esac
  exit 0
fi
if [ "$1 $2" = 's3api get-bucket-versioning' ]; then
  printf '%s\n' 'None'
  exit 0
fi
if [ "$1 $2" = 's3api head-object' ]; then
  case "$*" in
    *SSEKMSKeyId*) printf '%s\n' 'kms-test' ;;
    *ServerSideEncryption*) printf '%s\n' 'aws:kms' ;;
    *) exit 2 ;;
  esac
  exit 0
fi
if [ "$1 $2" = 's3 cp' ]; then
  source_path=$3
  destination_path=$4
  case "$source_path" in
    s3://test-bucket/*)
      object_key=${source_path#s3://test-bucket/}
      cp "$AWS_MOCK_ROOT/$object_key" "$destination_path"
      ;;
    *)
      object_key=${destination_path#s3://test-bucket/}
      destination="$AWS_MOCK_ROOT/$object_key"
      mkdir -p "$(dirname -- "$destination")"
      cp "$source_path" "$destination"
      ;;
  esac
  exit 0
fi

exit 2
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

export PATH="$TEST_ROOT/bin:$PATH"
export AWS_MOCK_ROOT="$TEST_ROOT/object-store"
export AWS_ENDPOINT_URL=https://storage.example.test
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

cp "$TEST_ROOT/object-store/mnema-backups/postgres/latest.env" "$TEST_ROOT/latest-before.env"
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

printf 'backup_contract=ok\n'
