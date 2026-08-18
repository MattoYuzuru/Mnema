#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-backup-integration.XXXXXX")
SUFFIX=$$
NETWORK="mnema-backup-test-$SUFFIX"
SOURCE="mnema-backup-source-$SUFFIX"
TARGET="mnema-backup-target-$SUFFIX"
UPLOADER="mnema-backup-uploader-$SUFFIX"
BACKUP_VOLUME="mnema-backup-data-$SUFFIX"
TARGET_VOLUME="mnema-backup-target-data-$SUFFIX"
POSTGRES_16_IMAGE=postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777
POSTGRES_18_IMAGE=${MNEMA_TEST_POSTGRES_18_IMAGE:-postgres:18@sha256:06cad38a5d9f5d24b4d83d86def30795d5e4b757fedbf5281172b576dedcd941}

cleanup() {
  docker rm -f "$SOURCE" "$TARGET" "$UPLOADER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  docker volume rm "$BACKUP_VOLUME" "$TARGET_VOLUME" >/dev/null 2>&1 || true
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT HUP INT TERM

docker network create "$NETWORK" >/dev/null
docker volume create "$BACKUP_VOLUME" >/dev/null
docker volume create "$TARGET_VOLUME" >/dev/null
docker run --rm --volume "$BACKUP_VOLUME:/backup" "$POSTGRES_18_IMAGE" chown 999:999 /backup
docker run --rm --volume "$TARGET_VOLUME:/var/lib/postgresql" "$POSTGRES_18_IMAGE" chown 999:999 /var/lib/postgresql

docker run --detach \
  --name "$SOURCE" \
  --network "$NETWORK" \
  --network-alias source-postgres \
  --env POSTGRES_DB=mnema \
  --env POSTGRES_USER=mnema \
  --env POSTGRES_PASSWORD=integration-password \
  "$POSTGRES_16_IMAGE" >/dev/null

attempt=0
until docker exec "$SOURCE" pg_isready --quiet --username=mnema --dbname=mnema; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 60 ]; then
    echo 'Source PostgreSQL did not become ready' >&2
    exit 1
  fi
  sleep 1
done

docker exec --interactive "$SOURCE" psql --username=mnema --dbname=mnema --set=ON_ERROR_STOP=1 <<'SQL' >/dev/null
CREATE SCHEMA auth;
CREATE SCHEMA app_user;
CREATE SCHEMA app_core;
CREATE SCHEMA app_media;
CREATE SCHEMA app_import;
CREATE TABLE auth.users (id uuid PRIMARY KEY, email text NOT NULL, profile jsonb NOT NULL);
CREATE TABLE app_user.users (id uuid PRIMARY KEY, created_at timestamptz NOT NULL);
CREATE TABLE app_core.cards (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, payload jsonb NOT NULL);
CREATE TABLE app_media.assets (id bigint PRIMARY KEY, size_bytes bigint NOT NULL);
CREATE TABLE app_import.jobs (id bigint PRIMARY KEY, status text NOT NULL);
INSERT INTO auth.users VALUES
  ('00000000-0000-0000-0000-000000000001', 'first@example.test', '{"locale":"en"}'),
  ('00000000-0000-0000-0000-000000000002', 'second@example.test', '{"locale":"ru"}');
INSERT INTO app_user.users VALUES ('00000000-0000-0000-0000-000000000001', '2026-08-19T00:00:00Z');
INSERT INTO app_core.cards (payload) VALUES ('{"front":"question","back":"answer"}');
INSERT INTO app_media.assets VALUES (1, 1024);
INSERT INTO app_import.jobs VALUES (1, 'DONE');
SQL

docker exec --detach \
  --env PGAPPNAME=mnema-lock-holder \
  "$SOURCE" \
  psql --username=mnema --dbname=mnema --set=ON_ERROR_STOP=1 \
  --command='SELECT pg_advisory_lock(5568224840852393265); SELECT pg_sleep(300);'

attempt=0
while [ "$(docker exec "$SOURCE" psql --username=mnema --dbname=mnema --tuples-only --no-align \
  --command='SELECT pg_try_advisory_lock(5568224840852393265)')" != f ]; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 30 ]; then
    echo 'Backup lock holder did not become active' >&2
    exit 1
  fi
  sleep 1
done

