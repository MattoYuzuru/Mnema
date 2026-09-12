# K1 immutable storage kernel — implementation evidence

Status: implemented and locally verified; independent review and exact-candidate repository gate belong to the delivery lead. This is the first bounded production slice of the owner's approved immutable PostgreSQL blocks/pages, normalized edges and physical-fragment scheme. It does not claim complete revision storage or a shipped content flow.

## Scope and contracts

V2 adds only `app_learning.storage_object`, `storage_edge`, `storage_pin`, `storage_gc_candidate`, their indexes and constraint/guard triggers. No dependency, endpoint, scheduler, codec, B-tree, revision/deck table or deployment change.

`ImmutableStorage` exposes these internal Java methods using records in `StorageTypes`:

```java
List<StagedRoot> stageBatch(StageBatch batch, Duration lease);
List<StoredObject> readBatch(UUID scope, List<UUID> ids);
UUID retain(StagedRoot prepared, PinOwner owner); // existing transaction mandatory
void release(UUID scope, UUID pinId);            // existing transaction mandatory
long renewStaging(UUID scope, UUID pinId, long expectedVersion, Duration extension);
int expireStaging(UUID scope, Instant cutoff, int limit);
CollectionResult collectBatch(UUID scope, Instant cutoff, int limit);
```

- Stage input is postorder: children must already be sealed, including children earlier in the same batch. A repeated object ID is reusable only after complete normalized payload, kind, encoding version, DAG rank and ordered-edge equality; the SHA-256 fingerprint is never equality authority. A repeated stage call deliberately returns fresh staging pins, not an idempotent publication receipt.
- Every reference carries a reuse scope. Composite foreign keys enforce same-scope, exact copied ranks. Scope and physical IDs do not grant access: the calling domain service must authorize reads, preparation, renewal and pin release. Retain additionally checks the stored preparation actor and root, using the database's current deadline, not a caller-supplied expiry.
- An object starts open, receives at most 32 contiguous edges, then seals. A deferred commit constraint rejects incomplete/open objects. Row locks serialize edge append and sealing; sealed headers and edges cannot be updated. Edge deletion is allowed only through parent removal. Descending DAG rank is independent of any later B-tree height; rank 0 is a block/header/fragment, and pages have ranks 1–32.
- Stage and read batches admit at most 64 objects. Their canonical object envelopes, including metadata and normalized edges, total at most 1 MiB. Each payload is a JSON object with at most 16 KiB of PostgreSQL `jsonb::text` UTF-8, independently checked after database normalization. This is a physical storage bound, **not** a semantic paragraph/native node bound. Larger nodes will be represented by codec-managed fragment/header/page graphs in a subsequent slice.
- Input rejects non-finite numbers, binary/POJO/missing nodes, NUL, malformed Unicode, unsafe browser numbers and excessive depth/size; it does not silently stringify unsupported scalar types. Serialization writes through a capped buffer. JSON object key order and lexical number formatting are not byte-preservation promises; normalized semantic JSON is stored.
- Reads take short key-share locks, so concurrent collection cannot separate a returned header from its outgoing edges. Historical/domain traversal is not implemented here and will need explicit caller budgets.
- Retain/release join the same transaction as the domain head CAS, projection effects and command receipt. They cannot independently commit publication. A durable pin has no expiry. Missing release is a no-op; the caller still owns pin authorization.

## Staging and collection ownership

Staging lease maximum defaults to one hour (`learning.storage.max-staging-lease`, allowed up to one day); orphan grace defaults to ten minutes (`learning.storage.orphan-grace`, up to seven days); lock timeout defaults to one second (`learning.storage.lock-timeout`, up to five seconds). Values must be at least one millisecond. These are configurable engineering safeguards, not product retention decisions. Renewal is versioned CAS, only for a still-live staging pin, and cannot resurrect an expired preparation.

Expiry and collection are explicitly invoked internal primitives, forbidden inside a caller publication transaction. No worker is registered. Each call performs at most eight candidates in a new ten-second transaction; locking/deadlock failures roll back the whole batch and retry at most three times. A supplied future cutoff is clamped to database time. The scoped expiry and garbage readiness indexes match each ordered range query. `SKIP LOCKED` divides selected candidates among concurrent callers.

Collection locks each object before testing incoming edges/pins. A new pin or incoming edge either wins its key-share lock and protects the object, or loses to deletion and fails without creating a dangling reference. Parent deletion removes its own edges, then queues its at most 32 children; there is no recursive graph scan. Interrupted/rolled-back calls leave queue and graph changes atomic. Surviving pinned candidates are removed from the queue and re-enqueued on subsequent pin release or parent removal. Expired staging pins remain protective until explicit expiry removes them.

