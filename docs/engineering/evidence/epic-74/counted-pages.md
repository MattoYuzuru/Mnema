# K3 counted pages and native structural editing

Status: implemented and locally tested on 2026-09-12; delivery and independent review belong to
the lead. Worktree base `81b75d4`, followed by lead-owned contract clarification `c4068ea`.
This is implementation self-check, not independent review or domain-fork acceptance.

Canonical boundaries: [counted-page contract](../../../architecture/counted-page-contract.md),
[revision storage](../../../architecture/revision-storage-and-runtime-boundaries.md),
[native v1](../../../../contracts/content/native-v1/README.md), [K1](storage-kernel.md),
[K2](native-storage.md). No existing K1/K2 source, wire, dependency, migration, endpoint or
shared contract is changed. PostgreSQL's [foreign-key documentation](https://www.postgresql.org/docs/18/ddl-constraints.html#DDL-CONSTRAINTS-FK)
informs the normalized-reference test: physical references remain FK edges, not payload-only IDs.

## Files and API

New production files in `backend/services/learning/src/main/java/app/mnema/learning/catalog/content/`:

- `pages/CountedPageTypes.java`: profile, entry, root, edit result and immutable-object source.
- `pages/CountedPageFailure.java`: sanitized internal page/range/key/budget failures.
- `pages/CountedPages.java`: bounded bulk build, ordinal insert/delete/move-one/replace and up-to-100-entry read.
- `storage/NativeStructuralEdit.java`: explicit insert, delete or subtree-move intent.
- `storage/NativeStructuralEditor.java`: validates intent against the final native document, edits
  preorder pages and returns the existing K2 `NativeEncodingPlan`.

Four new test files mirror these packages: `CountedPagesTest`, `CountedPagesIntegrationTest`,
`NativeStructuralEditorTest`, `NativeStructuralIntegrationTest`. This evidence file is the only
documentation owned by the slice.

`CountedPages(Profile, ObjectSource)` is pure: the source returns exact immutable objects in the
authorized scope. `build(scope, entries)` is a bounded O(N) bulk operation, not a request-time edit.
`insert(root, ordinal, entry)`, `delete(root, ordinal, expectedKey)`,
`move(root, from, destinationAfterRemoval, expectedKey)` and key-preserving `replace` return a
`PageEdit(root, additions)`. Additions contain only reachable new pages in child-before-parent order.
Same-position moves and equal replacements produce no additions. `read(root, start, limit)` has
limit 1–100; start equal to count returns an empty list.

The generic tree does **not** prove global key uniqueness. Insert callers must establish it through
their domain lookup/projection; no hidden whole-tree key scan occurs. Delete/move/replace verify
the selected key. The final validated native document supplies UUID-value uniqueness for the native
adapter. Descriptor meaning and any role-specific target requirements beyond lower physical rank
belong to the domain adapter.

## Wire, invariants and resource bounds

The exact empty roots remain separate encodingVersion1 PAGE objects of rank10 with no edges:

```json
{"codec":1,"role":"members","treeHeight":0,"counts":[]}
{"codec":1,"role":"exercises","treeHeight":0,"counts":[]}
```

All page payloads contain exactly codec, role, treeHeight and counts. Counts align with normalized
ordered edges and describe entry occurrences. Leaves carry entry keys; internal edges have null
keys. Profiles explicitly select keyed/unkeyed leaves, role, leaf rank, maximum entries and empty
root permission. Native uses `nodes`, rank4, max10,000, nonempty; fragments use unkeyed rank1 pages.
Member/exercise profiles use rank10, allowing normalized descriptor rank9 → content-root rank8 edges.

Non-root occupancy is 16–32; internal roots have 2–32 children. Overflow33 splits16/17. Deletion
borrows from a sibling with spare entries or merges; ancestor counts are copied and singleton roots
collapse. An empty page is legal only as a permitted height0 root. Old objects never mutate.
Final additions prune intermediate delete/move pages. Native subtree moves intentionally perform
K entry deletions/insertions, not an optimal range cut/join algorithm.

