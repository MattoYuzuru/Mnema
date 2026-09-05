# No-snapshot purge rehearsal

`scripts/purge/rehearsal.py` is the rehearsal-only executor for the no-snapshot
cutover policy. It cannot run unless `APP_ENV=rehearsal` and
`MNEMA_PURGE_DISPOSABLE_TARGET=true` are both exact. It is not a production
cutover command: #147 owns production target authority, maintenance, smoke,
approval and execution, and #76 must provide the final learning-media target
boundary first.

## Private manifest and evidence boundary

The input is a private JSON manifest with an exact closed schema. It names a
random UUID `targetId`, the literal point-of-no-return value
`first-delete-roll-forward-only`, and all five categories even when a category is
empty:

- `postgres`: exact server version, connection environment prefix, deletion
  schemas and preserved schemas with expected owners;
- `redis`: exact logical database plus deletion and preserved key sets;
- `s3`: exact bucket, endpoint environment reference, every object version,
  delete marker and incomplete multipart upload, plus explicitly preserved
  versions; each object version is classified as ordinary legacy object, WAL or
  backup;
- `kubernetes`: exact context/namespace and UID-bound Deployments, StatefulSets,
  Jobs, CronJobs, routes, Secrets and PVCs, split into deletion and preserved
  sets; `inventoryKinds` must name the complete fixed kind set so an empty
  deletion list cannot hide an undeclared resource;
- `providerArtifacts`: exact database, WAL and backup IDs which must already be
  reported `absent` by their provider and bound to a 64-character
  `absenceEvidenceSha256`. A retained, unverified or unknown provider artifact
  blocks the manifest instead of being treated as deleted.

The manifest and generated plan contain resource names and are never GitHub
artifacts. They are written outside the repository with mode `0600`. Evidence is
safe to attach: it contains only schema/kind/status, manifest and inventory
SHA-256 values, counts for every category, and the roll-forward-only statement.
It never contains keys, object paths, resource names, credentials or raw data.

Secrets are supplied only through environment variables, and provider commands
receive a minimal allowlisted environment instead of the caller's complete
process environment. PostgreSQL uses an `MNEMA_PURGE_*` prefix ending in `_HOST`,
`_PORT`, `_USERNAME`, `_PASSWORD` and `_DATABASE`; Redis uses `_HOST`, `_PORT`
and optional `_PASSWORD`. S3 requires `AWS_ACCESS_KEY_ID` and
`AWS_SECRET_ACCESS_KEY`, accepts an optional session token and CA bundle, and
uses an exact endpoint variable named by the manifest. Kubernetes uses a
kubeconfig path variable. Passwords are not command arguments.

Every live provider is also bound to the manifest `targetId`: the PostgreSQL
database comment must be `mnema-rehearsal:<targetId>`, Redis must preserve
`mnema:rehearsal:target-id` with that value, the S3 bucket must carry the
`mnema-rehearsal-target-id` tag, and the Kubernetes namespace must carry the
`mnema.app/rehearsal-target-id` label. A missing or different marker stops before
inventory can become an executable plan. The disposable confirmation flag alone
is never treated as target ownership proof.

## One-way sequence

1. Stop all disposable legacy writers and populate the private manifest from a
   read-only inventory.
2. Run `preflight`. Provider inventory must be a subset of the exact deletion and
   preserved sets; every preserved neighbor must exist and match owner/UID/version
   metadata. Unknown schemas, keys, versions, markers, uploads or Kubernetes
   resources stop before a plan is written. Inaccessible Object Lock state and an
   enabled lock both stop. PostgreSQL additionally performs each planned schema
   drop inside a rolled-back transaction and compares the preserved object
   fingerprint; a cross-schema `CASCADE` effect stops before a plan is written.
3. Review the private `0600` plan and the sanitized preflight evidence. Any state
   change between preflight and execution invalidates the plan.
4. Run `purge` with the exact acknowledgement. Before the first delete the tool
   writes a durable journal with `point-of-no-return-entered`. From that moment no
   v1 rollback is promised: retry or fix forward only.
5. The executor drops only named schemas, unlinks only named Redis keys, aborts
   only named multipart uploads, permanently deletes only named S3 version IDs,
   and deletes only name/UID-checked Kubernetes resources. It immediately
   re-inventories every provider, requires all deletion sets absent and all
   preserved neighbors present, then records `purged-and-verified`.
6. If execution stops after the durable journal enters the point of no return,
   retry with the same bound manifest, plan and journal. Only the still-present
   subset of the original deletion inventory can continue; new or changed
   resources stop. `verify` can be run independently and a complete
   preflight/purge rerun is safe after completion.

Example (private paths only):

```text
python3 scripts/purge/rehearsal.py preflight \
  --manifest /private/purge-manifest.json \
  --plan /private/purge-plan.json \
  --evidence /private/purge-preflight-evidence.json

python3 scripts/purge/rehearsal.py purge \
  --manifest /private/purge-manifest.json \
  --plan /private/purge-plan.json \
  --journal /private/purge-journal.json \
  --evidence /private/purge-evidence.json \
  --ack first-delete-roll-forward-only

python3 scripts/purge/rehearsal.py verify \
  --manifest /private/purge-manifest.json \
  --evidence /private/purge-absence-evidence.json
```

PostgreSQL `DROP SCHEMA ... CASCADE` can remove dependent objects outside the
named schema, so the preflight simulates the exact drops in a rolled-back
transaction and fingerprints relations, routines, types, constraints, triggers,
rules, policies and defaults in explicitly preserved schemas. The fingerprint is
checked again after deletion. The production plan should still prefer an isolated
legacy database/cluster boundary when available. S3 versioning needs
the permanent deletion of each version ID and each delete marker; a simple object
delete only creates another marker. Multipart uploads are independently listed
and aborted. Object Lock protected versions cannot be reported absent.

Yandex Managed PostgreSQL is an additional asynchronous boundary: current vendor
documentation says backups of a deleted cluster remain provider-restorable for
seven days and cannot be manually deleted during that interval. Therefore #147
cannot claim provider-level absence until the exact backup IDs have expired and a
read-only provider inventory confirms it. The `providerArtifacts` stop condition
models this explicitly.

## Verification

`python3 -m unittest scripts/tests/test_verify_purge_rehearsal.py -v` covers closed
schema validation, privacy-safe evidence, unknown neighbors, Object Lock, stale
plans, environment gates, exact deletion, preservation and idempotent reruns.
`scripts/test-purge-rehearsal-integration.sh` creates disposable pinned PostgreSQL
16, Redis and versioned MinIO fixtures with an object version, delete marker and
incomplete multipart upload; it proves rejection before mutation, exact purge,
preserved fresh state, explicit absence and a safe second run.

References: [PostgreSQL 18 `DROP SCHEMA`](https://www.postgresql.org/docs/18/sql-dropschema.html),
[S3 version deletion](https://docs.aws.amazon.com/AmazonS3/latest/userguide/DeletingObjectVersions.html),
[S3 multipart abort](https://docs.aws.amazon.com/AmazonS3/latest/userguide/abort-mpu.html),
[S3 Object Lock](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock-managing.html),
[Redis `SCAN`](https://redis.io/docs/latest/commands/scan/),
[Redis `UNLINK`](https://redis.io/docs/latest/commands/unlink/) and
[Yandex Managed PostgreSQL backup retention](https://yandex.cloud/en/docs/managed-postgresql/concepts/backup).
