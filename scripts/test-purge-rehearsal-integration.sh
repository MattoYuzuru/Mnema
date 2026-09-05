#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mnema-purge-rehearsal.XXXXXX")
SUFFIX="$$"
POSTGRES_CONTAINER="mnema-purge-postgres-$SUFFIX"
REDIS_CONTAINER="mnema-purge-redis-$SUFFIX"
MINIO_CONTAINER="mnema-purge-minio-$SUFFIX"
NETWORK="mnema-purge-$SUFFIX"
POSTGRES_IMAGE='postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777'
REDIS_IMAGE='redis:7.4.11-alpine@sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf'
MINIO_IMAGE='minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e'

cleanup() {
  docker rm -f "$POSTGRES_CONTAINER" "$REDIS_CONTAINER" "$MINIO_CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT INT TERM

command -v docker >/dev/null
command -v aws >/dev/null
command -v python3 >/dev/null
docker info >/dev/null

docker network create "$NETWORK" >/dev/null
docker run --detach --name "$POSTGRES_CONTAINER" --network "$NETWORK" \
  --env POSTGRES_PASSWORD=fixture-postgres-password --env POSTGRES_DB=legacy \
  "$POSTGRES_IMAGE" >/dev/null
docker run --detach --name "$REDIS_CONTAINER" --network "$NETWORK" "$REDIS_IMAGE" >/dev/null
docker run --detach --name "$MINIO_CONTAINER" --network "$NETWORK" \
  --publish 127.0.0.1::9000 \
  --env MINIO_ROOT_USER=purge-fixture-access --env MINIO_ROOT_PASSWORD=purge-fixture-secret \
  "$MINIO_IMAGE" server /data >/dev/null

for _ in $(seq 1 60); do
  if docker exec "$POSTGRES_CONTAINER" pg_isready -U postgres -d legacy >/dev/null 2>&1 && \
     docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | grep -Fqx PONG; then
    break
  fi
  sleep 1
done
docker exec "$POSTGRES_CONTAINER" pg_isready -U postgres -d legacy >/dev/null
docker exec "$REDIS_CONTAINER" redis-cli ping | grep -Fqx PONG
POSTGRES_VERSION_NUM=$(docker exec "$POSTGRES_CONTAINER" psql -XAt -U postgres -d legacy -c 'SHOW server_version_num')
export POSTGRES_VERSION_NUM

MINIO_PORT=$(docker port "$MINIO_CONTAINER" 9000/tcp | tail -1 | awk -F: '{print $NF}')
case "$MINIO_PORT" in
  '' | *[!0-9]*) echo 'purge_integration_error=minio_port_unavailable' >&2; exit 1 ;;
esac
export AWS_ACCESS_KEY_ID=purge-fixture-access
export AWS_SECRET_ACCESS_KEY=purge-fixture-secret
export AWS_DEFAULT_REGION=us-east-1
export MNEMA_PURGE_S3_ENDPOINT="http://127.0.0.1:$MINIO_PORT"

for _ in $(seq 1 60); do
  if aws --no-cli-pager --endpoint-url "$MNEMA_PURGE_S3_ENDPOINT" s3api list-buckets >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
aws --no-cli-pager --endpoint-url "$MNEMA_PURGE_S3_ENDPOINT" s3api list-buckets >/dev/null

docker exec "$POSTGRES_CONTAINER" psql -XAt -U postgres -d legacy -v ON_ERROR_STOP=1 -c \
  "COMMENT ON DATABASE legacy IS 'mnema-rehearsal:00000000-0000-4000-8000-000000000145'; CREATE SCHEMA legacy_auth AUTHORIZATION postgres; CREATE TABLE legacy_auth.session(id bigint); CREATE SCHEMA account_only AUTHORIZATION postgres; CREATE TABLE account_only.account(id uuid); INSERT INTO account_only.account VALUES ('00000000-0000-4000-8000-000000000001');" >/dev/null