Rank is leafRank + treeHeight, independent of native depth. A height-h internal-root tree requires
at least `2 * 16^h` entries, so 100k members have height at most3/rank13; 10k native entries have
height at most3/rank7 below the document root8. Profile construction rejects rank-overflow capacity.
Every local operation caps source reads at256 unique objects; per-call caching avoids duplicate
path reads. A 100-entry range reads only intersecting paths. Read and edit validation checks the root
and visited pages/child counts, not every off-path descendant. Inputs must come from qualified
immutable roots; K1 additionally enforces cross-scope and rank integrity on physical storage.

At fixed fanout32, insert/delete/move-one copy O(height) pages; a native structural change copies
O(K * height) pages plus changed record/fragments. Native record inspection, intent validation,
UUID matching and final full decoding remain bounded full-body work. List splices occur once per
subtree, not once per removed entry. Total CPU/allocation includes O(K * height) edit work on top
of O(N + bytes) planning; transient pure-plan objects are not the durable additions budget.
No latency, heap-allocation or optimal large-subtree-move claim is made.

K2 fragments and canonical records are reused through existing package-private seams rather than
copying its implementation. Unchanged records match by UUID value and exact canonical bytes, not
ordinal. Changed records retain scalar-safe prefix/suffix fragments; equal fragment pages reuse
their complete payload/rank/ordered-edge values. The existing decoder revalidates the final graph
and exact native JSON before exposing the plan. Original UUID spelling, opaque fields, arrays,
optional semantic data and parent child counts remain intact. Unrelated final-body changes, root
deletion/move and descendant-cycle moves fail closed. Existing K2 fixed-topology replacement still
rejects structural edits; there is no fallback or wire version change.

Native editing capabilities are narrower than native storage acceptance. Future `doc` versions
are entirely read-only, including no-op move commands. Insert parents and move destinations must
be supported version1 containers with supported-container ancestry; delete/move sources require
the same editable ancestry for their parent. An opaque subtree itself may move or be deleted intact
through a supported parent, but no operation edits beneath an opaque boundary, even if descendants
look like known types. The adapter's explicit eight-container allowlist follows native-v1; the
package-private reader schema remains authoritative for final-body validity. A future capability
expansion must update this gate deliberately rather than treating opaque acceptance as permission.

The K2 representability proof remains applicable after rebalancing: record bytes
`R <= 1,048,576 + 32 * 10,000 = 1,368,576`, multi-fragment pieces512–2048 bytes, fanout16–32,
fewer than13,521 expanded graph occurrences, fewer than16,000 objects, physical payloads below
16KiB and aggregate payload below16MiB. Rebalancing preserves precisely the occupancy assumptions
used by that proof. These limits do not substitute physical rank for native depth32 or narrow the
native1MiB/10k/scalar32KiB contract. The decoder remains the final fail-closed object/byte-budget gate.

## Executed evidence

Java21 and the existing disposable PostgreSQL18 Testcontainers fixture; no skipped-container mode,
production access, dependency installation or external publication. From `backend/`:

```sh
./gradlew :services:learning:test --tests '*CountedPagesTest' --console=plain
./gradlew :services:learning:test --tests '*NativeStructuralEditorTest' --console=plain
DOCKER_HOST=unix:///Users/m.ryabushkin/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew :services:learning:test --tests '*CountedPages*' --tests '*NativeStructural*' --console=plain
DOCKER_HOST=unix:///Users/m.ryabushkin/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew :services:learning:test --console=plain
```

Final complete Learning run: **241 tests PASS**, zero failures/errors/skips, 31 seconds. Seventeen
tests are new, including two real-PostgreSQL integration tests. Learning line coverage:
**1,750/1,788 (97.87%)**, above the existing90% floor; new pages229/232 (98.71%),
NativeStructural172/173 (99.42%).
JUnit XML and JaCoCo HTML/XML remain under Learning `build/`; no generated artifacts are committed.
No backend lint/static-analysis task is configured beyond compilation/coverage. The full repository
backend/frontend quality gate and exact delivery commit checks remain lead-owned and were not run
by this implementation subtask.

