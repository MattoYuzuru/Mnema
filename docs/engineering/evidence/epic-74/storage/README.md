# R74-S: schema-choice experiment

Status: research complete; independent re-review passed and the owner subsequently
accepted scoped UUID immutable blocks/pages, normalized FK edges and physical native-text
fragments. This report preserves the pre-decision experiment and does not itself
apply a production migration, dependency or application change. The proposed DDL
remains historical research evidence, not an executable migration.
The earlier [bigint-array baseline](./baseline-report.md) and [its CSV](./summary.csv)
are retained. They do not describe the measured encoding proposed below.

## Accepted recommendation and measured alternative

Recommend immutable native blocks and persistent pages with **normalized UUID FK
edges**, using one graph representation for ordinary and historical content.
This recommendation favors bounded snapshot access, local large-deck mutations,
and one reachability protocol. It is **not** a claim that this candidate wins every
latency or WAL comparison.

The efficient alternative is viable: a node-level mutable head, native checkpoints
and at most 32 patches, with a byte budget that can checkpoint earlier. It is
faster for the measured long-item workload and uses less WAL than the normalized
page implementation. Indexed immutable membership generations prepared in jobs
also give this alternative cheap forks and bounded first pages. That mechanism
was executed, not dismissed as impossible.

Prefer pages as the general representation because a large membership checkpoint
must materialize the current selection while pages update changed paths. The delta
alternative needs both bounded replay and ready indexed generations, plus their
rollover/preparation rules. It remains a reasonable owner choice if simpler item
edits are more important than a uniform storage kernel. A later optimization or
hybrid requires its own evidence; neither is silently adopted here.

The old claim of approximately four times less long-edit WAL applied to bigint
arrays without normalized edge rows. It **does not apply** to this proposal.

Final `choice-04` results (bytes are complete-batch allocation/WAL, not backups):

| Workload | UUID blocks + normalized pages | Efficient bounded delta |
|---|---:|---:|
| 1,000 native-document edits: WAL | 19,820,728 B | 16,659,080 B |
| Same edits: allocated heap + TOAST + indexes | 11,042,816 B | 16,228,352 B |
| Same edits: row mutations including page GC candidates | 42,000 | 2,000 |
| Same edits: p50 / p95 | 7.24 / 9.71 ms | 1.64 / 2.07 ms |
| Equal full-document read at revision 1,000: p50 | 5.54 ms; 6 payload queries | 3.44 ms; 2 payload queries |
| 100 changes in 100k-member deck: WAL | 2,853,528 B | 12,816,264 B |
| Same bulk command: elapsed, one sample | 1,278 ms | 1,031 ms |
| Fork plus first 100 members, p50 | 5.28 ms | 1.24 ms with ready generation |
| Ready-generation preparation for delta, 100k | not needed for direct shared tree root | 100,002 row mutations; 31,578,696 B WAL; 2,036 ms |

The page implementation intentionally repeats path copies for a bulk command;
it does not receive credit for unimplemented batch coalescing. The prepared delta
fork stores one namespace row; the tree fixture stores a namespace and revision
envelope. This bounded common-envelope difference is explicit, not a universal
fork-speed claim. Random UUID allocation and cache/host variance affect physical
allocation and timing, so reruns need not match byte-for-byte or millisecond-for-millisecond.

## Reproduction and envelope

Run from the repository root with an unused container suffix and free loopback
port. A reviewer can use a separate port without changing code:

```sh
set -o pipefail
R74_PORT=15475 bash docs/engineering/evidence/epic-74/storage/run.sh reviewer-01 2>&1 | tee /tmp/mnema-r74-storage-reviewer-01.log
```

The successful final writer run uses suffix `choice-04`, port 15474. Complete
stdout, assertions, image/version/configuration and row inventory are preserved
in [choice-04-evidence.txt](./choice-04-evidence.txt); measured batches are in
[choice-04-summary.csv](./choice-04-summary.csv). The final source SHA-256 is
`5268b044a418d91ca48ced67a647a1dfe074a8cc4120d873bd442d8783f7fab6`.
Earlier choice-03 evidence is retained as the independently rerun predecessor.

The runner validates ports 1024–65535, refuses an existing container, creates a
fresh schema, traps cleanup before startup, and stops its own container on exit.
It retains the stopped disposable database for inspection. It never drops an
existing schema, downloads an image or resolves new packages. The new executable
is `StorageChoiceExperiment.java`; `RevisionStorageSpike.java` is baseline code.

Host: Apple M2 Pro, 16 GiB; Colima VM: four CPUs and approximately 7.74 GiB.
Database: existing `postgres:18`, PostgreSQL 18.4/aarch64, image ID
`sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15`.
Container limits: two CPUs, 2 GiB RAM, 256 MiB shared memory, 256 MiB shared buffers,
20 connections. Java 21.0.11, maximum Java heap 1 GiB. Existing JDBC 42.7.13 and
Boot-managed Jackson 2.21.4/annotations 2.21 are read from the local Gradle cache.
No dependency change or installation was needed.

