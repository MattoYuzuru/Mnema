---
artifact:
  id: revision-storage-and-runtime-boundaries
  type: architecture
  title: "Revision storage, fork locality and independently scalable workloads"
  status: proposed
  created_at: "2026-09-06"
  updated_at: "2026-09-06"
  owners: ["project-owner"]
  decision_scope: [revision-storage, fork-lineage, publication, runtime-boundaries, bounded-reads]
---

# Revision storage and runtime boundaries

The owner has accepted the product requirements below. The physical representation,
limits and index designs are engineering proposals for #74/#75, not implemented or
load-tested guarantees. [Content platform](./content-platform-v2.md) owns the domain
overview; [product direction](../product/product-direction-v2.md) owns launch scope.

## Accepted requirements

An `ItemRevision` and a `DeckRevision` are immutable logical snapshots. Saving a
description must not copy every item; editing one paragraph must normally reuse
unchanged material and media. The API may return a complete document assembled
from shared storage. A complete response does not imply a complete historical copy.
Storage still grows for changed content, index pages, audit records and backups.

Personal decks are the launch. Sharing, catalog discovery and editable forks come
later. A fork must be cheap to create and retain independent edits, exercises and
progress. Manual selective upstream updates are the recommended initial future UX,
not a confirmed choice over automatic tracking; three-way
conflict resolution and proposing improvements upstream remain desired later
capabilities. This supersedes the earlier permanent prohibition on upstream changes.

Each item belongs logically to one deck. Physical reuse in a fork never introduces
a user-visible item link between decks or a shared scheduler state. A learning
session resolves exactly one personal deck and an immutable effective snapshot.

## What Git and PostgreSQL actually guarantee

