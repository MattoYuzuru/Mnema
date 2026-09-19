# R74-S: initial bigint-array baseline (superseded)

Historical report for `final-03`, retained as evidence. Its driver command describes
the former runner; the current runner executes `StorageChoiceExperiment.java`.
Read [the current report](./README.md) for the UUID/native encoding, corrected
fault tests and equal-output alternative. The initial recommendation and unresolved
gates below are historical and must not be cited as current decision evidence.

Status: research evidence and proposed next work; **no production schema decision
has been accepted**. Owner review is required by the Epic #74 execution prompt.
The executable lives only in the Learning API test source tree and creates an
isolated `r74_storage` schema. It does not load application migrations or services.

## Decision supported

Prefer immutable semantic blocks with persistent sequence-tree pages for the
general revision representation. The measured alternative—stable-node deltas,
node-level current projection, and a full checkpoint after at most 32 patches—is
viable and often cheaper in foreground edit latency. Its large-document history
and historical membership reconstruction cost more bytes/work. It remains the
fallback if implementing and verifying page maintenance proves too expensive.

Do not select a full JSONB document as the mutable head merely because PostgreSQL
supports `jsonb_set`: changing one field can rewrite its TOAST value. That control
case is measured separately from the efficient node-level delta alternative.
Neither result licenses a claim that PostgreSQL stores only changed characters.

Final successful run `final-03` (2026-09-06), with full batches in
[summary.csv](./summary.csv):

| Operation | Persistent blocks/pages | Bounded delta with node head |
|---|---:|---:|
| 1,000 edits, 256 KiB text: WAL | 3,550,632 B | 14,064,664 B |
| Same edits: allocated heap + TOAST + indexes | 3,670,016 B | 14,098,432 B |
| Same edits: rows inserted/updated | 3,000 | 2,000 |
| Same edits: p50 / p95 | 4.00 / 6.04 ms | 1.94 / 3.80 ms |
| Full historical document read | 6 payload fetches, regardless of edit number | checkpoint plus at most 32 replay steps |
| 100k membership historical read, p50 | 3.56 ms / requested 100 members | 16.11 ms / complete array checkpoint + patches |

The full-JSONB-head control emitted 311,399,888 B WAL. The efficient delta alternative
reduces that substantially; the tree still emitted approximately four times less
WAL in this fixture. Checkpoint interval, document/block size, compression and edit
locality can change the ratio. Delta's full-array membership checkpoint is this
implementation's cost; a separately prepared indexed materialization can avoid
whole-array reads, with additional projection/job machinery not measured here.

At 1k/10k/50k/100k, point membership edits copied 2/3/4/4 pages. A metadata save
always wrote one envelope and no pages. The 100k fork chain wrote two rows per fork
and fetched its first page in eight payload reads; p50/p95 including creation were
6.10/9.20 ms. A 100-change tree batch at 100k took 548 ms and wrote 400 pages in
this intentionally uncoalesced implementation. The hot-head measurement reports
an entire eight-client round: p50 33.59 ms, p95 60.77 ms; these are not per-request
latencies. Its per-instance write/read counters are not aggregated across clients
and must not be interpreted as zero database work.

The choice is provisional. This spike proves structural sharing and specific
concurrency/reachability mechanisms, not a complete production storage subsystem.
The unresolved acceptance gates below must remain visible during owner review.

## Environment and reproduction

Base supplied to this lane: `33a71f814185a16e922923e034518e25baeadbb8`.
Host: Apple M2 Pro, 16 GiB RAM; Docker/Colima VM: 4 CPUs, approximately 7.74 GiB.
Database container: 2 CPU limit, 2 GiB memory, 256 MiB shared memory and shared
buffers; 20 connections. Java: Temurin 21.0.11. JDBC: existing project dependency
42.7.13. Cached `postgres:18` resolves to PostgreSQL 18.4, aarch64, image
`sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15`.
No package, driver, image or build-file upgrade was performed.

`fsync`, `synchronous_commit` and `full_page_writes` are on; compression is pglz.
Autovacuum is off only in this disposable measurement container, avoiding
background maintenance WAL during read measurements. A manual vacuum/analyze runs
after the experiment. Checkpoint timeout is 30 minutes; WAL cap is 2 GiB.

From the repository root, choose an unused suffix and use the already present
JDBC JAR (override `R74_JDBC_JAR` if the cache location differs):

```sh
set -o pipefail
bash docs/engineering/evidence/epic-74/storage/run.sh final-03 2>&1 | tee /tmp/mnema-r74-storage-final-03.log
```