`fsync`, `synchronous_commit` and full-page writes remain enabled. Pglz compression,
30-minute checkpoints, 2 GiB WAL setting. Autovacuum is disabled in this isolated
measurement database, followed by explicit VACUUM/ANALYZE. There are at most four
concurrent application clients in this extension; the earlier hot-head baseline
used eight. Statements time out after 30 seconds, lock waits after 10 seconds,
concurrent results after 15 seconds. All fixtures are synthetic and bounded.

Each named measurement is one operation or ten samples; document edits have
1,000 samples. There is no warm-up discard or cold-disk run. PostgreSQL/OS caches
are warm, and there is no application cache. Ten-sample p95/p99 are maxima, not
stable tail estimates. Results are local feasibility evidence, never production
SLOs or user-capacity promises.

## Representation actually exercised

`object(scope UUID, id UUID)` stores immutable native JSONB blocks or page headers.
`edge(scope, parent, ordinal, node_key, child, amount)` stores a page's ordered
references with composite scope FKs, an incoming-edge index, and a maximum of 32
edges. References are not duplicated inside a JSON page blob. The harness validates
kind, child level, cardinalities, duplicate leaf keys and scope before inserting
a page. A separate direct SQL negative case verifies the child FK.

Non-root pages have 16–32 entries. Overflow splits; deletion merges or redistributes
neighbor pages; a one-child root collapses. Empty content uses a zero-entry leaf.
The height bound follows the current entry count: an internal root of height H
has at least `2 × 16^H` entries. Seeded tests traverse and verify these properties.

Native paragraphs have UUID node/text IDs, versions, attributes and child text.
The measured long document has 128 paragraphs of 2,048 characters, including a
Russian prefix, plus native envelope overhead. Both candidates receive the exact
same 1,000 replacements. Block size validation accepts exactly 16,384 bytes of
PostgreSQL JSONB text and rejects a single physical object of 16,385 before writing.
This is not a semantic paragraph/node limit. The separate
[large-node addendum](./large-native-node-report.md) proves a 24,562-byte text scalar
inside one paragraph: a native skeleton/header and six ordered physical fragments
reassemble exact native JSON with unchanged paragraph/text IDs. Both a tiny scalar
replacement and a short multibyte insertion reuse the other five fragments and
the header. All physical objects remain bounded (largest measured:4,115 bytes).

Membership fixtures use 1k/10k/50k/100k distinct UUID keys and selected immutable
short-content references. They isolate the storage graph: production deck-local
item revision envelopes, policies, actor/ACL columns and search projections are
not all present in every microbenchmark. Adding those bounded common records must
not be disguised as measured here. Incoming FK edges and GC candidate records
are included in the page candidate's physical measurements.

The efficient delta candidate reconstructs in Java after fetching one complete
checkpoint and its ordered patches. Native history reads return the same full
ordered JsonNodes as the tree and compare all values/IDs to the expected revision.
Membership edits use independent list comparisons after edit/delete/move/bulk;
additional retained fixtures cover insertion and both count/byte rollover.
Its 100k prepared generation is built in transactions of at most 1,000 rows,
remains unavailable until ready, and is reused by 25 direct forks. A sparse private
edit changes only the selected fork. Preparation is measured separately.

## Corrections to the five independent review findings

| Finding | Executed evidence in the new harness |
|---|---|
| SR-1 publication | One worker connection inserts revision, changes head/version, stores receipt and changes projection in one uncommitted transaction. Another connection asserts all four remain unchanged before disconnect and afterward. Staged content survives. Four simultaneous identical-command retries return exactly one committed result; payload reuse and stale heads conflict. |
| SR-2 tree invariants | Growth across 12 boundaries to 1,057; seeded shrink to zero; 350 regrow/churn operations; independent key/value comparisons, retained historical roots, minimum/maximum occupancy, split/merge/redistribution/root collapse and current-size height bound. |
| SR-3 fair comparison | Exact native values at revisions 0/1/31/32/33/999/1000 for both candidates; efficient checkpoint/patch transfer and client replay; real rollover; 180 mixed delta changes and all retained results; edit/delete/move/bulk on every required size; prepared 100k fork-of-fork and independent edit. |
| SR-4 encoding/integrity | UUID keys, native JSONB, normalized composite FK edges; exact physical16KiB boundary; short-item save/read; oversized semantic paragraph via physical fragments with preservedIDs/exactJSON and unchanged-fragment reuse; rejection of dangling/cross-scope/wrong-kind/wrong-count references; physical size/WAL measurements. |
| SR-5 bounded reachability | Typed page→page→block graph shared by source/fork/draft/attempt; bounded persistent candidate queue; orphan page/block collection; deterministic new-pin and staging races with an observed PostgreSQL lock wait; disconnect rollback of both object and queue; resumed collection; bounded staging expiry and grace. |

## Publication and collection boundary

