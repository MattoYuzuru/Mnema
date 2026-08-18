#!/bin/sh
set -eu

umask 007

BACKUP_DIR=${BACKUP_DIR:-/backup}
SNAPSHOT_FILE="$BACKUP_DIR/snapshot.id"
SNAPSHOT_RELEASE="$BACKUP_DIR/snapshot.release"
LOCK_FILE="$BACKUP_DIR/lock.acquired"
# Stable, project-specific signed bigint. A session-level advisory lock covers
# both scheduled and manually cloned Jobs; CronJob concurrencyPolicy alone does not.
BACKUP_LOCK_ID=5568224840852393265
keeper_pid=
completed=false

required() {
  name="$1"
  eval "value=\${$name:-}"
  if [ -z "$value" ]; then
    echo "backup_error=missing_${name}" >&2
    exit 1
  fi
}

cleanup() {
  status=$?
  trap - EXIT HUP INT TERM
  touch "$SNAPSHOT_RELEASE" 2>/dev/null || true
  if [ -n "$keeper_pid" ]; then
    wait "$keeper_pid" 2>/dev/null || true
  fi
  if [ "$completed" != true ]; then
    touch "$BACKUP_DIR/FAILED" 2>/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

for name in BACKUP_RUN_ID PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD; do
  required "$name"
done

if [ "$BACKUP_DIR" != /backup ]; then
  echo 'backup_error=backup_dir_must_be_/backup' >&2
  exit 1
fi

rm -f \
  "$BACKUP_DIR/FAILED" \
  "$BACKUP_DIR/READY" \
  "$BACKUP_DIR/UPLOADED" \
  "$BACKUP_DIR/UPLOAD_FAILED" \
  "$LOCK_FILE" \
  "$SNAPSHOT_FILE" \
  "$SNAPSHOT_RELEASE"

if ! printf '%s\n' "$BACKUP_RUN_ID" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'; then
  echo 'backup_error=invalid_backup_run_id' >&2
  exit 1
fi

backup_id="$(date -u +%Y%m%dT%H%M%SZ)-$BACKUP_RUN_ID"
snapshot_started_epoch=$(date -u +%s)
server_version_num=$(psql -X -qAt --set=ON_ERROR_STOP=1 -c 'SHOW server_version_num')
case "$server_version_num" in
  '' | *[!0-9]*)
    echo 'backup_error=invalid_server_version' >&2
    exit 1
    ;;
esac

psql -X -q --set=ON_ERROR_STOP=1 --set=BACKUP_LOCK_ID="$BACKUP_LOCK_ID" <<'SQL' &
SELECT CASE
  WHEN pg_try_advisory_lock(:'BACKUP_LOCK_ID'::bigint) THEN 'true'
  ELSE 'false'
END AS backup_lock_acquired \gset
\if :backup_lock_acquired
  \copy (SELECT 'true') TO '/backup/lock.acquired'
  BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
  \copy (SELECT pg_export_snapshot()) TO '/backup/snapshot.id'
  \! while [ ! -f /backup/snapshot.release ]; do sleep 1; done
  COMMIT;
  SELECT pg_advisory_unlock(:'BACKUP_LOCK_ID'::bigint);
\else
  \copy (SELECT 'false') TO '/backup/lock.acquired'
\endif
SQL
keeper_pid=$!

attempt=0
while [ ! -s "$LOCK_FILE" ]; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 60 ] || ! kill -0 "$keeper_pid" 2>/dev/null; then
    echo 'backup_error=lock_probe_failed' >&2
    exit 1
  fi
  sleep 1
done
if [ "$(tr -d '\r\n' < "$LOCK_FILE")" != true ]; then
  wait "$keeper_pid" 2>/dev/null || true
  keeper_pid=
  echo 'backup_error=lock_contended' >&2
  exit 1
fi

attempt=0
while [ ! -s "$SNAPSHOT_FILE" ]; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 60 ] || ! kill -0 "$keeper_pid" 2>/dev/null; then
    echo 'backup_error=snapshot_export_failed' >&2
    exit 1
  fi
  sleep 1
done