The runner refuses to overwrite an existing container/schema, exposes PostgreSQL
only on loopback port 15474, stops its container on exit, and retains its synthetic
database for inspection. It requires no Gradle resolution or new dependencies.
Raw output remains in `/tmp`; `summary.csv` is the compact measured result.
Do not run two copies on that port. Each SQL statement has a 30-second timeout,
locks a 5-second timeout, and concurrent checks have 10/15-second deadlines.
The fixed workload uses at most eight concurrent clients; no production or shared
service is targeted. Container failures or assertion failures stop the run.

## Workload and measurement interpretation

The sequence tree has fanout 32 at each level. Leaves store stable synthetic
member/node keys and content references; internal pages store child references
and subtree cardinalities. Inserts split pages; deletes remove empty pages;
reordering is remove+insert. No application cache is used. Database/OS caches are
warm after fixture creation; there is no claim of cold-disk performance.

Fixtures contain 1k, 10k, 50k and 100k members. The large document contains 128
semantic paragraphs with 2,048 deterministic pseudorandom ASCII characters each
(256 KiB of text plus JSON). This deliberately avoids overstating compression.
One stable paragraph is edited 1,000 times. The short-content case is a single
such block; no binary media are stored.

The delta candidate stores at most 32 patches after a checkpoint; the 33rd edit
creates the next complete checkpoint. The efficient candidate updates one current
node row; the control updates one full-document JSONB row. Delta membership reads
actually fetch the checkpoint and apply the ordered replacements. The long-item
historical read reconstructs the document through bounded SQL replay.

`logical_writes` counts algorithmic rows inserted/updated by the measured kernel,
not PostgreSQL internal index/TOAST tuples. The final ROWS inventory records exact
live table rows. Heap/TOAST/index columns are allocated relation-byte differences
over the **whole named batch**, and WAL is `pg_current_wal_insert_lsn()` distance.
TOAST indexes are included in index bytes. Free-space/visibility maps, WAL archive,
replicas and backups are not included in these relation columns. Page allocation
means a single edit can show zero physical allocation while still producing WAL.

Timing includes JDBC round trips and commit. There are 25 samples for ordinary
reads/edits, 1,000 for long edits, and one sample for fixture/bulk/checkpoint
operations. With 25 samples p99 is the observed maximum, not a reliable tail
estimate. There is no separate warm-up discard, repetition across machines,
production workload calibration or production SLO. Source compilation precedes
the benchmark. The whole fixture build is a setup operation, not a request path.
The `sql_reads` counter records explicitly instrumented page/payload fetches, not
all metadata/CAS/receipt queries. Physical measurements include every SQL effect.

Membership microbenchmarks isolate root/page or patch costs. Shared content,
item/deck revision envelopes, authorization, receipts and projection updates are
not included in every measured mutation. The long-document tree stores content
blocks/pages and keeps the list of historical root IDs in the harness; production
revision rows add bounded common overhead. IDs are bigint surrogates: the proposed
UUID/native payload encoding needs a follow-up measurement before byte budgets
are accepted. These qualifications prevent treating kernel numbers as API cost.

## Verified mechanisms

- Metadata operations insert one revision envelope and zero pages/content blocks.
- Single membership edits copy the affected tree path; first/historical pages
  traverse subtree counts, without replaying earlier deck revisions.
- Long-item edits write one block plus two pages. Every sampled historical root
  resolves the entire document in five page fetches plus one block fetch.
- A 25-generation chain of forks gives each deck its own revision envelope,
  source/base provenance, and directly shared root. Creation writes two rows;
  resolving head/root and reading the first 100 members stays bounded by eight
  fetches at 100k members, independent of fork depth. Source removal retains
  content, and progress keys distinguish source/fork namespaces.
- Six hundred deterministic insert/delete/move operations compare the full
  selected order to an independent `ArrayList` model across page boundaries.
- A prepared index containing 10 eligible entries out of 100k members supports
  a primary-key ordinal lookup. Its full scan/build is explicitly outside the
  request operation and separately timed. A coprime affine permutation visits
  each eligible ordinal once; a cursor computes one ordinal without a deck scan.
- Concurrent publishers on one expected version have one winner. Retry with the
  same command/payload returns its stored revision even with the old expected
  version; changed payload conflicts. Twenty hot-head rounds run four publishers
  and four saved-snapshot readers. No scheduler implementation is introduced.
