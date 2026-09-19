#!/usr/bin/env bash
set -euo pipefail
large_container="mnema-r74-large-${1:-measurement}"
large_port="${R74_LARGE_PORT:-15477}"
if [[ ! "$large_port" =~ ^[0-9]+$ ]] || ((large_port < 1024 || large_port > 65535)); then
  printf 'Invalid R74_LARGE_PORT.\n' >&2
  exit 1
fi
export R74_LARGE_PORT="$large_port"
large_cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
large_classpath="$(rg --files "$large_cache/org.postgresql/postgresql/42.7.13" | rg '/postgresql-42\.7\.13\.jar$')"
test -f "$large_classpath"
for large_artifact in jackson-core jackson-databind jackson-annotations; do
  large_version=2.21.4
  if [[ "$large_artifact" == jackson-annotations ]]; then large_version=2.21; fi
  large_jar="$(rg --files "$large_cache/com.fasterxml.jackson.core/$large_artifact/$large_version" | rg "/$large_artifact-$large_version.jar$")"
  test -f "$large_jar"
  large_classpath="$large_classpath:$large_jar"
done
docker image inspect postgres:18 >/dev/null
if docker container inspect "$large_container" >/dev/null 2>&1; then
  printf 'Refusing existing container %s.\n' "$large_container" >&2
  exit 1
fi
trap 'docker stop "$large_container" >/dev/null 2>&1 || true' EXIT
docker run --detach --name "$large_container" --cpus 2 --memory 2g --shm-size 256m \
  -p "127.0.0.1:$large_port:5432" -e POSTGRES_USER=mnema -e POSTGRES_PASSWORD=synthetic-only \
  -e POSTGRES_DB=mnema_r74_large postgres:18 -c shared_buffers=256MB -c max_connections=20 \
  -c autovacuum=off -c checkpoint_timeout=30min -c max_wal_size=2GB
docker image inspect postgres:18 --format 'IMAGE,{{.Id}}'
for large_attempt in {1..30}; do
  if docker exec "$large_container" pg_isready -U mnema -d mnema_r74_large >/dev/null; then break; fi
  sleep 1
done
docker exec "$large_container" pg_isready -U mnema -d mnema_r74_large
java -Xmx1g --class-path "$large_classpath" docs/engineering/evidence/epic-74/storage/src/LargeNativeNodeExperiment.java
