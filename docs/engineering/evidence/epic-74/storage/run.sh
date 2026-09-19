#!/usr/bin/env bash
set -euo pipefail

# Run from repository root. Uses only the image and JDBC dependency already present.
# The named disposable database is deliberately retained, stopped, for inspection.
storage_container="mnema-r74-storage-${1:-measurement}"
storage_port="${R74_PORT:-15474}"
if [[ ! "$storage_port" =~ ^[0-9]+$ ]] || (( storage_port < 1024 || storage_port > 65535 )); then
  printf 'R74_PORT must be a loopback port from 1024 through 65535.\n' >&2
  exit 1
fi
export R74_PORT="$storage_port"
storage_cache="${GRADLE_USER_HOME:-$HOME/.gradle}"
storage_driver="${R74_JDBC_JAR:-}"
if test -z "$storage_driver"; then
  storage_driver="$(rg --files "$storage_cache/caches/modules-2/files-2.1/org.postgresql/postgresql/42.7.13" | rg '/postgresql-42\.7\.13\.jar$')"
fi
test -f "$storage_driver"
storage_classpath="$storage_driver"
for storage_artifact in jackson-core jackson-databind jackson-annotations; do
  storage_version=2.21.4
  if [[ "$storage_artifact" == jackson-annotations ]]; then storage_version=2.21; fi
  storage_jar="$(rg --files "$storage_cache/caches/modules-2/files-2.1/com.fasterxml.jackson.core/$storage_artifact/$storage_version" | rg "/$storage_artifact-$storage_version.jar$")"
  test -f "$storage_jar"
  storage_classpath="$storage_classpath:$storage_jar"
done
docker image inspect postgres:18 >/dev/null
if docker container inspect "$storage_container" >/dev/null 2>&1; then
  printf 'Refusing to replace existing container %s; choose a fresh suffix.\n' "$storage_container" >&2
  exit 1
fi
trap 'docker stop "$storage_container" >/dev/null 2>&1 || true' EXIT
docker run --detach --name "$storage_container" --cpus 2 --memory 2g --shm-size 256m \
  -p "127.0.0.1:$storage_port:5432" -e POSTGRES_USER=mnema -e POSTGRES_PASSWORD=synthetic-only \
  -e POSTGRES_DB=mnema_r74_storage postgres:18 -c shared_buffers=256MB \
  -c max_connections=20 -c checkpoint_timeout=30min -c max_wal_size=2GB -c autovacuum=off
docker image inspect postgres:18 --format 'IMAGE,{{.Id}}'
for attempt in {1..30}; do
  if docker exec "$storage_container" pg_isready -U mnema -d mnema_r74_storage >/dev/null; then break; fi
  sleep 1
done
docker exec "$storage_container" pg_isready -U mnema -d mnema_r74_storage
java -Xmx1g --class-path "$storage_classpath" \
  docs/engineering/evidence/epic-74/storage/src/StorageChoiceExperiment.java
docker exec "$storage_container" psql -U mnema -d mnema_r74_storage -c 'VACUUM (ANALYZE);'
docker exec "$storage_container" psql -U mnema -d mnema_r74_storage -Atc \
  "SELECT 'AFTER_VACUUM_DATABASE_BYTES,' || pg_database_size(current_database())"