if docker run --rm \
  --user 999:999 \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --read-only \
  --tmpfs /tmp \
  --network "$NETWORK" \
  --env BACKUP_RUN_ID=00000000-0000-4000-8000-000000000000 \
  --env PGHOST=source-postgres \
  --env PGPORT=5432 \
  --env PGDATABASE=mnema \
  --env PGUSER=mnema \
  --env PGPASSWORD=integration-password \
  --volume "$BACKUP_VOLUME:/backup" \
  --volume "$REPO_ROOT/scripts/backup:/scripts:ro" \
  "$POSTGRES_18_IMAGE" \
  /bin/sh /scripts/backup.sh >"$TEST_ROOT/contended.stdout" 2>"$TEST_ROOT/contended.stderr"; then
  echo 'A concurrent backup must fail closed on advisory-lock contention' >&2
  exit 1
fi
if ! grep -Fxq 'backup_error=lock_contended' "$TEST_ROOT/contended.stderr"; then
  echo 'Contended backup did not emit the expected safe failure code' >&2
  sed -n '1,20p' "$TEST_ROOT/contended.stderr" >&2
  exit 1
fi

docker exec "$SOURCE" psql --username=mnema --dbname=mnema --tuples-only --no-align \
  --command="SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = 'mnema-lock-holder'" \
  | grep -Fxq t

docker run --rm \
  --user 999:999 \
  --volume "$BACKUP_VOLUME:/backup" \
  "$POSTGRES_18_IMAGE" \
  /bin/sh -c 'for marker in FAILED READY UPLOADED UPLOAD_FAILED; do [ ! -e "/backup/$marker" ] || unlink "/backup/$marker"; done'

docker run --detach \
  --name "$UPLOADER" \
  --user 999:999 \
  --volume "$BACKUP_VOLUME:/backup" \
  "$POSTGRES_18_IMAGE" \
  /bin/sh -c 'while [ ! -f /backup/READY ] && [ ! -f /backup/FAILED ]; do sleep 1; done; [ -f /backup/READY ] && touch /backup/UPLOADED' >/dev/null

docker run --rm \
  --user 999:999 \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --read-only \
  --tmpfs /tmp \
  --network "$NETWORK" \
  --env BACKUP_RUN_ID=00000000-0000-4000-8000-000000000001 \
  --env PGHOST=source-postgres \
  --env PGPORT=5432 \
  --env PGDATABASE=mnema \
  --env PGUSER=mnema \
  --env PGPASSWORD=integration-password \
  --volume "$BACKUP_VOLUME:/backup" \
  --volume "$REPO_ROOT/scripts/backup:/scripts:ro" \
  "$POSTGRES_18_IMAGE" \
  /bin/sh /scripts/backup.sh >/dev/null

docker wait "$UPLOADER" >/dev/null

docker run --detach \
  --name "$TARGET" \
  --user 999:999 \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --network "$NETWORK" \
  --network-alias postgres.mnema-restore-drill.svc.cluster.local \
  --env POSTGRES_DB=mnema_restore \
  --env POSTGRES_USER=mnema_restore \
  --env POSTGRES_PASSWORD=restore-password \
  --volume "$TARGET_VOLUME:/var/lib/postgresql" \
  "$POSTGRES_18_IMAGE" >/dev/null

attempt=0
until docker exec "$TARGET" pg_isready --quiet --username=mnema_restore --dbname=mnema_restore; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 60 ]; then
    echo 'Restore PostgreSQL did not become ready' >&2
    exit 1
  fi
  sleep 1
done

docker run --rm \
  --user 999:999 \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --read-only \
  --tmpfs /tmp \
  --network "$NETWORK" \
  --env TARGET_NAMESPACE=mnema-restore-drill \
  --env DRILL_STARTED_EPOCH="$(date -u +%s)" \
  --env PGHOST=postgres.mnema-restore-drill.svc.cluster.local \
  --env PGPORT=5432 \
  --env PGDATABASE=mnema_restore \
  --env PGUSER=mnema_restore \
  --env PGPASSWORD=restore-password \
  --volume "$BACKUP_VOLUME:/restore" \
  --volume "$REPO_ROOT/scripts/backup:/scripts:ro" \
  "$POSTGRES_18_IMAGE" \
  /bin/sh /scripts/restore.sh > "$TEST_ROOT/restore-report.json"

python3 "$REPO_ROOT/scripts/backup/validate_report.py" \
  --kind restore-drill \
  --report "$TEST_ROOT/restore-report.json"

restored_accounts=$(docker exec "$TARGET" psql --username=mnema_restore --dbname=mnema_restore --tuples-only --no-align --command='SELECT count(*) FROM auth.users')
if [ "$restored_accounts" != 2 ]; then
  echo 'Restored account count is incorrect' >&2
  exit 1
fi

printf 'backup_integration=ok\n'
