# R74-S independent storage review — 2026-09-06

## Final assessment: ready for the owner's schema choice

**SR-1 through SR-5 are now closed at the bounded R74-S feasibility level.
The lead can present the concrete schema recommendation and measured alternative
to the owner. No critical spike acceptance blocker remains.** This supersedes the
intermediate and baseline assessments retained below; it does not accept the
schema on the owner's behalf or assert production readiness.

This final pass reviewed the complete 153-line `LargeNativeNodeExperiment.java`,
its runner/report, the scoped-expiry source addition, updated README/proposed DDL,
and complete `choice-04` and `large-node-proof-02` outputs and summaries. The two
CSVs match their outputs' `METRIC,` lines, and the preserved outputs match the
writer's raw logs. Source fingerprints:

- `StorageChoiceExperiment.java`:
  `5268b044a418d91ca48ced67a647a1dfe074a8cc4120d873bd442d8783f7fab6`.
- `LargeNativeNodeExperiment.java`:
  `1da6d1867a8b0e4790777165668c7ed47584f5e67fe397c60febbda000155ce1`.

### SR-4 closed: physical fragments preserve the large native node

The addendum stores a 24,562-byte UTF-8 text scalar inside one native paragraph,
with a total native document size of 25,169 bytes. A native skeleton/header plus
six physical text fragments reconstruct the exact original JSON, including all
five native IDs, attributes, order, ruby, RTL and emoji context. Physical UUIDs
remain separate from semantic identity (`LargeNativeNodeExperiment.java:43`).

The replacement and insertion at lines 54–70 independently construct expected
native documents and compare them with database reassembly. Both change one
fragment, retain the other five IDs and reuse the header. Fragmentation operates
at Unicode scalar boundaries; old and new native histories remain exact. Direct
deletion of a referenced fragment fails by FK, and bounded collection after
releasing old pins reclaims obsolete fragments while retaining the current
document (lines 75–82). The largest actual stored object is 4,115 JSONB-text bytes.
The README and DDL now explicitly distinguish the 16 KiB physical cap from the
native scalar/paragraph envelope; the earlier material limit conflict is removed.

Independent rerun:

```sh
set -o pipefail
R74_LARGE_PORT=15478 bash docs/engineering/evidence/epic-74/storage/run-large-node.sh review-proof-01 2>&1 | tee /tmp/mnema-r74-large-review-proof-01.log
```

Exit zero with `LARGE_NATIVE_NODE_ALL_ASSERTIONS`. The initial save writes
23 rows and 36,952 B WAL; replacement and insertion each write 11 rows and
6,872 B WAL, matching the writer's physical-work results. The independent
replacement/insertion latencies were 6.484/5.894 ms, single samples rather than
SLO evidence. The run's log SHA-256 is
`7a71a009cb309e5e032cb04f3aa30d43a96911ccc689dfdb1dc9883e4149d8c3`.
The uniquely named `mnema-r74-large-review-proof-01` container is retained stopped.
Runner shell syntax passes. No implementation files were edited by this reviewer.

### SR-5 closed: expiry selection is indexed by scope and deadline

`StorageChoiceExperiment.java:71` and the proposed DDL now define the expiry index
with `(scope, expires)` / `(scope_id, expires_epoch)`. The new proof at lines
452–465 uses the actual `FOR UPDATE SKIP LOCKED` query after adding 20,000 foreign
expired roots, 5,000 local future roots and 16 local expired roots. The stored
`EXPLAIN (ANALYZE, BUFFERS)` plan constrains both columns in `Index Cond`, returns
eight rows without filtering foreign/future roots, and uses three scan buffer
hits (11 including row locks). This closes the identified missing scope bound.
The new source and full writer plan were independently inspected; the main
experiment was not rerun again because SR-1–3 and GC race/restart behavior already
have an independent successful predecessor run and this correction is isolated.

### Decision boundary and handoff

Recommend asking the owner to adopt scoped UUID immutable blocks/paged manifests
with normalized FK edges and internal native-text fragments as the C74-1 target,
with bounded deltas plus prepared membership generations presented as the viable
alternative. The choice favors changed-path locality and a common graph/retention
model, while accepting the tree candidate's measured extra long-item WAL and
foreground latency. The latest `choice-04` long-edit WAL is 19,820,728 B for the
tree and 16,659,080 B for delta; allocated growth is 11,042,816 B versus
16,228,352 B. Delta wins the measured long-item latency. There is no universal
benchmark winner and no basis to restore the old bigint-array WAL claim.