Generic evidence includes 1,100 alternating end insertions followed by deletion to zero, checking
every intermediate tree; 1,500 seeded insert/delete/move/replace operations against an independent
ArrayList; independent recursive counts/order/occupancy/rank checks; direct old-root reads; exact
empty wire; expected-key/range/scope/rank/count/missing-object/budget rejection; and reachable
postorder-only additions. Native evidence includes exact opaque/UUID-case/multilingual subtree
insert/delete/move, supported-link parent-fragment reuse, inserted opaque-subtree fragment/branch
reuse, invalid intents/cycles, future-document and opaque-ancestry rejection, 9,999→10,000-node
insertion, supported-blockquote depth31→32 insertion and structural deletion from an exact1MiB
source with32KiB scalars.

Synthetic ordinal fixtures share one rank9 descriptor; these are physical algorithm measurements,
not distinct-content storage ratios or domain forks. The measured 100-entry window is the final
100 entries (the stdout label says `first100Reads`); PostgreSQL separately checks first100 reads.

| Entries | Height | 100-entry window page reads | Midpoint insert object reads | New pages |
|---:|---:|---:|---:|---:|
| 10,000 | 2 | 6 | 4 | 4 |
| 50,000 | 3 | 7 | 5 | 6 |
| 100,000 | 3 | 7 | 5 | 6 |

A 2,000-native-node beginning insertion under a supported root creates **8 objects** and retains
**39 unique opaque-sibling fragments**. This is an executed fixture, not a universal maximum.
PostgreSQL tests stage in
32-object batches with every frontier object pinned, verify GC cannot collect staged dependencies,
retain the same root twice without new objects, edit one branch, remove source retention and check
both surviving order and normalized rank9→rank8 content protection. K2 staging/decoder also round-trip
a new structural plan while the old root remains directly readable.

Early native fixtures accidentally attached unknown fields to a supported doc node and correctly
failed native validation. Independent review then caught that the replacement fixture edited a
future doc root and another fixture edited inside opaque ancestry: valid storage data, but forbidden
editor operations. A new future-document regression failed against the old adapter, then passed
with explicit capability checks. Fixtures now retain extension data in opaque siblings under a
supported root, and test depth32 through supported blockquotes. Positive intact opaque move/delete
and valid known-link/new-opaque-record fragment tests still pass. A repeated-string fragment-count
assertion was replaced with non-repeating multilingual data to distinguish deduplication from reuse.

## Integration handoff and residual limits

The lead owns domain key uniqueness, descriptor schemas, authorized object sources, live source
and frontier leases, bounded stage/cleanup orchestration, durable pins, revisions/head/CAS/receipts,
atomic publication, projections and ACL. Existing `NativeStorageBatches` accepts these plans and
retains its one-K1-batch-per-call behavior. Generic pages intentionally add no DB transaction wrapper
or authorization token. Source roots must stay pinned throughout multi-call reads/edits/staging.

Physical shared-root branches and GC protection are proven here; concurrent domain publication,
fork identity/ACL, replay receipts and cleanup races across domain transactions are not. The existing
K1 concurrency suite still runs in complete Learning tests. Maximum-size heap/latency profiling and
optimal large-subtree cut/join are not delivered. No unresolved implementation blocker is known from
self-check; independent review and the lead's full repository gate are still required for delivery.

## Independent review follow-up

Independent Astra review read all10 initial files and reproduced the opaque capability
gap. The corrected3-file follow-up was read completely and rerun independently:
`./gradlew :services:learning:test --tests '*NativeStructural*' --console=plain --rerun-tasks`
PASS8/8, zero failures/errors/skips,25s, including PostgreSQL18. Reviewer verdict:
approve-recommended, no remaining confirmed blockers. The lead read the complete
report, production sources and tests; generic pages remained unchanged. This is
local review evidence, not a GitHub human approval or domain-publication acceptance.