docker exec "$REDIS_CONTAINER" redis-cli SET legacy:session forbidden >/dev/null
docker exec "$REDIS_CONTAINER" redis-cli SET fresh:account preserved >/dev/null
docker exec "$REDIS_CONTAINER" redis-cli SET mnema:rehearsal:target-id 00000000-0000-4000-8000-000000000145 >/dev/null

BUCKET=mnema-purge-fixture
AWS=(aws --no-cli-pager --endpoint-url "$MNEMA_PURGE_S3_ENDPOINT" --region us-east-1 s3api)
"${AWS[@]}" create-bucket --bucket "$BUCKET" >/dev/null
"${AWS[@]}" put-bucket-versioning --bucket "$BUCKET" --versioning-configuration Status=Enabled
"${AWS[@]}" put-bucket-tagging --bucket "$BUCKET" \
  --tagging 'TagSet=[{Key=mnema-rehearsal-target-id,Value=00000000-0000-4000-8000-000000000145}]'
printf 'legacy' >"$TEST_ROOT/legacy"
printf 'fresh' >"$TEST_ROOT/fresh"
LEGACY_VERSION=$("${AWS[@]}" put-object --bucket "$BUCKET" --key legacy/content \
  --body "$TEST_ROOT/legacy" --query VersionId --output text)
WAL_VERSION=$("${AWS[@]}" put-object --bucket "$BUCKET" --key legacy/wal \
  --body "$TEST_ROOT/legacy" --query VersionId --output text)
BACKUP_VERSION=$("${AWS[@]}" put-object --bucket "$BUCKET" --key legacy/backup \
  --body "$TEST_ROOT/legacy" --query VersionId --output text)
FRESH_VERSION=$("${AWS[@]}" put-object --bucket "$BUCKET" --key fresh/avatar \
  --body "$TEST_ROOT/fresh" --query VersionId --output text)
DELETE_MARKER_VERSION=$("${AWS[@]}" delete-object --bucket "$BUCKET" --key legacy/deleted \
  --query VersionId --output text)
MULTIPART_ID=$("${AWS[@]}" create-multipart-upload --bucket "$BUCKET" --key legacy/upload \
  --query UploadId --output text)
for identity in "$LEGACY_VERSION" "$WAL_VERSION" "$BACKUP_VERSION" "$FRESH_VERSION" \
  "$DELETE_MARKER_VERSION" "$MULTIPART_ID"
do
  case "$identity" in
    '' | None | null | *[![:print:]]*) echo 'purge_integration_error=s3_identity_unavailable' >&2; exit 1 ;;
  esac
done
LEGACY_ETAG=$(python3 -c 'import hashlib; print(hashlib.md5(b"legacy", usedforsecurity=False).hexdigest())')
WAL_ETAG=$LEGACY_ETAG
BACKUP_ETAG=$LEGACY_ETAG
FRESH_ETAG=$(python3 -c 'import hashlib; print(hashlib.md5(b"fresh", usedforsecurity=False).hexdigest())')
export LEGACY_VERSION WAL_VERSION BACKUP_VERSION FRESH_VERSION DELETE_MARKER_VERSION MULTIPART_ID
export LEGACY_ETAG WAL_ETAG BACKUP_ETAG FRESH_ETAG

mkdir -p "$TEST_ROOT/bin"
cat >"$TEST_ROOT/bin/psql" <<EOF
#!/usr/bin/env bash
exec docker exec -i -e PGUSER -e PGPASSWORD -e PGDATABASE "$POSTGRES_CONTAINER" psql "\$@"
EOF
cat >"$TEST_ROOT/bin/redis-cli" <<EOF
#!/usr/bin/env bash
exec docker exec -i "$REDIS_CONTAINER" redis-cli "\$@"
EOF
chmod 700 "$TEST_ROOT/bin/psql" "$TEST_ROOT/bin/redis-cli"

MANIFEST="$TEST_ROOT/manifest.json"
export MANIFEST BUCKET
python3 - <<'PY'
import json
import os
from pathlib import Path