The delivery lead/domain slice must wire authorized publication, fork/history pins and product deletion/retention into these primitives. There is no arbitrary purge endpoint, durable TTL, automatic history expiry, background scheduler or authorization shortcut in K1. Runtime/migration role separation remains deployment ownership; this slice does not claim protection against a database owner disabling triggers or issuing TRUNCATE.

## Verification

Environment: Java 21, existing Spring/Jackson/JDBC dependencies, local Colima Docker and the repository's shared fail-closed Testcontainers `postgres:18` fixture. No production resources or new dependencies. The tests use isolated test-only publication/projection tables, synthetic elapsed lease/deadline updates, and at most two concurrent application workers; they do not simulate a real content API.

Commands from `backend/` (replace the Docker socket path for another host):

```sh
./gradlew :services:learning:compileJava --console=plain
DOCKER_HOST=unix:///Users/m.ryabushkin/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew :services:learning:test --tests 'app.mnema.learning.storage.*' --console=plain
DOCKER_HOST=unix:///Users/m.ryabushkin/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew :services:learning:test --console=plain
```

Final complete Learning run on 2026-09-06: **PASS**, 136 tests, zero failures/errors/skips, 14 seconds reported by Gradle. It includes 23 new PostgreSQL integration tests and seven storage unit tests. JaCoCo Learning line coverage: 765/785 (**97.45%**), against the existing 90% floor; storage package: 332/338 (**98.22%**). XML/HTML artifacts are under the normal Learning `build/test-results` and `build/reports` directories, not committed evidence fixtures. The full multi-service/frontend gate and exact delivery SHA are deliberately not claimed here.

Test evidence covers:

- Exact 16,384-byte multibyte JSONB payload and one-byte overflow; aggregate stage and read limits; fanout/rank/ordinal/UUID/duration checks and defensive snapshots.
- Complete equality reuse and conflicting payload/version/edge rollback; missing/cross-scope/forward references; actual deferred commit rejection and SQL mutation guards.
- Mandatory publication transaction, actor/expiry checks, renewal CAS, durable non-expiry, rollback of receipt/pin/head/projection after a crash exception and CAS loss.
- Same-command receipt race and different-command head CAS race, concurrent equal staging, reader-versus-GC, both new-pin/deletion winning orders and sealed-parent append contention. These tests observe `pg_blocking_pids`, not merely concurrent submissions.
- Bounded/restartable root→page→fragment collection, expiry and collection batches of eight, locked candidate skipping, publication/expiry exclusion and a 4,000-other-scope skewed expiry index plan.
- Whole-batch lock failure retry and exhaustion are deterministic transaction-manager unit fixtures (not claimed as a real PostgreSQL deadlock injection).

During development, tests exposed and corrected a final repository class incompatible with Spring's exception-translation proxy, Jackson's non-finite-number string coercion, and the platform smoke test's now-stale single-migration assertion. The latter now expects exactly V1/platform foundation/SUCCESS and V2/immutable storage kernel/SUCCESS, preserving all legacy-schema checks.

PostgreSQL references informing the constraints and lock protocol: [constraints](https://www.postgresql.org/docs/18/ddl-constraints.html), [explicit locking](https://www.postgresql.org/docs/18/explicit-locking.html), and [constraint triggers](https://www.postgresql.org/docs/18/sql-createtrigger.html). Cross-row graph invariants use foreign keys and trigger-held row locks, not unsupported cross-row CHECK expressions.

## Remaining boundaries

Independent review on 2026-09-12 reproduced a bounded-GC grace defect: deleting
a parent can postpone its child even when both were selected in the same batch.
The old loop used the stale selected IDs and could delete that child immediately.
This was premature orphan collection, not deletion of reachable/pinned content.
The new regression first failed against the old implementation. The loop now
rechecks each already-locked candidate's deadline against the original batch cutoff,
retaining postponed candidates for a later call. No unbounded scan or extra worker
was introduced. Post-fix complete Learning run on 2026-09-12: `./gradlew
:services:learning:test --console=plain` **PASS**, 137 tests, zero failures/errors/skips,
18 seconds. Learning line coverage is 772/792 (97.47%). Exact-candidate full repository
gates and delivery are still pending. Child issue: #179.

The native fragment codec, stable semantic IDs and exact native reconstruction, occupancy/split/merge/root collapse, paged historical APIs, real domain publication and search/outbox effects are subsequent slices. Research demonstrated feasibility, but K1 does not claim those implementations. No new owner choice is required for this physical graph slice; durable retention behavior and caller authorization remain domain policy and cannot be inferred from staging engineering defaults.
