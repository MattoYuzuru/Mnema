# Deck-scoped Study contract v1

This directory is the canonical shared contract for Epic #75. The Learning
runtime context path is `/api`; every resource is private, owner-scoped and
`Cache-Control: private, no-store`. JSON examples are fixtures consumed by both
backend and frontend tests. UUIDs and decimal version strings follow the existing
Deck/Item contracts.

## Accepted P0 boundary

- A `MemoryObjective` is one assessable direction/answer contract inside one
  deck-local `LearningItem`. Forward and reverse are separate stable objectives.
- Every P0 `StudyPresentation` has exactly one assessed objective revision. Other
  shown bindings are `CUE`, `OPTION` or `CONTEXT`; they never receive exposure,
  evidence or progress.
- One stable objective may be evidenced by several exercise kinds. Editing its
  answer creates an immutable objective revision without resetting `StudyState`.
- Cross-item assessed objectives, group matching and aggregate multi-target credit
  are P1. The persisted binding model remains M:N so P1 needs no replacement schema.

## Authoring resources

| Operation | Request | Success |
|---|---|---|
| List exercises | `GET /api/decks/{deckId}/exercises?limit=20&cursor=...` | 200, page pinned to one Deck revision |
| Read exercise | `GET /api/decks/{deckId}/exercises/{exerciseId}[?revisionId=...]` | 200, exact immutable revision |
| Create exercise and objective | `POST /api/decks/{deckId}/exercises` + `If-Match` | 201, publication acknowledgement |
| Revise/re-enable exercise | `PUT /api/decks/{deckId}/exercises/{exerciseId}` + `If-Match` | 200, publication acknowledgement |

The write command is atomic. `objective.operation=create` allocates a stable
objective plus its first revision; `reuse` pins an existing exact revision;
`revise` creates the next objective revision while retaining stable identity and
Study state. The server generates stable/revision IDs. A command pins the expected
Deck revision and all referenced item revisions/node IDs, validates one `ASSESSED`
objective, advances the Deck exercise root and returns the new Deck ETag. Receipt,
Deck-head CAS, immutable rows and current projection commit together.

P0 types are `SELF_CHECK`, `TYPED`, `CLOZE_SINGLE` and `SINGLE_CHOICE`. Prompt,
answer and options are renderer-neutral specs made from stable node selections or
bounded custom text. The initial compact custom text boundary is 80 grapheme
clusters; full material remains available through Browse. `SINGLE_CHOICE` requires
one assessed focal binding and 2..6 distinct options from the same pinned snapshot.

## Session resources

| Operation | Request | Success |
|---|---|---|
| Start | `POST /api/decks/{deckId}/study-sessions` | 201 ACTIVE/EMPTY or 202 PREPARING |
| Read/resume | `GET /api/decks/{deckId}/study-sessions/{sessionId}` | 200 current bounded batch |
| Refill | `POST /api/decks/{deckId}/study-sessions/{sessionId}/presentations` | 200 next bounded batch |
| Submit | `POST /api/decks/{deckId}/study-sessions/{sessionId}/attempts` | 200 stored outcome |
| Restart items | `POST /api/decks/{deckId}/study-restarts` | 200 restart acknowledgement |
| Material progress | `GET /api/decks/{deckId}/study-progress?limit=...&cursor=...` | 200 explainable page |

The client requests only intent. Start accepts a server-enforced mode and bounded
budget; timezone/local study date are resolved from the authenticated account and
stored in the session. The response owns snapshot, exercise/objective revisions, roles, mode,
evaluator, nonce and selection-policy identity. Submit therefore contains no
client-selected Deck revision, binding roles, correct answer or scheduler flag.

`SCHEDULED` selects due objectives before not-yet-introduced objectives.
`REPLAY` requires a completed source session from the same account/deck/local day
and reuses its presentation revisions/order without old responses. `PRACTICE`
selects introduced objectives by default; `includeNew=true` is explicit. A batch
contains at most 20 presentations. A READY candidate generation is keyed by the
pinned exercise root; absent preparation returns `PREPARING`, never an unbounded
fallback scan. Retry/resume uses an opaque cursor and deterministic seed.

Session mode is immutable. `SCHEDULED` presentations may create current-epoch
exposure when issued and evidence when submitted. `REPLAY`/`PRACTICE` never write
canonical exposure, evidence, `StudyState`, due, streak or experiment outcome.

## Attempt and idempotency semantics

`attemptId` is a global client-generated UUID. Exact retry by the same owner,
session, presentation and canonical payload returns the stored outcome with
`Idempotency-Replayed: true`; changed reuse returns `IDEMPOTENCY_CONFLICT`. A
presentation has one terminal receipt, so a second `attemptId` for it also conflicts.

The safe command order is:

1. look up the owner-scoped receipt; exact retry returns its stored outcome before evaluation;
2. authorize current owner and read the immutable presentation;
3. perform deterministic evaluation without a StudyState lock;
4. in one transaction, acquire the receipt lock and recheck replay/conflict;
5. lock and terminalize the presentation;
6. for `SCHEDULED`, insert/lock the current objective state, verify learning epoch,
   reduce and write evidence/state/raw/receipt atomically;
7. for `REPLAY`/`PRACTICE`, write only a short-lived compact receipt.