Git models commits as references to trees and blobs; unchanged objects are reused.
A changed file initially becomes a new blob. Packfiles can subsequently encode
objects as deltas. Thus “Git-like versions” is a sound semantic analogy, but “Git
always writes only the edited characters” is not its contract. Mnema adopts logical
snapshots, stable ancestry and physical sharing without putting ACL, search or
study transactions inside a Git repository. [Git objects](https://git-scm.com/book/en/v2/Git-Internals-Git-Objects),
[Git packfiles](https://git-scm.com/book/en/v2/Git-Internals-Packfiles).

PostgreSQL TOAST compresses and stores large values out of line. It can preserve an
unchanged external column during an update; it does not promise application-level
delta encoding between newly inserted JSONB revision rows. Therefore neither
JSONB nor a small `jsonb_set` operation is evidence that historical copies are
cheap. Measure heap, TOAST, indexes, WAL and backup growth, including vacuum work.
[PostgreSQL TOAST](https://www.postgresql.org/docs/current/storage-toast.html).

## Physical candidates and proposed choice

| Candidate | Storage and read behavior | Engineering tradeoff |
|---|---|---|
| Full document and full membership per revision | Simple direct reads; repeats unchanged payload and memberships | Reject as the general history format; valid for deliberately bounded caches |
| Stable-node deltas, materialized current head, periodic checkpoints | Small usual edits; historical read applies at most a configured number of patches | Good simpler alternative for small items; checkpoint work and forks need explicit budgets, no unbounded replay |
| Immutable content blocks and persistent paged manifests | Reuses unchanged blocks/pages; a revision selects roots, read cost depends on document/page size rather than history length | Proposed target: one bounded storage abstraction, with SQL projections for query-intensive paths |
| Literal Git/JGit primary store | Mature version objects and merge primitives | Additional ACL/query/transaction/GC integration; not proposed |

Recommend immutable JSONB **blocks at semantic block granularity**, plus a persistent
paged manifest for deck membership and exercise policy. Do not split every
character into an object or build a general distributed filesystem. A short item
can be one block. Changing that short block copies the block; this bounded write
amplification is intentional. Large document blocks must be split at supported
node boundaries, with a byte limit even when one user paragraph is enormous.
Unchanged image/audio/video assets remain references.

Proposed internals:

- `content_block(block_id, reuse_scope_id, format_version, payload, checksum)` is
  immutable. It contains bounded native nodes with stable node IDs; its internal
  checksum is not an entity ID or an authorization token.
- `content_manifest` is a bounded-fanout tree of immutable pages selecting ordered
  blocks. `ItemRevision.content_root_id` identifies the document without a chain
  of earlier item revisions. Replacing a block writes the block and ancestor pages.
- `deck_manifest` selects stable member keys, content revisions, rank, tags and
  item lifecycle; a companion immutable root selects exercises, bindings and
  eligible pool members. Pages store subtree counts needed for bounded ordinal
  lookup. Reordering updates index paths; mass rebalance uses a job.
- `DeckRevision` selects metadata, membership and exercise roots, the parent
  revision, actor and command. Metadata-only saves reuse both roots. Change records
  record the semantic diff for audit/pull; they are not required to replay ordinary
  reads. Small bounded metadata may be stored as a full JSONB snapshot.
- Current search/due/eligibility projections remain relational and rebuildable.
  Only changed entries are updated for ordinary saves. A future fork initially
  reads shared revision projections through its namespace; it cannot require
  eager per-item rows or a fork-wide search-index build before its first page.

With `N` memberships, manifest fanout `F`, `K` changed entries and `B` changed block
bytes, a normal mutation costs approximately `B + O(K log_F N)` bounded page
writes, plus revision/index overhead. Metadata-only save and fork creation each
write a constant number of logical records. A page of `P` members costs
`O(log_F N + P)` traversal/transfer. Fetching a whole item still costs its document
size. These are algorithmic bounds, not latency or physical disk-byte promises.

The LLD must prove page split/merge, ordering, node identity and projection parity
with property tests before adopting this representation. If implementation cost
proves excessive, the alternative is bounded delta history: a maximum of 32 item
patches after a checkpoint, byte-budget-triggered checkpoints and immutable
materializations prepared by jobs for large deck snapshots. Such a fallback needs
an explicit revised decision and must preserve cheap fork creation and bounded
historical reads; “add checkpoints when slow” is not an acceptable contract.

## Logical locality and physical sharing

The proposed local identity is `(deck_id, member_key)`. `member_key` is stable inside
its deck; a fork may reuse its source keys in a new deck namespace. The compound
identity differs without inserting `N` mapping rows at fork time. Public references
carry `deckId` plus an item key (or a reversibly scoped opaque equivalent), never
just an inherited unscoped item ID. New items receive fresh keys.

Immutable content revision objects can be shared physically and are authorized
only through the requested deck revision's manifest. They are not themselves
logical learning items. Exercise and objective keys are likewise resolved in the
deck namespace, allowing a fork to reuse an immutable exercise root without
rewriting every binding. Progress keys include the account and personal deck;
equal content does not merge learning histories.

Store a fork's exact source/base revision and namespace mapping rule. Ordinary
inherited keys map by identity; sparse explicit provenance entries represent
later remappings/imports. The fork root points directly to immutable pages, not
recursively through all earlier forks. Subsequent private edits copy changed
blocks/pages and advance only the private head. “Clone” in product copy means
this editable fork; export is a separate streamed interchange operation and does
not imply an unnecessary database copy.

Deduplication is allowed within a trusted lineage/reuse scope and follows access
checks. No endpoint lets someone test whether another account has the same private
hash. A fork retains authorized references after source removal according to the
published retention contract. Global cross-tenant deduplication is not required.

## Future pull and contribution semantics

A pure update plan compares the last resolved upstream base, the proposed upstream
revision and the private head. Stable node/member/exercise identities drive the
diff. Whole unmodified units can update automatically **inside the user's approved
pull**. Disjoint edits may merge only when structure, exercise answers and bindings
remain valid. Deletion versus private edit, competing answers and ambiguous node
changes remain conflicts; keep the private version until the user chooses.

The plan records metadata, item and exercise changes separately, their dependency
closure, chosen updates and kept/conflicting units. Persist per-unit last-resolved
upstream revisions where choices differ; one global “last pulled revision” cannot
represent a selective pull. Reject a plan if either compared head changed. Apply
accepted changes atomically as a new private revision; no hidden background
advance, repeated duplication or silent overwrite is allowed.

Later manual conflict resolution may select own/upstream content or a validated
combination within a material. Contribution proposals target an exact upstream
base and contain explicit selected changes; private notes are excluded unless
explicitly selected. Future merge records need ordered parent/provenance edges
in addition to the primary same-deck parent. No contribution endpoint or editor is
part of personal-deck launch.

## Publication, budgets and concurrency

Proposed initial engineering limits are tunable configuration, not paid-plan
entitlements: 100 members per response page, 20 exercise targets per attempt,
100 changed memberships and 1 MiB command JSON per synchronous publication.
Document limits are owned by the content-format contract. Requests above the
synchronous publication envelope become durable jobs (or return a documented
size error where no job API exists). No bulk path loops over all content inside
one HTTP request or transaction.

Build and validate immutable blocks/pages in bounded staging batches. Staging is
not visible content. The short final transaction deduplicates the command,
compares the expected head/row version, verifies ownership and the prepared
validation receipt, inserts the revision/change summary, updates the bounded
head projection or switches its fully prepared generation, and advances the head.
It stores the command result in the same transaction. A stale head returns a
conflict; a failed final commit exposes no partial revision. Staged orphan blocks
are reclaimable after a grace period. Provider or object-upload calls never run
while a deck/state lock is held.

PostgreSQL's default isolation does not by itself express the application's
expected-head precondition. Use conditional updates/row locking; retry serialization
failures as complete idempotent transactions when stronger isolation is selected.
Do not use `latestVersion + 1` as a concurrency strategy.
[PostgreSQL transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html).

A publication job has actor, command/payload identity, expected source head,
progress, lease expiry, cancellation and terminal result. Attempts after a worker
crash reuse staged work safely. Proposed worker baseline: separate bounded queues
per resource class and per-account concurrency caps, with explicit queue age and
rejection behavior. #74 LLD must select deadlines from load evidence rather than
claiming a guessed time as a production SLO.

## Session reads and large decks

Pin an effective personal snapshot: saved deck revision, selected exercise roots,
personal content choices and the relevant runtime versions. A source revision
alone is insufficient after a selective pull or private edits. Unsaved drafts and
unfinished quick notes cannot enter selection. Changes after start apply to future
sessions; a running attempt retains exactly the content that was shown.

The scheduler's normal due-session path uses indexed due/eligible state for this
deck. Replay and whole-deck practice have an explicit server-side mode that
forbids scheduler/state/experiment-exposure writes; a client boolean is not an
authorization boundary. Replay references the bounded persisted sequence of the
completed session and its revisions. Content availability rules can expire replay
when its retention pin expires; the product must show that explicitly.

Whole-deck practice uses a seeded, versioned permutation of eligible snapshot
ordinals and a cursor over manifest pages, fetching only the next batch. A later
weak-first policy uses pre-indexed buckets whose order and membership are pinned;
it does not sort every item per click. Candidate pools include only explicitly
enabled compatible bindings, and the full-deck practice pool excludes unfinished
and, under the initial product rule, never-introduced material. If a filtered pool
has no ready ordinal index, build it as a bounded job and expose preparing status.
Do not hide an `O(N)` scan behind “seeded randomization,” `ORDER BY random()`, a full
JSON snapshot response or an unbounded cursor filter. Replay time is always
proportional to the number of exercises actually consumed.

## Retention and garbage collection

Reachability roots include live deck heads, retained history, fork/pull bases,
saved attempts/replay pins, durable drafts, exports/offline retention leases and
moderation holds. Quick notes are durable user content until explicit deletion;
cache TTL must never delete them. A client offline lease has a documented expiry,
renewal and conflict outcome so offline use cannot pin all history forever.

Purge is a bounded background mark/sweep or verified reference-count process with
a grace period and concurrency protection for new roots. A process crash cannot
delete a reachable shared block. Delete a root/reference before reclaiming only
unreachable objects; source-deck deletion cannot cascade into fork content.
Backups, restore and GC must cover roots and blocks consistently. Media deletion,
legal removal and audit retention require their own explicit policy; “immutable”
does not mean “retain every byte forever.” No current history-retention number is
silently introduced here.

## Runtime decision and evidence

At this revision, Identity & Account source is consolidated and the separate
[Learning API runtime foundation](../../backend/services/learning/guide.md) exists:
fresh schema, error contracts, CAS and idempotent commands. Content, library and
study domains have not yet been implemented in it. Legacy core/media/import/AI
source remains replacement input. This is a selected target topology and a
runtime shell, not a completed product-service consolidation or deployment claim.

The owner's scaling intuition is correct: a saturated importer should receive
more worker capacity without multiplying unrelated API work. A modular Learning
API preserves that option. Request-serving content/library/study share coherent
transaction boundaries; media transcoding, large imports, AI provider calls and
bulk revision preparation run in independently deployed workers with their own
queues, CPU/memory limits and concurrency. Workers submit idempotent domain
commands; they do not mutate another module's tables.

Real examples support the distinction, not Mnema-specific capacity claims.
Shopify describes organizing a large monolith into explicit components to control
coupling. GitLab documents separate Web/API and Sidekiq capacity and cautions that
both ultimately consume database resources. The inference for Mnema is to keep
domain boundaries explicit while scaling request and worker processes separately;
neither example proves that Mnema needs their exact stack or topology.
[Shopify modular monolith](https://shopify.engineering/deconstructing-monolith-designing-software-maximizes-developer-productivity),
[GitLab scalability](https://docs.gitlab.com/development/scalability/),
[GitLab Sidekiq processes](https://docs.gitlab.com/administration/sidekiq/extra_sidekiq_processes/).

Unknown traffic alone does not identify good service boundaries. Splitting all
modules introduces network retries, partial failures and cross-service consistency
before measuring the dominant workload. Extract a module when independent scaling,
fault isolation, trust boundaries or ownership justify that cost. Reserve queue
and command boundaries for predictable heavy work from its first implementation.

Stateless API replicas reduce request-process bottlenecks but do not eliminate
database write/connection limits, hot deck locks, storage bandwidth, provider
quotas or correlated infrastructure failures. Bound database pools across all
replicas and workers; use admission control and backpressure before saturation.
CDN caches immutable media, not private authorization decisions. Cache keys include
the effective revision; cache failure must have a bounded origin fallback.
Replication/failover, tested backup restore and later partitioning/sharding address
different failure/capacity boundaries. Horizontal scaling alone does not promise
unlimited exponential growth or remove every single point of failure.

## Evidence required before implementation acceptance

Verify metadata-only saves write no item revisions; tiny edits reuse unchanged
blocks; 1,000 successive edits have a history-independent read bound; a fork of
100,000 items performs bounded synchronous writes and returns a bounded first
page; fork-of-fork reads have no lineage-depth penalty. Test independent progress,
source deletion, selective-pull retries, stale heads, worker crash before final
commit, reachable-object GC races and zero scheduler writes in practice modes.

Measure rows/bytes/WAL per mutation and latency under concurrent publish/review,
including a hot deck, failed cache and a saturated bulk queue. These are required
future validation scenarios, not tests performed by this documentation change.
No production capacity claim or storage-size estimate substitutes for those tests.
