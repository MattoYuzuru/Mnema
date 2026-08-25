#!/bin/sh
set -eu

umask 007

RESTORE_DIR=${RESTORE_DIR:-/restore}
EXPECTED_NAMESPACE=mnema-restore-drill
EXPECTED_HOST=postgres.mnema-restore-drill.svc.cluster.local

required() {
  name="$1"
  value="$2"
  if [ -z "$value" ]; then
    echo "restore_error=missing_${name}" >&2
    exit 1
  fi
}

metadata_value() {
  key="$1"
  file="$2"
  value=$(awk -F= -v key="$key" '$1 == key { if (found) exit 2; found = 1; print substr($0, length(key) + 2) } END { if (!found) exit 3 }' "$file") || {
    echo "restore_error=invalid_metadata_${key}" >&2
    exit 1
  }
  printf '%s' "$value"
}

required TARGET_NAMESPACE "${TARGET_NAMESPACE:-}"
required DRILL_STARTED_EPOCH "${DRILL_STARTED_EPOCH:-}"
required PGHOST "${PGHOST:-}"
required PGPORT "${PGPORT:-}"
required PGDATABASE "${PGDATABASE:-}"
required PGUSER "${PGUSER:-}"
required PGPASSWORD "${PGPASSWORD:-}"

if [ "$TARGET_NAMESPACE" != "$EXPECTED_NAMESPACE" ] || [ "$PGHOST" != "$EXPECTED_HOST" ]; then
  echo 'restore_error=target_must_be_fixed_isolated_namespace' >&2
  exit 1
fi
if [ "$RESTORE_DIR" != /restore ]; then
  echo 'restore_error=restore_dir_must_be_/restore' >&2
  exit 1
fi

case "$DRILL_STARTED_EPOCH" in
  '' | *[!0-9]*)
    echo 'restore_error=invalid_drill_start_time' >&2
    exit 1
    ;;
esac
attempt=0
until pg_isready --quiet --host="$PGHOST" --port="$PGPORT" --dbname="$PGDATABASE" --username="$PGUSER"; do
  if [ -f "$RESTORE_DIR/DOWNLOAD_FAILED" ]; then
    echo 'restore_error=download_failed' >&2
    exit 1
  fi
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 180 ]; then
    echo 'restore_error=postgres_readiness_timeout' >&2
    exit 1
  fi
  sleep 2
done

attempt=0
while [ ! -f "$RESTORE_DIR/READY" ]; do
  if [ -f "$RESTORE_DIR/DOWNLOAD_FAILED" ]; then
    echo 'restore_error=download_failed' >&2
    exit 1
  fi
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 1800 ]; then
    echo 'restore_error=download_timeout' >&2
    exit 1
  fi
  sleep 1
done

existing_relations=$(psql -X -qAt --set=ON_ERROR_STOP=1 -c "SELECT count(*) FROM pg_class AS class JOIN pg_namespace AS namespace ON namespace.oid = class.relnamespace WHERE class.relkind IN ('r', 'p', 'm') AND namespace.nspname IN ('auth', 'app_user', 'app_core', 'app_media', 'app_import')")
if [ "$existing_relations" != 0 ]; then
  echo 'restore_error=target_database_not_empty' >&2
  exit 1
fi

if ! pg_restore \
  --exit-on-error \
  --no-owner \
  --no-acl \
  --dbname="$PGDATABASE" \
  "$RESTORE_DIR/database.dump" \
  > /dev/null \
  2> "$RESTORE_DIR/pg-restore.stderr"; then
  echo 'restore_error=pg_restore_failed' >&2
  exit 1
fi

printf '%s\n' 'kind,object_name,row_count,checksum_left,checksum_right' > "$RESTORE_DIR/restored-reconciliation.csv"
if ! psql -X -q --csv --tuples-only --set=ON_ERROR_STOP=1 \
  >> "$RESTORE_DIR/restored-reconciliation.csv" \
  2> "$RESTORE_DIR/reconciliation.stderr" <<'SQL'
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
\i /scripts/reconcile.sql
COMMIT;
SQL
then
  echo 'restore_error=reconciliation_query_failed' >&2
  exit 1
fi

if ! cmp -s "$RESTORE_DIR/reconciliation.csv" "$RESTORE_DIR/restored-reconciliation.csv"; then
  echo 'restore_error=reconciliation_mismatch' >&2
  exit 1
fi

backup_id=$(metadata_value BACKUP_ID "$RESTORE_DIR/metadata.env")
snapshot_epoch=$(metadata_value BACKUP_SNAPSHOT_EPOCH "$RESTORE_DIR/metadata.env")
source_server_version_num=$(metadata_value SOURCE_SERVER_VERSION_NUM "$RESTORE_DIR/metadata.env")
expected_account_count=$(metadata_value ACCOUNT_COUNT "$RESTORE_DIR/metadata.env")
dump_sha256=$(metadata_value DUMP_SHA256 "$RESTORE_DIR/metadata.env")
reconciliation_sha256=$(metadata_value RECONCILIATION_SHA256 "$RESTORE_DIR/metadata.env")
actual_account_count=$(awk -F, '$1 == "table" && $2 == "auth.users" { print $3 }' "$RESTORE_DIR/restored-reconciliation.csv")
if [ "$actual_account_count" != "$expected_account_count" ]; then
  echo 'restore_error=account_count_mismatch' >&2
  exit 1
fi

target_server_version_num=$(psql -X -qAt --set=ON_ERROR_STOP=1 -c 'SHOW server_version_num')
restore_completed_epoch=$(date -u +%s)
rpo_seconds=$((DRILL_STARTED_EPOCH - snapshot_epoch))
rto_seconds=$((restore_completed_epoch - DRILL_STARTED_EPOCH))
if [ "$rpo_seconds" -lt 0 ] || [ "$rto_seconds" -lt 0 ]; then
  echo 'restore_error=invalid_recovery_timing' >&2
  exit 1
fi

printf '{"schemaVersion":1,"kind":"restore-drill","status":"reconciled","namespace":"%s","backupId":"%s","snapshotEpoch":%s,"drillStartedEpoch":%s,"restoredEpoch":%s,"rpoSeconds":%s,"rtoSeconds":%s,"sourceServerVersionNum":%s,"targetServerVersionNum":%s,"accountCount":%s,"dumpSha256":"%s","reconciliationSha256":"%s"}\n' \
  "$TARGET_NAMESPACE" \
  "$backup_id" \
  "$snapshot_epoch" \
  "$DRILL_STARTED_EPOCH" \
  "$restore_completed_epoch" \
  "$rpo_seconds" \
  "$rto_seconds" \
  "$source_server_version_num" \
  "$target_server_version_num" \
  "$actual_account_count" \
  "$dump_sha256" \
  "$reconciliation_sha256"