snapshot=$(tr -d '\r\n' < "$SNAPSHOT_FILE")
case "$snapshot" in
  '' | *[!0-9A-Fa-f-]*)
    echo 'backup_error=invalid_snapshot_id' >&2
    exit 1
    ;;
esac

if ! pg_dump \
  --format=custom \
  --compress=9 \
  --lock-wait-timeout=60000 \
  --no-owner \
  --no-acl \
  --snapshot="$snapshot" \
  --file="$BACKUP_DIR/database.dump" \
  "$PGDATABASE" \
  2> "$BACKUP_DIR/pg-dump.stderr"; then
  echo 'backup_error=pg_dump_failed' >&2
  exit 1
fi

printf '%s\n' 'kind,object_name,row_count,checksum_left,checksum_right' > "$BACKUP_DIR/reconciliation.csv"
if ! psql -X -q --csv --tuples-only --set=ON_ERROR_STOP=1 --set=SNAPSHOT="$snapshot" \
  >> "$BACKUP_DIR/reconciliation.csv" \
  2> "$BACKUP_DIR/reconciliation.stderr" <<'SQL'
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET TRANSACTION SNAPSHOT :'SNAPSHOT';
\i /scripts/reconcile.sql
COMMIT;
SQL
then
  echo 'backup_error=reconciliation_failed' >&2
  exit 1
fi

if ! psql -X -q --set=ON_ERROR_STOP=1 --set=SNAPSHOT="$snapshot" \
  > "$BACKUP_DIR/capacity.csv" \
  2> "$BACKUP_DIR/capacity.stderr" <<'SQL'
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET TRANSACTION SNAPSHOT :'SNAPSHOT';
\i /scripts/capacity.sql
COMMIT;
SQL
then
  echo 'backup_error=capacity_snapshot_failed' >&2
  exit 1
fi

touch "$SNAPSHOT_RELEASE"
wait "$keeper_pid"
keeper_pid=

(
  cd "$BACKUP_DIR"
  sha256sum database.dump reconciliation.csv capacity.csv > checksums.sha256
)

account_count=$(awk -F, '$1 == "table" && $2 == "auth.users" { print $3 }' "$BACKUP_DIR/reconciliation.csv")
case "$account_count" in
  '' | *[!0-9]*)
    echo 'backup_error=account_count_missing' >&2
    exit 1
    ;;
esac

backup_completed_epoch=$(date -u +%s)
dump_bytes=$(wc -c < "$BACKUP_DIR/database.dump" | tr -d ' ')
dump_sha256=$(awk '$2 == "database.dump" { print $1 }' "$BACKUP_DIR/checksums.sha256")
reconciliation_sha256=$(awk '$2 == "reconciliation.csv" { print $1 }' "$BACKUP_DIR/checksums.sha256")
capacity_sha256=$(awk '$2 == "capacity.csv" { print $1 }' "$BACKUP_DIR/checksums.sha256")

cat > "$BACKUP_DIR/metadata.env" <<EOF
FORMAT_VERSION=1
BACKUP_ID=$backup_id
BACKUP_SNAPSHOT_EPOCH=$snapshot_started_epoch
BACKUP_DUMP_COMPLETED_EPOCH=$backup_completed_epoch
SOURCE_SERVER_VERSION_NUM=$server_version_num
ACCOUNT_COUNT=$account_count
DUMP_BYTES=$dump_bytes
DUMP_SHA256=$dump_sha256
RECONCILIATION_SHA256=$reconciliation_sha256
CAPACITY_SHA256=$capacity_sha256
EOF

touch "$BACKUP_DIR/READY"

attempt=0
while [ ! -f "$BACKUP_DIR/UPLOADED" ]; do
  if [ -f "$BACKUP_DIR/UPLOAD_FAILED" ]; then
    echo 'backup_error=upload_failed' >&2
    exit 1
  fi
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 1800 ]; then
    echo 'backup_error=upload_timeout' >&2
    exit 1
  fi
  sleep 1
done

completed=true
echo "backup_id=$backup_id status=complete account_count=$account_count dump_bytes=$dump_bytes"