document = {
    "schemaVersion": 1,
    "kind": "mnema-no-snapshot-purge",
    "targetId": "00000000-0000-4000-8000-000000000145",
    "pointOfNoReturn": "first-delete-roll-forward-only",
    "postgres": [{
        "id": "legacy-postgres",
        "envPrefix": "MNEMA_PURGE_POSTGRES",
        "serverVersionNum": int(os.environ["POSTGRES_VERSION_NUM"]),
        "deleteSchemas": [{"name": "legacy_auth", "owner": "postgres"}],
        "preserveSchemas": [{"name": "account_only", "owner": "postgres"}, {"name": "public", "owner": "pg_database_owner"}],
    }],
    "redis": [{
        "id": "legacy-redis", "envPrefix": "MNEMA_PURGE_REDIS", "database": 0,
        "deleteKeys": ["legacy:session"],
        "preserveKeys": ["fresh:account", "mnema:rehearsal:target-id"],
    }],
    "s3": [{
        "id": "legacy-storage", "endpointEnv": "MNEMA_PURGE_S3_ENDPOINT", "region": "us-east-1",
        "bucket": os.environ["BUCKET"], "requireObjectLockDisabled": True,
        "deleteVersions": [{
            "category": "object", "key": "legacy/content", "versionId": os.environ["LEGACY_VERSION"],
            "size": 6, "etag": os.environ["LEGACY_ETAG"],
        }, {
            "category": "wal", "key": "legacy/wal", "versionId": os.environ["WAL_VERSION"],
            "size": 6, "etag": os.environ["WAL_ETAG"],
        }, {
            "category": "backup", "key": "legacy/backup", "versionId": os.environ["BACKUP_VERSION"],
            "size": 6, "etag": os.environ["BACKUP_ETAG"],
        }],
        "deleteMarkers": [{"key": "legacy/deleted", "versionId": os.environ["DELETE_MARKER_VERSION"]}],
        "multipartUploads": [{"key": "legacy/upload", "uploadId": os.environ["MULTIPART_ID"]}],
        "preserveVersions": [{
            "category": "object", "key": "fresh/avatar", "versionId": os.environ["FRESH_VERSION"],
            "size": 5, "etag": os.environ["FRESH_ETAG"],
        }],
        "preserveDeleteMarkers": [],
    }],
    "kubernetes": [],
    "providerArtifacts": [
        {"category": "database", "provider": "fixture", "resourceId": "legacy-cluster", "state": "absent",
         "absenceEvidenceSha256": "1" * 64},
        {"category": "wal", "provider": "fixture", "resourceId": "legacy-wal", "state": "absent",
         "absenceEvidenceSha256": "2" * 64},
        {"category": "backup", "provider": "fixture", "resourceId": "legacy-backup", "state": "absent",
         "absenceEvidenceSha256": "3" * 64},
    ],
}
Path(os.environ["MANIFEST"]).write_text(json.dumps(document), encoding="utf-8")
PY
chmod 600 "$MANIFEST"

export APP_ENV=rehearsal
export MNEMA_PURGE_DISPOSABLE_TARGET=true
export MNEMA_PURGE_POSTGRES_HOST=ignored-by-container-wrapper
export MNEMA_PURGE_POSTGRES_PORT=5432
export MNEMA_PURGE_POSTGRES_USERNAME=postgres
export MNEMA_PURGE_POSTGRES_PASSWORD=fixture-postgres-password
export MNEMA_PURGE_POSTGRES_DATABASE=legacy
export MNEMA_PURGE_REDIS_HOST=127.0.0.1
export MNEMA_PURGE_REDIS_PORT=6379
export MNEMA_PURGE_REDIS_PASSWORD=
export PATH="$TEST_ROOT/bin:$PATH"

TOOL="$REPO_ROOT/scripts/purge/rehearsal.py"
docker exec "$POSTGRES_CONTAINER" psql -XAt -U postgres -d legacy -v ON_ERROR_STOP=1 -c \
  'CREATE VIEW account_only.legacy_dependency AS SELECT id FROM legacy_auth.session' >/dev/null