- Disconnecting a worker before commit exposes neither its head update nor a
  partial saved snapshot. Staging survives independently.
- Direct root FKs and transitive reachability protect fork/draft/attempt roots.
  A stale GC candidate is revalidated after an exclusive scope lock; a new pin
  committed under a shared lock survives. An unreferenced staged page is removed;
  a collector disconnect rolls back its uncommitted deletion.

## Proposed production boundaries and outstanding gates

See [proposed-ddl.sql](./proposed-ddl.sql). It is a review sketch, deliberately not
a migration. Its proposed fanout is 32; semantic block/page maximum is 16 KiB,
subject to tests using the actual UUID/native encoding. The experiment used much
smaller pages and 2 KiB text blocks; **16 KiB is not a measured optimum**. A large
individual paragraph requires a stable-node-preserving physical chunk contract;
the harness does not supply the editor/native-format decision.

Keep 100 members per response, at most 100 changed memberships and 1 MiB command
JSON as proposed synchronous ceilings. Larger publication prepares immutable
objects in bounded durable batches, validates a receipt, then performs one short
head/receipt transaction. The naive bulk fixture deliberately repeats path writes;
coalescing a batch can improve it but is not credited by these measurements.
No worker deadline is presented as an SLO on this evidence.

Final publication must combine ownership, expected-head CAS, command identity,
validation receipt, root selection, bounded projection updates/generation switch,
revision and result in one transaction. Production should reuse the existing
platform CAS/receipt foundation; the spike uses independent SQL to test mechanics.

Reachability roots must include saved heads/history, fork/pull bases, drafts,
attempt/replay pins, export/offline leases and moderation holds. Every staged
reference acquisition and root publication participates in the lineage scope
protocol. The spike's recursive revalidation scans one scope and is **not a
bounded production collector**. Production needs resumable mark batches, grace
epochs, protected staging leases and restartable sweep; a stale mark alone cannot
authorize deletion. No retention duration is chosen here.

Open gates, owned by the subsequent storage implementation/review:

1. Complete page occupancy/merge/root-collapse/empty-root rules and property tests;
   this harness tests splits and empty-child removal, not a production balancing
   proof. Byte limits and UUID/native encoding must be exercised.
2. Validate typed cross-page references and trusted reuse scopes. Compare normalized
   FK edges with immutable arrays plus validation receipts; no array entry gets an
   FK merely because its containing root has one.
3. Implement resumable bounded GC and test crash/restart across mark/sweep epochs
   and new staging references. The tested lock/FK mechanisms are necessary pieces.
4. Demonstrate projection parity, bounded job admission and saturated queue recovery
   using the accepted production kernel. This spike has no queue, HTTP endpoint,
   search projection, ACL service, selective pull engine or actual study scheduler.
5. Expand the delta membership candidate to delete/reorder/bulk and cheap fork
   materializations if fallback selection remains under consideration. Those
   operations are measured for the tree; delta comparison here covers point
   replacement, full checkpoint and reconstruction. Do not claim full parity.

Unclosed critical invariants block accepting a persisted production schema. The
evidence supports choosing the next implementation experiment and explicitly
rejecting full snapshots as the general history representation, while leaving
the schema `proposed` for owner review.

Next reviewable slice: **C74-1a, bounded immutable page kernel**. Implement the
accepted leaf/internal encoding and stable-key sequence operations with byte
validation, empty-root behavior, split/merge/root collapse, deterministic ordering
and randomized model comparisons on real PostgreSQL. Exclude HTTP/UI/drafts/GC
execution and production migration application. Acceptance: both semantic order
and read/write bounds hold at 1k/10k/50k/100k with actual UUIDs, unchanged pages
reuse IDs, and malformed/cross-scope pages are rejected before publication.

## Official sources consulted

[PostgreSQL 18 TOAST](https://www.postgresql.org/docs/18/storage-toast.html)
motivated separating block sharing, whole-value replacement and physical storage.
[Administrative functions](https://www.postgresql.org/docs/18/functions-admin.html)
define the relation-size and WAL-position measurements.
[Transaction isolation](https://www.postgresql.org/docs/18/transaction-iso.html)
informed the explicit expected-head lock/CAS and post-lock READ COMMITTED snapshot
used for stale GC candidate revalidation. These sources describe mechanisms; they
do not establish Mnema production performance.

Verification is this standalone targeted executable and its assertions. The lead
owns the full repository quality gate before any push/PR; this evidence does not
claim that gate has run. No canonical schema, dependency file, migration or
application route was changed by this lane.