This preserves the repository's receipt-first command pattern. It avoids locking
or creating Study state for a duplicate or non-scheduled attempt. Concurrent
online attempts for one objective serialize on its state row and receive a
monotonic transition sequence in server commit order. Client time never orders
transitions. A late presentation from an old learning epoch returns a durable
`NOT_ASSESSED` outcome and does not mutate the new epoch.

Responses use these shapes:

- `TEXT` for `TYPED`/`CLOZE_SINGLE`;
- `SELF_CHECK` with `NOT_RECALLED`, `HINTED`, `PARTIAL` or `FULL`;
- `CHOICE` with a server-issued option ID;
- `CANCEL`, which terminalizes as `NOT_ASSESSED` without a transition.

`confidence` is optional calibration metadata and has no reducer effect in v1.
`durationMs` is bounded diagnostic metadata and never changes correctness/evidence.
Self-check is always `LOW`; deterministic unhinted production is `HIGH`; a valid
hint caps positive typed/cloze evidence at `MEDIUM`; single choice is always `LOW`.
Deterministic incorrect production can be `HIGH`: result describes direction,
while evidence class describes reliability of the observation.

## `mnema-baseline-v1`

The initial reducer is a deliberately transparent calibration baseline, not a
claim of optimal memory science and not an SM2/FSRS port. It is the pure function:

```text
prior state + normalized evidence + acceptedAt + immutable config -> next state
```

State contains `learningEpoch`, `level` (0..7), `correctStreak`, `lapseCount`,
`lastAssessedAt`, `nextDue`, reducer/config identity and row/transition versions.
Intervals by resulting level are `PT10M`, `PT4H`, `P1D`, `P3D`, `P7D`, `P14D`,
`P30D`, `P60D`.

| Result | HIGH | MEDIUM | LOW |
|---|---|---|---|
| `CORRECT` | `level + 2`, ceiling 7 | `level + 1`, promotion ceiling 6 | `level + 1`, promotion ceiling 3 |
| `PARTIAL` | `level - 1` | `level - 1` | `level - 1` |
| `UNSURE` | `level - 1` | `level - 1` | `level - 1` |
| `INCORRECT` | set 0 | `level - 2` | `level - 1` |

Correct evidence never demotes an objective already above its promotion ceiling:
`after = max(before, min(before + levels, ceiling))`. All lower bounds are zero.
`CORRECT` increments `correctStreak`; every other
assessed result resets it. `INCORRECT` and `UNSURE` increment `lapseCount`;
`PARTIAL` does not. `nextDue = acceptedAt + interval(resulting level)`.
`NONE`, `NOT_ASSESSED` and `UNAVAILABLE` are not reducer inputs and create no
state transition. Server UTC time, truncated to microseconds, is persisted as
`acceptedAt`; tests use a fixed clock. Algorithm, version, config ID/hash and
before/after state are stored on every transition. `configHash` is SHA-256 over
RFC 8785-style canonical JSON containing exactly `configId`, `intervals`,
`reducerId`, `reducerVersion` and `transitions`; golden cases are excluded.

Restart locks selected objectives in UUID order, increments each learning epoch,
sets level/streak/lapses to zero, clears last assessment and makes the new epoch
due immediately. Prior attempts/transitions remain. Exact command retry returns
the original result; old-epoch presentations cannot affect the new state.

## Progress and retention

Material progress is a projection, not a mastery percentage:

- `NOT_STARTED`: no current-epoch assessed state;
- `LEARNING`: introduced/current state at level 0..1;
- `DUE`: any enabled objective is due at the read clock;
- `ON_TRACK`: all introduced enabled objectives are future-due and level >=2.

The response also exposes objective coverage, last assessed time and nearest due.
Unknown objectives are not rendered as 0% or 100%.

Attempt receipt, normalized evidence, before/after transition and version identity
are retained until account deletion. A bounded child row stores scheduled raw JSON
until exactly `submittedAt + 30 days`; purge deletes only that child row. Exact retry
after purge still returns the stored outcome without re-evaluation. Replay/practice
never persist raw response. Their compact receipt expires after 24 hours but leaves
an attempt-ID tombstone so an expired global ID cannot be reused.

Live-row deletion does not claim deletion from backup/PITR/WAL. Production backup
retention remains a separate legal/operations decision.

## Errors and security

Existing Problem Details remain canonical. Study adds stable error codes
`SESSION_PREPARING`, `SESSION_EXPIRED` and `PRESENTATION_EXPIRED`; private
absent/foreign IDs remain the same opaque 404. A recoverable evaluator failure is
HTTP 200 `UNAVAILABLE` feedback with reason code `EVALUATOR_UNAVAILABLE`, not an
incorrect result or transport Problem Detail.
No log, metric, trace or Problem Detail includes raw response, expected answer,
private content or receipt payload.

Exact examples: [authoring.json](authoring.json), [session.json](session.json),
[attempts.json](attempts.json), [progress.json](progress.json),
[restart.json](restart.json), [flows.json](flows.json),
[reducer-v1.json](reducer-v1.json) and
[adversarial.json](adversarial.json). Each fixture validates against the matching
definition in [study.schema.json](study.schema.json); public wire DTOs reject unknown
fields at every described level. Adversarial request/effect payloads are descriptive
test plans rather than wire DTOs, so their inner keys intentionally vary by case.