The fragment proof covers one long slot and a bounded manifest, not a finished
general chunker: cross-fragment deletion, repeated growth/rebalance, multiple
large slots, typed receipt validation and integration with the semantic page
kernel remain normal production implementation obligations. Their generalization
must preserve the proved native identity/round-trip and bounded-object contracts;
it must not introduce a smaller paragraph limit. The intermediate review's
measurement qualifications and future fork/projection limitations also remain.
These disclosed obligations do not require another broad research cycle before
the owner chooses the demonstrated physical direction.

The lead retains ownership of the exact-candidate full repository quality gate,
GitHub delivery, owner decision and subsequent production-quality slices. No
production schema migration, deployment or application acceptance is claimed.

## Intermediate assessment: UUID/normalized-edge extension

**Do not request owner schema acceptance yet. SR-1, SR-2 and the bounded SR-3
comparison are closed. SR-4 still lacks large-native-node preservation; SR-5
safety passes, with one small expiry-index bound correction remaining.** This
assessment supersedes the original bigint-baseline review retained below. A PASS
label in the experiment is not evidence for an unexecuted product behavior.

Read all 493 lines of `StorageChoiceExperiment.java`, the updated storage README,
`proposed-ddl.sql`, runner, `choice-03-summary.csv` and complete writer output.
The frozen source SHA-256 is
`4b4afa6c190b8a0dc598a4d8cc334ed9dae27f9f2b81cdc921f66d8c7c5fca91`.
The writer's preserved `choice-03-evidence.txt` matches the raw `/tmp` log and
has SHA-256 `6d4f66003c99ff6393c6ddb6b27a17443a4819619c3680f03ac71287b8624cce`.
Its summary matches the complete set of `METRIC,` lines exactly. Source and DDL
line numbers in this section refer to this extension, not the old executable.

### Independent execution

```sh
set -o pipefail
R74_PORT=15476 bash docs/engineering/evidence/epic-74/storage/run.sh review-choice-01 2>&1 | tee /tmp/mnema-r74-storage-review-choice-01.log
```

Exit zero; all individual assertions and `CHOICE_EXPERIMENT_ALL_ASSERTIONS`
passed, then VACUUM/ANALYZE completed. PostgreSQL 18.4, Java 21.0.11 with 1 GiB
maximum heap, existing dependencies and the reported container configuration were
used. No full Gradle gate was run by this lane. The independently named container
`mnema-r74-storage-review-choice-01` is retained **stopped**; no resource was
deleted. Log SHA-256:
`955e12cccd013ae45e53af7611c16f4f9975032674ce2ce009861647b10c5b23`.
The source fingerprint remained unchanged after the run. Runner shell syntax
passes; configurable loopback port and early cleanup trap fix its earlier
independent-rerun limitation.

### Rechecked findings

| Finding | Current result and evidence |
| --- | --- |
| SR-1 | Closed for the spike. Lines 398–431 perform revision/head/version/projection/receipt writes through the worker's connection, observe all components from another connection before and after disconnect, preserve staging and test four concurrent identical commands returning one complete result. A changed payload and stale head fail. |
| SR-2 | Closed for the spike. Lines 191–254 implement redistribution/merge/root collapse/empty leaf, verify 16–32 occupancy and a current-count height inequality, grow across 12 boundaries to 1,057, shrink to zero, regrow/churn and compare current and retained key/value snapshots. Required large membership fixtures also verify the resulting tree. This is a bounded feasibility proof, not exhaustive production property coverage. |
| SR-3 | Closed as a comparison of the exercised candidates, with the limits below. Lines 285–394 perform actual checkpoint rollover, mixed operations, byte/count replay bounds, equivalent full native-document transfer and exact equality at seven history positions. A separately prepared 100k generation supports direct forks and a private sparse edit. The unbounded SQL document-rewrite comparison is no longer used as the efficient candidate. |
| SR-4 | Partially corrected: actual UUID/native JSONB/normalized scope FKs, byte validation and short-item round-trip now execute. Native-node rejection at 16 KiB still conflicts with required large-paragraph handling; see the remaining blocker below. |
| SR-5 | Core safety corrected: lines 434–483 execute page→page→block sharing, source-root removal, orphan reclamation, durable candidates, rollback/resume, actual lock-wait interleavings and staging grace. Candidate selection and outgoing work are bounded. The expiry selection index needs the narrow correction below before calling the whole protocol bounded by scope. |

