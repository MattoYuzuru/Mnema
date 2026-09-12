# Private Deck metadata API — #188 evidence

Implemented locally; delivery/exact full repository gate still pending. Contract:
[shared Deck fixtures](../../../../contracts/decks/README.md). No frontend authoring,
item/draft/capture/fork endpoint, dependency or deployment is included.

## Behavior and integrity

Canonical Learning POST/GET `/api/decks`, GET/PATCH `/api/decks/{deckId}` use current
Identity authentication plus SQL owner predicates. Foreign and absent Deck IDs share404.
Strict bounded stream/UTF-8/JSON/UUID/metadata/version/cursor boundaries reject input
without echoing private values. All private responses/errors are no-store.

Command receipts bind authenticated actor, type, target, expected version and metadata.
Creation allocates IDs inside the deduplicated action. Current ACL precedes replay.
An exact retry returns its original acknowledgement, not a new publication. Fresh
writes return ETag; replay omits it and signals Idempotency-Replayed, requiring GET
before subsequent editing. This follows [RFC9110](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.1)
and conditional partial updates from [RFC5789](https://www.rfc-editor.org/rfc/rfc5789.html).

V3 introduces Deck and immutable DeckRevision. Composite references bind exact
deck/scope/owner, parent/sequence, head/version and revision-owned durable root pins.
The CAS head update happens before new revision allocation under a deferred head FK;
competing writes cannot collide on revision sequence before returning412. Revision,
pins, head and acknowledgement commit or roll back together. Metadata save stages
the existing two roots without objects, retains two new durable revision pins and
releases both temporary pins in the same transaction. Old history/pins remain.
Index `(owner_id, created_at DESC, deck_id DESC)` matches bounded limit+1 keyset reads.
Cursor preserves microseconds; metadata changes never reorder the creation key.

POST creates a fresh scope. Scope remains a physical lineage, not a unique Deck:
multiple independently authorized Deck namespaces may retain the same physical
roots. The only schema-level unique scope assumption was found during lead review,
independently confirmed as P2 against the canonical future-fork seam, and removed.
The new PostgreSQL regression first failed23505 on the old constraint; it checks
independent heads/access and exactly two stored root objects with separate pins.
This is a synthetic authorized sharing fixture, not implemented fork authorization/UI.

## Executed checks

Java21, existing SpringBoot3.5.16/JDBC/Jackson, fail-closed disposable PostgreSQL18,
Python stdlib and loopback HTTP; no secrets or shared server access.

- Initial Deck MVC4, strict boundary5, service PostgreSQL7 and constraint PostgreSQL17
  cases PASS. Independent reviewer read all sources/DDL/tests/fixtures and reran all33
  cases: PASS/0 failures/errors/skips,14s. No blocker besides the subsequent scope
  finding remained; its delta review follows the corrected candidate.
- Full Learning before Native-main integration:170 PASS/0 failures/errors/skips,25s;
  lines978/998 (98.00%), Deck199/199. This is not the exact delivery gate.
- After actual main `b6c523b` integration and the lineage correction: full Learning
  **241 PASS**, zero failures/errors/skips. Independent delta review reran the18
  constraint and7 service PostgreSQL cases: **25/25 PASS**, zero skips,12s. The
  reviewer confirmed unchanged Deck production hashes and closed the scope P2;
  no confirmed blocker remains in this API slice.
- Full-suite integration exposed old K1 fixture cleanup missing the new Deck→pin FK.
  Explicit disposable-table reset now includes Deck/DeckRevision; no CASCADE or
  production trigger weakening. The failed24 cases were setup failures; full rerun
  above passed. No database contents outside disposable tests were deleted.
- `python3 scripts/learning-security/run.py --requests 8 --duration-seconds 1`:
  PASS24 real-process scenarios/25.21s; cleanup complete, no private files retained.
  Actual PKCE grants, create/read/list/save, exact stale receipt, two HTTP writers
  (200/412), foreign IDs/replay404, missing version428, changed-command409,
  read/write scope403, revocation401 and Identity unavailable503. Both real JARs
  and harness hashes are emitted by the fixture. A first startup attempt correctly
  failed for a missing Identity JAR; after building both, the scenario passed.

The local evidence commands (from backend unless stated) are:

```sh
./gradlew :services:learning:test :services:learning:bootJar :services:identity-account:bootJar --console=plain
./gradlew :services:learning:test --tests '*catalog.deck.*' --console=plain --rerun-tasks
# Repository root, with Java21 on PATH:
python3 scripts/learning-security/run.py --requests 8 --duration-seconds 1
```

## Boundaries and delivery

The full exact candidate repository gate must still run backend quality/coverage,
frontend npm ci/lint/tests/build and all33 policy/security/recovery/purge steps,
including the **default** HTTP and real cancellation envelopes. Filtered developer
tests do not replace those requirements. No dedicated backend lint task is configured.
Main integration includes Native validation; the full Learning total is now241.

No browser TLS/Secure-cookie/visual/a11y/IME evidence or full authoring flow is claimed.
The index is verified structurally, not a representative-load query-plan benchmark;
slow-upload and lock-wait wall-clock deadlines remain infrastructure verification,
not claims derived from a transaction timeout. Future membership codec validation,
retention/deletion/source provenance and fork commands are separate owning slices.
Rollback is a protected code revert before adoption; do not reverse applied migrations
or delete history implicitly. #185 disables hosted operations; #147 remains separate.