Staging inserts each immutable object, its outgoing edges and its GC candidate
atomically. Durable roots or leases retain prepared objects. Final publication
owns revision, expected-head update, receipt and bounded projection change in one
transaction. The test uses independent SQL so a wrong connection cannot hide
behind mocked transactions. Production integrates the existing CAS/receipt
foundation instead of copying this test helper.

The GC experiment uses a bounded reference-edge protocol, not an unbounded
recursive mark query. It selects at most eight queue candidates with an index and
`FOR UPDATE SKIP LOCKED`. For each object it reads at most 32 outgoing edges and
checks indexed incoming edges, durable roots and revisions. Deleting an orphan
parent removes its outgoing edges and enqueues its children with a grace epoch.
Removing a root also enqueues the former target atomically. The graph is acyclic
because internal edges lower the level and leaves target content blocks.

FKs arbitrate a reference created after candidate selection: either the committed
reference protects the object, or reference creation must fail on a missing
target and retry from valid staged content. The test explicitly observes the
collector waiting on the uncommitted root's FK lock before allowing it to commit.
Staging pins use this same protection. Expiry processes at most eight leases per
transaction and enqueues targets after removal; it cannot delete content itself.
Interrupted collection rolls back its queue/object changes and resumes safely.
Epochs are deterministic test ticks, not an accepted retention duration.

The expiry index is `(scope,expires)`, matching both query predicates. An
`EXPLAIN (ANALYZE,BUFFERS)` fixture adds 20,000 expired roots in another scope,
5,000 future roots in the requested scope, and16 expired roots in that scope.
The measured index scan constrains both scope and epoch, returns eight candidates
with no filtering, and touches three index-scan buffers (11 including row locks).
The complete plan is preserved in choice-04 evidence. LIMIT 8 alone is not the proof.

## Measurement columns and practical limits

Heap, TOAST, index and WAL differences cover the entire named batch. Index bytes
include TOAST indexes. They are allocated relation growth with autovacuum off,
not retained bytes after candidate-specific compaction, backups or WAL archives.
The final database size includes both candidates and proof fixtures and must not
be described as candidate backup growth. Logical row mutations count measured
object/edge/candidate writes; proof helpers and every control query are not a
database-wide statement counter. `logical_payload_bytes` counts serialized edge
values and fetched UTF-8 JSON, excluding wire protocol overhead. `payload_queries`
counts instrumented payload/validation fetches, not every control/CAS statement.

At the time of this research, remaining production work was explicit: connect
complete item/exercise/metadata contracts and ACLs; integrate the real head
projections; implement admission and
durable preparation jobs with operational deadlines; enforce immutable writer
permissions and typed validation receipts; define retention/lease policy and
monitor GC backlog; add API failure, backup/restore and deployment evidence.
These are implementation obligations, not claims of a finished product service.
The [independent review](../verification/storage-review.md) confirms all five
findings closed at the bounded feasibility level. The later owner decision is recorded in
[Epic #74 dependency decisions](../../../epic-74-dependency-decisions.md#owner-answers):
the immutable block/page direction, normalized FK edges and physical fragmentation were
accepted after this evidence. Bounded deltas/checkpoints remain the measured alternative,
not the selected implementation.

The next reviewable slice proposed here was subsequently delivered in bounded stages:
[K1 storage kernel](../storage-kernel.md), [K2 native codec](../native-storage.md), and
[K3 counted pages and structural editing](../counted-pages.md). Those implementation
artifacts do not turn this research DDL into a migration or claim the unfinished
LearningItem/draft/Capture/editor/product flows.

## #171 acceptance completion mapping

- Raw configuration, hardware, row inventory, heap/TOAST/index/WAL deltas and current,
  historical and page-read latency are retained in this report and the linked raw/CSV files.
- Metadata-only revisions write no content/pages; single-node edits reuse immutable data;
  direct-root reads remain independent of revision-chain length.
- The 100k direct fork/fork-of-fork fixtures use bounded foreground writes and reads,
  separate deck namespaces and no eager per-item mapping.
- Real PostgreSQL fixtures cover atomic publication, CAS, same-command retry, disconnect
  rollback, staging visibility and bounded reachability/collection races.
- The historical proposed DDL, page/object limits, transaction/staging/GC boundaries,
  evidence qualifications and next kernel slice are explicit.
- Owner acceptance is explicit in the decision record above; it is not inferred from a
  merge or absent response.

This completes the bounded R74-S/#171 research gate. Product integration, operational
retention, backup/restore and deployment remain owned by later Epic #74/infrastructure
slices and are not retroactively claimed here.

Official sources: [PostgreSQL TOAST](https://www.postgresql.org/docs/18/storage-toast.html)
informs changed-value storage measurement;
[administrative functions](https://www.postgresql.org/docs/18/functions-admin.html)
define size/WAL counters;
[constraints](https://www.postgresql.org/docs/18/ddl-constraints.html) and
[locking](https://www.postgresql.org/docs/18/explicit-locking.html) inform composite
FK reference integrity and reference/delete races. Cross-row kind/count semantics
are explicitly application validation, not an invented cross-table CHECK guarantee.
