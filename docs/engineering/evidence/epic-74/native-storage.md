# K2 native codec and immutable ordered manifests

Status: implemented and locally validated; independent review, exact delivery commit and the full
repository quality gate belong to the delivery lead. This slice builds on K1 `d22cca8` and the native
boundary `a766097`, integrated locally at `2130507`. It adds no migration, dependency, endpoint,
worker, publication handler or editor integration. The owner-approved physical scheme is unchanged.

Canonical contracts: [native v1](../../../../contracts/content/native-v1/README.md),
[revision storage](../../../architecture/revision-storage-and-runtime-boundaries.md),
[K1 kernel](./storage-kernel.md). The [research](./storage/README.md) and
[large-node experiment](./storage/large-native-node-report.md) are feasibility evidence, not the
production codec. Their earlier pending-owner wording does not override the subsequent selection.

## Representation and ownership

`catalog.content.storage.NativeSnapshotCodec` accepts a validated `NativeDocument`. It flattens only
the native `content` traversal into preorder records `{"c": childCount, "n": nodeWithoutContent}`.
All other semantic fields remain intact, including opaque extension fields/attributes, optional
presence, ordered arrays/marks, UUID spelling and unsupported descendants. Objects inside opaque
attributes are data even when their keys resemble native nodes. Preorder and direct child counts
reconstruct the exact native tree. Object-key order, whitespace and equivalent number spelling are
not preservation promises, consistently with the native contract.

A record's canonical UTF-8 JSON is stored as bounded strings: `BLOCK` with role `record` for one
piece, otherwise `FRAGMENT` leaves under role `fragments` pages. Payloads have `codec:1`; K1
`encodingVersion` is also 1. Fragments target 1,024 bytes and never exceed 2,048 bytes. Boundaries
are Unicode scalar boundaries in the encoded JSON stream; pieces need not independently parse as
JSON. Escaped JSON sequences may span pieces and are parsed only after exact concatenation. This
also handles a 32-KiB property name or a deeply nested opaque value without a large/deep physical
header. It is deliberately not the research fixture's single-text-slot skeleton.

Every multi-piece record has fragments of at least 512 bytes. Replacements preserve complete old
fragments in the common prefix/suffix, then repair small pieces locally. A short insertion inside
a sufficiently sized piece reuses the suffix boundaries. Repeated deletions cannot leave an
unbounded number of tiny fragments. Reuse compares the entire kind/rank/canonical payload/ordered
edge value within the source scope; a hash is not equality authority.

Balanced role `nodes` pages select records in preorder, with native UUID values in leaf-edge
`logicalKey`; internal keys are null. Counts are aligned with normalized edges and checked during
decoding. Physical references occur only in K1 edges. A role `document` PAGE selects this manifest
and declares format version, node count and total record bytes. Non-root pages have 16–32 entries;
an internal root has at least two children. Bulk construction distributes each level evenly.

Native depth, page `treeHeight`, and K1 `dagRank` are independent:

| Layer | Physical rank |
|---|---:|
| Record/block or fragment bytes | 0 |
| Fragment page tree | 1–3 |
| Native manifest leaf/internal pages | 4–7 |
| Document root | 8 |

Semantic parent-child relationships do not add physical edges. Therefore a native document of
depth 32 still fits the same rank bound, including oversized data at its deepest node.

## APIs and bounded integration

`encode(scope, document)` returns a pure `NativeEncodingPlan`: the resulting validated snapshot and
new objects in postorder. `replace(previousSnapshot, document)` requires identical preorder UUID
values and direct child counts; otherwise it throws internal `STRUCTURE_CHANGED`. Changing UUID
case alone preserves identity but retains the newly supplied spelling. There is no silent structural
bulk-rebuild fallback in `replace`. New independent snapshots can still use `encode`.

Planning/reconstruction inspect one bounded document, not previous revisions. With unchanged
topology and fragment count, changed objects and ancestor pages are copied while equal branches
retain their physical IDs. Full-record inspection is not an O(log N) edit API. Fragment-count changes
can rebuild that record's small page tree; this slice does not claim local insert/delete/move,
incremental split/merge, or large-deck membership mutation behavior.

`NativeSnapshotDecoder` is a caller-owned incremental cursor: request at most 32 physical IDs,
accept precisely that response, repeat, then obtain a snapshot only after complete graph and native
validation. Roles, encoding versions, scope, IDs, rank/height, occupancy, keys, counts, record JSON,
preorder closure and all native constraints are checked. A failed cursor cannot resume or expose
partial content. Traversal has separate unique-object, edge, expanded-occurrence and byte budgets;
sharing a page repeatedly cannot bypass the expansion bound.

`NativeStorageBatches` performs one K1 `stageBatch` or `readBatch` per call and holds no service state.
Preparation state belongs to the caller. Each new object receives a staging pin, so frontier children
remain protected between calls. Replacements require a source preparation for the exact source root;
unchanged replacements acquire a fresh staging root pin. Earlier recorded deadlines guard subsequent
calls conservatively. These in-memory checks are not authoritative database lease verification:
the caller must keep source/frontier pins live and unreleased, and K1 `retain` checks the actual
stored actor/root/deadline at final publication. Clock skew can conservatively reject preparation;
renewal, resumption after expiry and durable job receipts are orchestration work, not invented here.