### SR-4 remaining blocker: physical block limit becomes a native-node limit

`block` rejects a whole native node above 16,384 JSONB-text bytes at lines
109–117. `boundaries` proves only rejection of a 16,385-byte node (256–272).
README representation text and proposed DDL explicitly defer transparent large
node storage. This is a real remaining contract decision, not merely a future
production optimization: the architecture requires splitting large physical
blocks at supported node boundaries even when one paragraph is enormous
(`revision-storage-and-runtime-boundaries.md:65`). A physical 16 KiB block cap
must not silently reduce the accepted native document/editor envelope or force
the user to divide one paragraph into different semantic nodes.

Required bounded continuation: store one paragraph larger than 16 KiB but within
the proposed native envelope through internal fragments, retaining its original
paragraph and text IDs, attrs and exact text. Read it back as the same native
document; execute a tiny replacement and insertion, prove unaffected fragments
reuse their IDs, verify every stored fragment's byte limit, and measure actual
rows/WAL. Physical fragment identity must remain distinct from semantic node
identity. No production editor or general distributed storage system is needed.
The lead has assigned this as a separate experiment. Alternatively, reducing the
native paragraph limit requires an explicit material product decision; it cannot
be implied by routine approval of the storage schema.

### SR-5 remaining narrow bound: expiry index omits the scope prefix

Classification: missing bounded-query evidence/design risk, not observed data
loss. Source line 70 creates `root_expiry(expires)` while line 449 queries
`scope = ? AND expires <= ? ORDER BY expires LIMIT 8`. The DDL sketch has the
same shape. Although at most eight roots are removed, that index may scan many
expired roots belonging to other scopes to find them; another scope-only index
may instead scan many unexpired roots. LIMIT alone does not bound examined work.
The candidate queue already uses the appropriate `(scope, eligible, object_id)`
index and is not affected by this observation.

Required correction: prefix the expiry index with scope in the harness and DDL,
then verify the query on a skewed fixture with other-scope expired roots and
same-scope unexpired roots. This is an index-and-query proof, not a request for
a complete production collector. Preserve the tested atomic release/enqueue and
new-reference FK arbitration.

### Measurement interpretation and owner tradeoff

The new encoding reverses the old long-edit WAL conclusion. In the writer run,
tree WAL is 19,857,912 B versus delta's 16,659,016 B; the independent run measured
19,861,216 B versus 16,660,512 B. The respective writer-run allocations are
11,083,776 B and 16,236,544 B. The tree therefore has **more WAL but less allocated
relation growth** in this fixture. These are different measurements. Delta's
long-edit/read foreground latency is also lower in both runs. Neither conclusion
may be replaced with the original bigint-array “four times less WAL” result.

The 100k/100-change writer workload instead emits 2,870,000 B tree WAL versus
12,821,328 B delta WAL, while taking 1,636 ms versus 809 ms in these deliberately
simple implementations. Prepared delta forks are cheap and page efficiently;
generation preparation emits about 31.5 MB WAL for 100k entries and is correctly
measured separately. Choosing pages is defensible for locality and a common
immutable graph, but would be an engineering tradeoff in light of the faster
delta results, not selection of a universal benchmark winner.

Additional bounds on that interpretation:

- Delta membership mutation starts with an already materialized in-memory list
  and copies it at line 289. Its measured mutation latency is not proof of a
  bounded request path for loading arbitrary current decks. The comparison makes
  delta viable; production would still need the selected projection/rollover seam.
- Prepared fork chains start from an unedited ready generation; the sparse edit
  occurs after all 25 forks. Inheriting private overlays when forking an already
  edited fork is not executed here. The measured direct-generation mechanism is
  valid, but should not be described as full future fork feature acceptance.
