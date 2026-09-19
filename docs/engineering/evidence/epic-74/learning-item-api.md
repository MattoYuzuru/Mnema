# Deck-local LearningItem API — #200 evidence

Candidate implementation only; protected delivery and the exact repository-wide
gate are owned by the integrating lead. Contract: `contracts/items/README.md`.

V4 adds Deck-local logical ownership, immutable item revisions, a rebuildable
ordered current projection and immutable per-Deck-revision change records. A
rank-9 item descriptor binds the member/revision identity to one native rank-8
content root. The counted member page points to that descriptor, so the existing
Deck revision durable member-root pin protects the complete published graph.
Descriptor shape and edge target are checked by a PostgreSQL trigger.

Canonical `/decks/{deckId}/items` routes expose bounded current pages, exact current
or historical reads, single create/save and an atomic at-most-100-change publication
for create/save/delete/reorder. Deck ETag plus exact Deck/item revision identifiers
provide CAS. Current ACL runs before receipt replay. Exact retry returns the original
acknowledgement; changed command reuse is rejected. List cursors bind one Deck head.

Only validated native-v1 documents cross the boundary. Same-topology saves reuse
unchanged objects; structural changes require the K3 explicit edit intent. Reads
decode a bounded immutable graph directly rather than replaying history. The
request and native boundaries independently reject duplicate keys, malformed UTF-8,
unsupported numbers, excessive depth/tokens/nodes/scalars and commands over 1 MiB.

PostgreSQL integration coverage exercises owner create/list/read/save/reload,
historical round-trip of the multilingual/opaque golden document, metadata-only
root reuse, exact retry, changed replay, stale head, competing writers, foreign IDs,
bulk reorder/delete, durable reachability and an outer rollback that removes head,
projection, revisions, durable pins and receipt while independently committed staging
remains retryable. MVC tests cover
wire headers, 428/412-safe preconditions, strict input and stable opaque errors.

Encoding and K1/K2/K3 staging run in bounded committed batches before the short
publication transaction. The final transaction rechecks the exact prepared Deck head,
then commits receipt, head CAS, revision/change/projection rows and durable root pins
atomically. A known replay or CAS loss releases its preparations in a separate cleanup
transaction; process loss leaves only leased, invisible staging for bounded expiry, and
a retry safely reuses equal immutable objects with fresh pins. No latency/SLO, durable
job cursor or large-job resume guarantee is claimed. Imports, public catalog, forks,
exercise behavior, media upload/serving, drafts/capture, scheduler and deployment remain
outside #200.

## Local verification — 2026-09-19

- `./gradlew :services:learning:test --console=plain`: 288 tests, zero failures,
  errors or skips; PostgreSQL item coverage includes create/save/history, bulk
  reorder/delete, competing writers, rollback and database invariants.
- `./gradlew quality --console=plain`: `BUILD SUCCESSFUL`; Learning line coverage
  96.38% against the 90% baseline (all other backend module baselines also pass).
- `python3 -m py_compile scripts/learning-security/run.py` and
  `python3 -m unittest discover -s scripts/learning-security/tests -v`: 14 tests
  pass, with the two separately invoked real-service cancellation cases skipped by
  their explicit opt-in flag.
- `python3 scripts/learning-security/run.py --requests 8 --duration-seconds 1`:
  25 scenarios pass in 16.37 seconds; LearningItem create/browse/save/reload,
  historical read, exact retry, changed retry, stale and foreign ACL cases pass;
  cleanup completes with no retained private files.
- `python3 scripts/learning-security/verify_cancellation.py`: SIGINT and SIGTERM
  mid-paced real-service cases pass; two processes, the owned PostgreSQL container
  and private directory are removed in 630.46 ms and 570.82 ms respectively.

The verified artifacts are Learning
`f771424db4ca0a0dc22c544dc0c1ec6bbbf35149de0c43df2a169169e313e3f4`, Identity
`5dde50005f2db8af7de128fd50b019122412123a34d5bca5ab575753871592a8`, harness
`4ca6f24732e367a7d574b4c8c20f7281ab39631d9a946d25751db5531aefab2e` and
cancellation verifier
`92a2a952148516e8073a26f6efdf40a1142c1f71279da19ccce95ca46dbd7ae3`.

Security/transaction review used Spring Security's official
[HTTP authorization guidance](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)
to confirm that GET/HEAD inherit `learning.read` while every publication inherits
`learning.write`, and PostgreSQL's official
[locking documentation](https://www.postgresql.org/docs/current/explicit-locking.html)
plus deferrable constraints to retain explicit CAS and commit-time exact graph
relationships.