if python3 "$TOOL" preflight --manifest "$MANIFEST" --plan "$TEST_ROOT/cascade-plan.json" \
  --evidence "$TEST_ROOT/cascade-evidence.json" >/dev/null 2>&1; then
  echo 'purge_integration_error=cross_schema_cascade_accepted' >&2
  exit 1
fi
test ! -e "$TEST_ROOT/cascade-plan.json"
docker exec "$POSTGRES_CONTAINER" psql -XAt -U postgres -d legacy -v ON_ERROR_STOP=1 -c \
  'DROP VIEW account_only.legacy_dependency' >/dev/null

python3 "$TOOL" preflight --manifest "$MANIFEST" --plan "$TEST_ROOT/plan.json" \
  --evidence "$TEST_ROOT/preflight-evidence.json" >/dev/null
if grep -Eq 'legacy_auth|legacy:session|legacy/content|fixture-postgres-password' "$TEST_ROOT/preflight-evidence.json"; then
  echo 'purge_integration_error=evidence_leak' >&2
  exit 1
fi

docker exec "$REDIS_CONTAINER" redis-cli SET neighbour:unknown stop >/dev/null
if python3 "$TOOL" preflight --manifest "$MANIFEST" --plan "$TEST_ROOT/rejected-plan.json" \
  --evidence "$TEST_ROOT/rejected-evidence.json" >/dev/null 2>&1; then
  echo 'purge_integration_error=unknown_neighbour_accepted' >&2
  exit 1
fi
docker exec "$REDIS_CONTAINER" redis-cli DEL neighbour:unknown >/dev/null

python3 "$TOOL" purge --manifest "$MANIFEST" --plan "$TEST_ROOT/plan.json" \
  --journal "$TEST_ROOT/journal.json" --evidence "$TEST_ROOT/purge-evidence.json" \
  --ack first-delete-roll-forward-only >/dev/null
python3 "$TOOL" verify --manifest "$MANIFEST" --evidence "$TEST_ROOT/verify-evidence.json" >/dev/null

test "$(docker exec "$POSTGRES_CONTAINER" psql -XAt -U postgres -d legacy -c "SELECT count(*) FROM pg_namespace WHERE nspname='legacy_auth'")" = 0
test "$(docker exec "$POSTGRES_CONTAINER" psql -XAt -U postgres -d legacy -c "SELECT count(*) FROM account_only.account")" = 1
test "$(docker exec "$REDIS_CONTAINER" redis-cli EXISTS legacy:session)" = 0
test "$(docker exec "$REDIS_CONTAINER" redis-cli GET fresh:account)" = preserved
VERSIONS=$("${AWS[@]}" list-object-versions --bucket "$BUCKET")
UPLOADS=$("${AWS[@]}" list-multipart-uploads --bucket "$BUCKET")
printf '%s' "$VERSIONS" | python3 -c 'import json,os,sys; value=json.load(sys.stdin); versions=value.get("Versions", []); assert [(v["Key"],v["VersionId"]) for v in versions] == [("fresh/avatar",os.environ["FRESH_VERSION"])]; assert not value.get("DeleteMarkers")'
printf '%s' "$UPLOADS" | python3 -c 'import json,sys; assert not json.load(sys.stdin).get("Uploads")'

python3 "$TOOL" preflight --manifest "$MANIFEST" --plan "$TEST_ROOT/rerun-plan.json" \
  --evidence "$TEST_ROOT/rerun-preflight.json" >/dev/null
python3 "$TOOL" purge --manifest "$MANIFEST" --plan "$TEST_ROOT/rerun-plan.json" \
  --journal "$TEST_ROOT/rerun-journal.json" --evidence "$TEST_ROOT/rerun-purge.json" \
  --ack first-delete-roll-forward-only >/dev/null

printf 'purge_rehearsal_integration=ok\n'