- The tree fork benchmark has two envelope rows and directly reads the known
  root; the delta fixture has one namespace row and joins its generation. The
  README properly discloses the envelope difference; timings are kernel cases.
- Complete item-revision envelopes, global key uniqueness across pages, native
  capability validation, ACLs and actual search/exercise projections are owning
  production integration obligations. The fixed small graph and model checks
  establish feasibility without claiming those implementations exist.

No additional blocker is inferred from absent production queues, scheduler,
backup deployment, or exhaustive frontend behavior. Keep owner schema selection
on hold for the large-node seam and the bounded expiry-index correction, then
reassess those exact additions without repeating completed work.

## Original bigint-baseline review (retained evidence)

**Decision: continue the bounded R74-S experiment; do not request acceptance of a
persisted production schema yet.** The evidence establishes a useful structural
sharing direction and the cost of the measured full-value JSONB control. It does
not yet establish all critical invariants required by
[R74-S](../../../epic-74-refinement.md#r74-s-выбрать-реализацию-экономных-revisions).
The storage README reaches the same qualified conclusion at lines 207–210. Moving
its critical gaps to an implementation ticket does not satisfy that gate.

This is an independent read-only review of the complete 560-line harness,
storage README, CSV, runner and proposed DDL, against the Epic #74 execution
prompt, refinement and revision-storage architecture. Base supplied by the lead:
`33a71f814185a16e922923e034518e25baeadbb8`; shared branch supplied by the lead:
`epic-74/research-contracts`. No Git/GitHub operations, dependencies, migrations,
production resources or reviewed files were changed. The SRE and finding-validation
skills governed classification and the distinction between inspected and executed
evidence. Line numbers below refer to the reviewed snapshot.

## Evidence independently checked

- Read `/tmp/mnema-r74-storage-final-03.log`: PostgreSQL 18.4, measured batches,
  index-scan plan, individual PASS lines, final `PASS,all assertions`, successful
  vacuum and final database size are present.
- `diff -u summary.csv <(awk '/^(ENV|CONFIG|METRIC|ROWS|FINAL_BYTES|AFTER_VACUUM_DATABASE_BYTES),/' /tmp/mnema-r74-storage-final-03.log)`
  produced no differences when run with the full repository paths.
- `bash -n docs/engineering/evidence/epic-74/storage/run.sh` passed. The runner was
  inspected, not independently executed: it and Java hardcode port 15474, so they
  do not support the separate review port required for this lane. No review
  container or other test artifact was created.
- Harness SHA-256:
  `a89473602fb3eb6ec95c9876a395cb1084e201109afc2b249bed8094e834bc9b`.
  CSV SHA-256:
  `f299bbedaab93bc0c7c97d4e2ca01d2582e880965522a2d1ac65845b5a0b549a`.
- The reported long-edit allocation sums are correct: tree 3,670,016 B and
  node-head delta 14,098,432 B. Their measured WAL is 3,550,632 B and
  14,064,664 B respectively. These are batch allocations with maintenance disabled,
  not retained bytes after candidate-specific vacuum or backup measurements.
- Tree member edits write 2/3/4/4 pages for 1k/10k/50k/100k fixtures. Metadata
  creates one envelope without inserting blocks/pages. The direct fork roots
  and sparse `(deck, member)` progress key demonstrate the proposed namespace
  mechanism. The 25-generation fork fixture has bounded first-page reads without
  following ancestry. These are kernel facts, not integrated authorization or
  scheduler verification.
- Read/write counters intentionally omit some SQL and do not aggregate concurrent
  clients. The README discloses this, along with warm caches, absent warm-up,
  limited samples, omitted common publication overhead and synthetic identifiers.
  Those qualifications are appropriate; zero hot-head counters are not zero work.

## Findings that block schema acceptance

### SR-1 — Crash test commits a revision outside the failed transaction

**Confirmed harness defect; high acceptance impact; high confidence.**

At `RevisionStorageSpike.java:404`, `revision(staged)` is invoked on the outer
spike, whose `db` is in autocommit mode. Only the subsequent UPDATE uses `worker`.
The revision therefore commits independently before the worker disconnects.
Assertions at lines 405–407 only compare `deck.head`, so this test passes while a
new revision row survives. It does not demonstrate the required atomic
revision/head/result publication. This is established by connection ownership and
Java argument evaluation in the inspected source, not an independent runtime rerun.

Bounded correction: create a worker-scoped spike and perform revision, head,
receipt and a representative changed projection together on that connection.
Stage only blocks/pages separately. Observe from a second connection before
disconnect and afterwards: no new published revision/result/projection/head is
visible, while staged objects survive. Assert exactly one complete result after
retry. Add simultaneous identical-command retries, as current idempotency checks
are sequential after two different commands race. No application API is needed.

### SR-2 — Tree height/occupancy and empty-root behavior remain unproved

**Missing required evidence; high acceptance impact; high confidence.**

`mutate` rejects an empty replacement at line 129; `build` accesses the first
element of an empty list at line 121. Deletion only removes empty children, and
lines 160–164 split overflow without merging underfull pages or collapsing a
single-child internal root. The 600-step model at lines 358–372 starts near 1,050
members and alternates insert/delete/move; it does not shrink a large tree to
empty or establish occupancy/height bounds after repeated churn. Its assertion
checks keys, not content-reference parity. Existing historic snapshots can remain
correct while traversal height reflects earlier shape rather than current N.

The architecture explicitly requires split/merge/order/identity/projection
property evidence before adoption (`revision-storage-and-runtime-boundaries.md:101`).
It is not enough to label this an eventual production balancing proof.

Bounded correction: in the isolated kernel, specify empty-root representation,
minimum occupancy, redistribution/merge and root collapse. Add deterministic
boundary cases around fanout and height changes, grow/shrink-to-zero/regrow,
and bounded seeded churn against independent key/value and retained-snapshot
models. Count page reads/writes and verify the claimed bounds against current
member count. This is feasibility evidence for the proposed tree, not the entire
production storage kernel or an HTTP implementation.

### SR-3 — The rejected alternative lacks equivalent semantic/read evidence

**Confirmed comparison limitation and missing evidence; high acceptance impact;
high confidence.**

The tree long-read benchmark transfers 128 payloads through JDBC at lines 307–313
and checks only their count. The delta historical query at line 336 returns only
`octet_length(doc::text)`, is tied to the first control-case checkpoint and never
benchmarks historical reconstruction from the efficient node-head candidate.
It applies repeated whole-document `jsonb_set` operations in SQL; an alternative
that transfers the checkpoint and patches once and applies stable-node edits in
memory is not measured. The existing result is evidence for that implementation,
not an inevitable replay cost of bounded deltas. Correct order, stable node IDs
and exact historic values are not asserted by either size/count check.

Membership checkpoints at line 212 store the original `keys`, not the edited
materialization, so the timed checkpoint does not prove rollover correctness.
Only replacement is implemented for delta membership. No delete/reorder/bulk,
fork, fork edit, or ready indexed-materialization case tests that alternative
(README:202–205). The architecture's alternative explicitly permits prepared
immutable materializations; dismissing it from full-array reconstruction alone
would not be a fair comparison. A byte-triggered checkpoint is also absent.

Bounded correction: run the same edit sequence for both candidates and compare
complete ordered content/key/value results to one independent model at revisions
0/1/31/32/33/999/1000. Return equivalent requested results to JDBC and measure
bytes/work as well as latency. Exercise real checkpoint rollover and its byte
trigger. Add delta delete/reorder/bulk on a small boundary fixture, then one
100k prepared generation with a direct fork-of-fork, independent edit and
100-member page. Measure generation preparation separately from foreground work.
This needs no general fork/pull engine. If choosing the tree after that evidence,
state the measured alternative and engineering tradeoff, not universal superiority.

### SR-4 — Proposed physical encoding and reference integrity are not measured

**Missing schema evidence; high acceptance impact; high confidence.**

The executable uses bigint SQL arrays (`Java:55–58,81–86`) and small synthetic
node IDs/payloads (`281–292`). The proposed DDL uses composite scope/UUID keys,
JSONB page payloads, 16 KiB limits and node contracts. It leaves normalized FK
edges versus validated JSON references undecided (`proposed-ddl.sql:33–36`).
There is no byte-based split or failure case for a large native node. The report
accurately discloses this (`README:123–129,163–167`), but adopting the concrete
DDL/page budget would go beyond what was measured. The short-item behavior is
inferred from a single block within a large item, not a separate save/read fixture.

Bounded correction: choose one proposed encoding for the experiment, use UUIDs
and representative native envelopes, and measure page/block sizes and one
short-item, near-limit and over-limit large-node path. Demonstrate unchanged IDs,
semantic reconstruction and a feasible identity-preserving chunk/rejection rule.
Test one dangling edge, wrong page kind, incorrect subtree count and cross-scope
reference rejection before publication. Repeat affected storage batches with
that representation before fixing byte budgets. This does not require every
future native node, renderer, or production migration.

### SR-5 — GC proof stops before the reachable content graph and bounded protocol

**Missing critical safety evidence; high acceptance impact; high confidence.**

`reachabilitySafety` uses synthetic leaf values without corresponding content
blocks (`Java:442–473`). `sweepCandidate` traverses internal page references only
and deletes only pages (`477–489`); content block references in leaf `counts`
are never traversed or reclaimed. Direct root FKs are useful but cannot establish
that shared fork/draft/attempt content survives collection. The locking example
rechecks an entire scope for each candidate under an exclusive lock. It contains
no bounded mark state, epoch, staging lease or restart boundary. The advisory-lock
race starts a worker before commit but does not explicitly observe that the
collector has reached the lock wait, weakening deterministic interleaving evidence.

Bounded correction: use a small typed page-to-block graph shared by fork, draft
and attempt roots. Implement only an experimental batch/epoch or reference-edge
protocol sufficient to demonstrate maximum work per transaction, resume after
an interrupted batch, source-root removal, and survival of live descendant
blocks. Coordinate a new root and a staging reference between mark and sweep
with deterministic barriers; prove stale candidates cannot delete either.
Include one reclaimable orphan block/page. The proposed reference encoding must
support this protocol. A full production collector, every retention policy,
worker fleet and backup system are legitimate later implementation work.

## Runner, repeatability and non-blocking followups

`run.sh:6–32` uses a quoted prefixed container name, refuses an existing name,
requires a cached image/JDBC JAR, binds PostgreSQL to loopback, bounds container
resources and stops its own container on exit. Java uses `CREATE SCHEMA`, never
DROP/reset. With a fresh suffix and free port, the inspected flow is appropriately
isolated. It intentionally retains the stopped synthetic database, which is
disclosed. Shell syntax passes. No deletion or resource cleanup occurred here.

Support a validated explicit loopback port shared by runner/JDBC to enable
independent reruns. Install cleanup after creation even if startup fails; record
Java version, image digest and the assertion/plan output in durable evidence, as
`/tmp` does not survive all machines/sessions. These are repeatability improvements,
not evidence of a production security vulnerability.

Production service integration, ownership checks, actual search/exercise
projections, saturated queue recovery, cache failure, selective pull, study
scheduler behavior and full repository gates remain with their owning slices.
The synthetic projection model must prove manifest parity before schema choice;
it need not implement those product services. Retained-size/vacuum/backup claims
need candidate-specific evidence before capacity claims, but lack of a full
production backup deployment does not by itself block this bounded experiment.
Do not describe the total post-vacuum database number as candidate backup growth.

## Next handoff

The storage writer owns the five bounded evidence corrections above. Keep the
schema proposed, label the continuation R74-S, and retain the existing raw result
as a baseline. After a fresh successful targeted run, the reviewer checks the
new code, exact output equivalence, fault boundaries and updated physical
measurements. Only then present the owner with the concrete schema recommendation,
rejected alternative and remaining production implementation work. Choosing a
further experiment requires no new product decision and must not be disguised
as schema acceptance or a premature C74-1 implementation start.

The full backend/frontend repository quality gate was not run in this read-only
review lane; the lead owns it on the exact delivery candidate. This report claims
code inspection, raw-result reconciliation and shell syntax validation only.

Official PostgreSQL 18 documentation was consulted during review:
[transaction isolation](https://www.postgresql.org/docs/18/transaction-iso.html)
supports the post-lock READ COMMITTED snapshot reasoning;
[administrative functions](https://www.postgresql.org/docs/18/functions-admin.html)
defines relation/WAL measurements; [TOAST](https://www.postgresql.org/docs/18/storage-toast.html)
supports distinguishing changed large values from application-level revision sharing.