Caller ownership remains explicit: ACL, root retention for the entire multi-call read, staging lease
renewal and bounded intermediate-pin cleanup, final CAS/receipt/projection transaction, scheduling and
admission. Physical IDs and these Java objects grant no access. No operation wraps all batches in
one database transaction or schedules itself. Until cleanup/expiry is invoked, staging pins continue
to protect storage exactly as in K1.

## Bounds without narrowing valid native input

Let `B <= 1,048,576` be canonical native bytes and `N <= 10,000` native nodes. Removing native child
arrays and adding `{c,n}` adds fewer than 32 bytes per node; total record bytes `R <= B + 32N`, hence
`R <= 1,368,576`. The codec uses this conservative bound. Flattening does not change native scalar,
JSON-depth, token, node-count or native-depth limits.

Multi-piece records have pieces >=512 bytes, so their total leaf occurrences `L <= R/512`. Let `M`
be their number and `I` their page count. With non-root fanout >=16 and root fanout >=2,
`I <= (L + 13M)/15`. Including `N-M` single-record blocks, total record graph occurrences are at most
`N + 16R/(15*512)`. The outer tree adds at most `(N+13)/15` pages and one document root. The resulting
bound is below **13,521 occurrences**, less than the **16,000** object/edge/expanded-occurrence limits.
Deduplication can only reduce unique-object count. A record has fewer than 2,674 pieces; a fragment
tree of height 3 would require at least `2*16^3=8192`, so fragment height <=2/rank <=3. The same
formula with 10,000 native entries gives outer height <=3/rank <=7.

JSON-string escaping grows a fragment by at most six bytes per source byte. Fixed payload metadata
is under 64 bytes, giving a physical payload below `6*2048+64`, with ample room below K1's 16 KiB
JSONB-text cap. Page payloads are under 512 bytes (32 bounded numeric counts plus fixed metadata).
Combining these bounds gives less than **10.1 MB** of canonical physical payloads per expanded graph,
below the decoder's **16 MiB** aggregate payload budget. This is a payload budget, not a wire/WAL,
heap or backup-size claim. Edges/headers have independent cardinality bounds. A 32-object call fits
K1's 1-MiB canonical-envelope budget even at these conservative per-object maxima.

## Validation and limits of evidence

Java 21, existing PostgreSQL 18 Testcontainers fixture and dependencies. All tests use isolated
synthetic scopes; no production data, dependency changes, fault injection or load campaign.

From `backend/`:

```sh
./gradlew :services:learning:compileJava --console=plain
./gradlew :services:learning:test --tests 'app.mnema.learning.catalog.content.storage.NativeSnapshotCodecTest' --console=plain
DOCKER_HOST=unix:///Users/m.ryabushkin/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew :services:learning:test --console=plain
```

Complete Learning run on 2026-09-12: **224 tests PASS**, zero failures/errors/skips, 22 seconds reported
by Gradle. Seventeen new tests include two PostgreSQL integration tests. Learning line coverage:
1,349/1,383 (**97.54%**, existing floor 90%); new codec package: 391/401 (**97.51%**). Normal XML/HTML
artifacts remain under Learning `build/test-results` and `build/reports`; no generated fixtures are
committed. The lead must rerun the full repository gate on the delivery candidate.

Evidence includes exact semantic corpus/opaque/optional/UUID-case preservation; exact native 1-MiB,
10,000-node, 32-KiB scalar/property-name, depth-32 and JSON-depth-128 fixtures; 200 seeded Unicode
fragment edits; one thousand direct-root history replacements; fixed-topology rejection; malformed
metadata/records/preorder/cross-scope/response cases; fanout budget exhaustion; recorded lease expiry;
multi-batch PostgreSQL staging with every frontier pin protected against GC; durable historical roots
and subsequent collection with the surviving snapshot unchanged.

The PostgreSQL large-opaque fixture includes a 32-KiB property name and a 32-KiB control-character
scalar (larger when JSON-escaped): initial graph **26 unique objects**, tiny insertion **5 new objects**,
largest observed JSONB payload **1,239 bytes**. Repeated fixture strings deduplicate heavily; these
are executed feasibility counts, not a general storage-ratio or latency claim. The 100-native-node
fixture separately requires multiple staging/read batches and checks the actual 16-KiB SQL limit.

One early negative test used an ineffective literal replacement because supplementary Unicode was
escaped in the canonical stream; the fixture now guarantees a changed payload. No production
contract was weakened to make it pass. Implementation self-check is not independent review.

Independent review on 2026-09-12 read all seven source files, five test files and this
evidence. It recomputed the expanded-object bound below 13,520 and reran all 17 K2
tests, including both PostgreSQL tests: zero failures/errors/skips, Gradle 14 seconds.
No confirmed blocker remained in the bounded K2 scope. The delivery lead also read
the complete source/tests and evidence. Exact committed full-repository validation
and protected delivery remain required; this is not an HTTP/publication readiness claim.

Official [PostgreSQL JSON types](https://www.postgresql.org/docs/18/datatype-json.html) informs semantic
preservation and escaped-string storage; [row locking](https://www.postgresql.org/docs/18/explicit-locking.html)
informs the unchanged K1 pin/collection protocol. Real publication, generic persistent edits,
authorized historical APIs, durable preparation orchestration and deployment remain later slices.
