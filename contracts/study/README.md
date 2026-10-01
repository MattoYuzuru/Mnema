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
- One stable objective may be evidenced by several exercises. An objective is stable identity
  plus a short author title; it never stores option, blank or pair IDs. Exercise-specific
  answer keys belong to the immutable exercise revision.
- Cross-item assessed objectives and aggregate multi-target credit remain out of scope. A
  composite exercise (several blanks, pairs or options, or material from other items shown as
  context) assesses exactly one objective of its `subject` item and returns per-part feedback;
  context materials never receive exposure, evidence or progress.

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
Deck revision, the subject item revision and every referenced material node/media asset,
validates exactly one assessed objective, advances the Deck exercise root and returns the new Deck ETag. Receipt,
Deck-head CAS, immutable rows and current projection commit together.

### Exercise mechanics (#266)

Seven canonical mechanics separate what the learner sees (**content**), what they do
(**interaction**), how the exercise is checked (**evaluator policy + answer key**) and what is
assessed (**objective**). Media type is content, never a mechanic: there is no audio-, video- or
listening-specific exercise type.

| Mechanic | Interaction | Answer key (`answerKey.kind`) | Evaluator |
|---|---|---|---|
| `SELF_CHECK` | explicit reveal of the reference, then behavioral self-rating | `SELF_REPORT` | `self-check` |
| `FREE_RESPONSE` | one text answer, whatever the prompt contains | `TEXT`: any one accepted alternative, explicit normalization, `STRICT`/`SOFT` | `deterministic-text` (`ai-semantic` reserved, §AI) |
| `CLOZE` | one text input per blank in an authored passage | `CLOZE`: accepted answers per `blankId` | `deterministic-cloze` |
| `CHOICE` | `SINGLE` or `MULTIPLE` selection of authored options | `CHOICE`: exact set of `correctOptionIds` | `deterministic-choice` |
| `MATCH` | one-to-one pairing of independently shuffled left/right items | `MATCH`: bijection `pairs[{leftId,rightId}]` | `deterministic-match` |
| `ORDER` | restore the authored sequence of shuffled items | `ORDER`: full `sequence` of item IDs | `deterministic-order` |
| `CATEGORIZE` | assign every item to exactly one of the authored groups | `CATEGORIZE`: `assignments[{itemId,categoryId}]` (many-to-one) | `deterministic-categorize` |

Publication command: `{commandId, expectedDeckRevisionId, [expectedExerciseRevisionId], objective,
exercise}` with `exercise = {type, schemaVersion: 2, enabled, subject:{memberKey,itemRevisionId},
content, answerKey, evaluatorPolicy}`. `schemaVersion` versions the document shape; it is not a
mechanic selector. Objective operations are `create {title}`, `reuse {objectiveId,
objectiveRevisionId}` and `revise {objectiveId, expectedObjectiveRevisionId, title}`; the objective
belongs to `subject.memberKey`. The server derives binding rows (the assessed subject and one
context binding per referenced material revision); clients never submit binding roles.
Type, content shape, `answerKey.kind` and evaluator must agree; every DTO rejects unknown fields.

**Content blocks.** `TEXT {text}` keeps newlines verbatim and is never HTML. `MATERIAL
{memberKey,itemRevisionId,nodeId}` pins a text-bearing node of a LearningItem revision in the same
Deck and is resolved to text when a presentation is issued, so a later material edit cannot change
an already published exercise. `IMAGE {assetId,alt}`, `AUDIO|VIDEO {assetId,title,[transcript]}`
and `YOUTUBE {videoId,title}` reuse the native media/YouTube rules. Media `title` is an author
label and is never sent to learners. New exercise attachments are owner-scoped logical assets; they
do not modify the source LearningItem.

| Slot profile | Used by | Kinds | Blocks | Text bound (UTF-16 units) |
|---|---|---|---|---|
| PROMPT | `content.prompt` | all six | 1..8 (`CLOZE`, `MATCH`: 0..8) | 4000 per block |
| REFERENCE | `SELF_CHECK` (1..8), `FREE_RESPONSE` (0..8) `content.reference` | all six | see left | 4000 per block |
| COMPACT | `CHOICE` options, `MATCH` left/right items | `TEXT`, `MATERIAL`, `IMAGE`, `AUDIO`, `VIDEO` | 1..2: ≤1 text-like and ≤1 media | 300 |
| Passage | `CLOZE content.passage` | `TEXT` segments and `BLANK` | 2..64 segments, 1..12 blanks | 4000 total |
| SEQUENCE | `ORDER` items | `TEXT`, `MATERIAL`, `IMAGE`, `AUDIO`, `VIDEO` | 1..2: ≤1 text-like and ≤1 media | 1000 (newlines kept, e.g. code) |

At most 32 media blocks per exercise. Text is never truncated; limits are validation errors.

**Mechanic rules.** `CHOICE` has 2..12 options with stable `optionId`s in authored order;
`SINGLE` requires exactly one correct ID and one selected ID, `MULTIPLE` grades the exact selected
set against one or more correct IDs. `CLOZE` blanks are explicit passage segments with a stable
`blankId`, `size` (`FIXED` 5..20 or `ANSWER_LENGTH`) and a per-blank `firstLetterHint` flag; the key
covers exactly the passage blank IDs, so repeated words never match by position. `MATCH` has 2..6
items per side, globally unique item IDs and an exact bijection key; any COMPACT combination
(text, recorded audio, image, short video or material on either side) is valid.

**ORDER (#268).** Content `{prompt (0..8), items: [{itemId, blocks}] 2..12}` (SEQUENCE profile); answer key
`{kind:"ORDER", sequence}` is an exact permutation of the item IDs, explicitly authored — order is never inferred.
Items whose blocks are identical (same canonical JSON) are interchangeable: swapping indistinguishable copies is not an
error; nothing else is treated as equivalent. Issue shuffles items with the secure source and persists the order;
for 3+ items a shuffle that already shows the correct (equivalence-aware) sequence is redrawn, for 2 items the
permutation is uniform so the layout never reveals the answer. Response `ORDER {sequence}` must be an exact
permutation of the issued IDs. Result is binary: `CORRECT` for the right sequence, otherwise `INCORRECT` — a
positional score is not a measure of knowing a process. Feedback lists `correctSequence` and per-`position`
correctness. Evidence `MEDIUM` (`SEQUENCING`, `DETERMINISTIC`).

**CATEGORIZE (#268).** Content `{prompt (0..8), categories: [{categoryId, label}] 2..6, items: [{itemId, blocks}]
2..12}`; labels are nonblank plain text ≤ 80 UTF-16 units, unique after trim + case fold; items use the COMPACT
profile. Key `{kind:"CATEGORIZE", assignments}` assigns every item to exactly one existing category; a category may
stay empty (a distractor). Labels and category order are display only; IDs are canonical. Issue shuffles items and
keeps authored category order. Response `CATEGORIZE {assignments}` assigns every issued item exactly once to an
issued category. Result: all correct `CORRECT`, some `PARTIAL`, none `INCORRECT`; feedback per item
(`selectedCategoryId`, `correctCategoryId`, `correct`). There are no intermediate checks, so the single submission
is the whole record. Evidence `LOW` (`CATEGORIZING`, `DETERMINISTIC`, `RECOGNITION`).

Both mechanics assess one composite objective of the subject item; no per-element credit is created.

**Media lifecycle.** Every IMAGE/AUDIO/VIDEO block in every slot is pinned in
`exercise_media_ref` with its declared media kind inside the publication transaction, after owner
validation. A candidate is issued, a pair is checked and an attempt is assessed only while every
pinned asset is READY with a verified source of the declared kind; otherwise submission returns
`NOT_ASSESSED` + `MEDIA_NOT_READY` without evidence or transition.

**Fresh schema.** Migration `V21` replaces the earlier mechanic enum and stores answer keys on
exercise revisions. It refuses to run over existing exercise rows instead of converting or deleting
them: #266 starts from a fresh local Learning database
([local runbook](../../docs/deploy/selfhost-local.md)). There are no compatibility readers.
Rollback is a protected PR revert plus recreation of the same disposable local database; an
older build cannot read V21 rows.
Fixtures: [mechanics.json](mechanics.json).

### Author preview evaluation (#267)

`POST /api/exercise-previews` lets the exercise editor play its interactive demo and the author's own draft
with the **same** evaluator as Study, without a second client-side implementation. It is stateless and
side-effect free: no exercise, session, attempt, receipt, progress, exposure or media row is read or written;
`Cache-Control: private, no-store`; authenticated with the authoring write scope; body ≤ 64 KiB. The request is
`{exercise: {type, schemaVersion: 2, content, answerKey, evaluatorPolicy}, action}` validated with the
publication rules for type/content/answer key/evaluator (MATERIAL blocks are checked structurally but not
resolved; media `assetId`s are opaque). Actions: `SUBMIT {response, hintedBlankIds, pairMistakes,
transcriptRevealed}` → `{feedback}` with Study feedback shapes (no evidence or transition;
`referenceContent` is `[]` because the editor shows the author's draft itself); `PAIR_CHECK {leftId, rightId}`
→ `{correct}`; `HINT {blankId}` → `{blankId, firstLetter}`. `ai-semantic` returns `UNAVAILABLE`, never a
substitute result. `hintedBlankIds` must be distinct blanks with `firstLetterHint: true` (as in Study);
`CANCEL` is a Study-only terminal response and is rejected; `PAIR_CHECK` is MATCH-only. All validation
failures, including bodies over the cap, are the opaque `400 INVALID_REQUEST`. Fixtures: [preview.json](preview.json).

### AI assessment and speech-to-text capabilities

Both are server-owned, disabled-by-default capabilities
(`learning.features.ai-assessment.enabled`, `learning.features.speech-to-text.enabled`). A
capability is available only when its flag is true **and** a provider is configured; no provider
exists, so a mistakenly enabled flag yields `PROVIDER_NOT_CONFIGURED`, never a fake result.
`GET /api/capabilities` returns `{aiAssessment, speechToText}` as `{available, reason}` with
`reason ∈ DISABLED | PROVIDER_NOT_CONFIGURED` (null when available) and no provider details.

`FREE_RESPONSE` may declare `evaluatorPolicy {id:"ai-semantic", version:"1", rubric}` with a
typed rubric (`referenceAnswer`; 1..10 `criteria {criterionId, description, critical}`; exactly
the levels `COMPLETE`, `PARTIAL`, `INSUFFICIENT` with descriptions) or `content.responseInput =
TEXT_OR_SPEECH`. While the matching capability is unavailable, publication fails with 409
`CAPABILITY_UNAVAILABLE` after structural validation; Study skips such candidates, and an issued
`ai-semantic` presentation evaluates to `UNAVAILABLE` + `EVALUATOR_UNAVAILABLE` with no exact-match
fallback. A future evaluator maps `COMPLETE→CORRECT`, `PARTIAL→PARTIAL`, `INSUFFICIENT→INCORRECT`,
provider uncertainty to `UNSURE` and provider failure to `UNAVAILABLE`; it returns evaluation and
evidence only and never writes `StudyState`. There is no speech-to-text endpoint or response kind.
Author audio recording is ordinary media upload and does not depend on either flag. The provider
foundation itself remains issue #77.

## Session resources

| Operation | Request | Success |
|---|---|---|
| Start | `POST /api/decks/{deckId}/study-sessions` | 201 ACTIVE/EMPTY or 202 PREPARING |
| Read/resume | `GET /api/decks/{deckId}/study-sessions/{sessionId}` | 200 current bounded batch |
| Refill | `POST /api/decks/{deckId}/study-sessions/{sessionId}/presentations` | 200 next bounded batch |
| Reveal transcripts | `POST /api/decks/{deckId}/study-sessions/{sessionId}/presentations/{presentationId}/transcript` with `{nonce}` | 200 `{presentationId, transcriptRevealed, content}` |
| Reveal first letter | `POST /api/decks/{deckId}/study-sessions/{sessionId}/presentations/{presentationId}/hints` with `{nonce, blankId}` | 200 `{presentationId, blankId, firstLetter}` |
| Check one pair | `POST /api/decks/{deckId}/study-sessions/{sessionId}/pair-checks` with `{presentationId, nonce, leftId, rightId}` | 200 `{correct}` |
| Submit | `POST /api/decks/{deckId}/study-sessions/{sessionId}/attempts` | 200 stored outcome |
| Today's replay sources | `GET /api/decks/{deckId}/study-sessions/replay-sources` | 200 bounded completed sessions |
| Restart items | `POST /api/decks/{deckId}/study-restarts` | 200 restart acknowledgement |
| Material progress | `GET /api/decks/{deckId}/study-progress?limit=...&cursor=...` | 200 explainable page |

The client requests only intent. Start accepts a server-enforced mode and bounded
budget; timezone/local study date are resolved from the authenticated account and
stored in the session. A scheduled budget pins both total presentations and the
maximum number of previously unseen objectives. The current UI maps `QUICK` to
10/2 and `STANDARD` to 20/5; 10–15 minutes is guidance, not a time guarantee.
The response owns snapshot, exercise/objective revisions, roles, mode, evaluator,
nonce, budget and selection-policy identity. Submit therefore contains no
client-selected Deck revision, binding roles, correct answer or scheduler flag.

`SCHEDULED` selects due objectives, then introduced unassessed objectives, then at
most the remaining new-objective allowance. Stopping early creates no attempt and
does not remove the objective from a future session.
`REPLAY` requires a completed source session from the same account/deck/local day
and reuses its presentation revisions/order without old responses. `PRACTICE`
selects introduced objectives by default; `includeNew=true` is explicit. A batch
contains at most 20 presentations. A READY candidate generation is keyed by the
pinned exercise root; absent preparation returns `PREPARING`, never an unbounded
fallback scan. Retry/resume uses an opaque cursor and deterministic seed.

Before issuing a new Scheduled/Practice presentation, every bound material must
still belong to the current Deck. This membership check applies to newly started
sessions and refill, even when they reuse a candidate generation. It does not
require the material's current revision to equal the pinned revision: editing
content preserves existing versioned exercises. [Material deletion](../items/README.md)
does not cascade into exercise definitions or history; already issued presentations
and explicit Replay retain their immutable snapshots.

**Learner presentation.** Each issued presentation carries `{presentationId, nonce, ordinal,
exerciseRevisionId, type, objectiveId, objectiveRevisionId, learningEpoch, content,
transcriptRevealed, hints, evaluator}`. `content` is resolved once at issue and replayed verbatim:
`MATERIAL` becomes `TEXT`, media blocks expose only `assetId` (+ image `alt`) and
`transcriptAvailable`, `MATCH` sides are shuffled independently by a secure random source at issue
and persisted; the answer key never adjusts the permutation (any arrangement, including
rows that happen to line up, is possible), `CLOZE` blanks expose only `blankId`,
`size {mode,length}` and `firstLetterHint`. No presentation contains an answer key, accepted
strings, correct option/pair IDs, binding rows, media titles or unrevealed transcripts.
`SELF_CHECK` carries its reference blocks because revealing them is the interaction; clients keep
them hidden until the learner's explicit reveal. Playback URLs are resolved separately under owner
authorization.

The transcript and first-letter routes require the same owner/session/nonce and a pending,
unexpired presentation; each records an append-only accommodation before returning text, and a
repeated request returns the same value. The first letter is the first extended grapheme of the
NFC reference for that one blank and is available only where the author enabled it. Read/resume
shows the same disclosure state. A pair check is durable and idempotent and never changes progress.

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

- `TEXT {text}` for `FREE_RESPONSE`;
- `SELF_CHECK {rating}` with `NOT_RECALLED`, `HINTED`, `PARTIAL` or `FULL`;
- `CLOZE {blanks:[{blankId,text}]}` covering exactly the issued blank IDs;
- `CHOICE {optionIds}`, a non-empty unique array of server-issued option IDs (exactly one for `SINGLE`);
- `MATCH {pairs:[{leftId,rightId}]}`, an exact one-to-one map of all issued left and right IDs;
- `ORDER {sequence}`, an exact permutation of the issued item IDs;
- `CATEGORIZE {assignments:[{itemId,categoryId}]}`, every issued item exactly once to an issued category;
- `CANCEL`, which terminalizes as `NOT_ASSESSED` without a transition.

The submit command is `{attemptId, presentationId, nonce, response, confidence, durationMs}`.
Clients do not claim hints: only server-recorded first-letter and transcript accommodations
affect evidence.

`confidence` is optional calibration metadata and has no reducer effect in v1.
`durationMs` is bounded diagnostic metadata and never changes correctness/evidence.
| Mechanic | Result | Evidence class | Feedback after submit |
|---|---|---|---|
| `SELF_CHECK` | `FULL→CORRECT`, `PARTIAL`/`HINTED→PARTIAL`, `NOT_RECALLED→INCORRECT` | `LOW`, `SELF_REPORT` | rule only |
| `FREE_RESPONSE` | any accepted alternative → `CORRECT`, else `INCORRECT` | `HIGH`; revealed transcript → `LOW` | `reference`, `referenceContent` |
| `CLOZE` | all blanks `CORRECT`, some `PARTIAL`, none `INCORRECT` | `HIGH`; any hinted blank caps non-incorrect results at `MEDIUM`; transcript → `LOW` | per-blank `correct`, `hinted`, `reference` |
| `CHOICE` | exact selected set → `CORRECT`, else `INCORRECT` | `LOW` recognition | `correctOptionIds` |
| `MATCH` | all pairs `CORRECT`, some `PARTIAL`, none `INCORRECT`; a correct final map after any wrong pair check is `PARTIAL` + `PAIR_RETRY` | `LOW` recognition | per-pair selected/correct IDs |
| `ORDER` | exact (equivalence-aware) sequence → `CORRECT`, else `INCORRECT` | `MEDIUM` sequencing | `correctSequence`, per-position correctness |
| `CATEGORIZE` | all items `CORRECT`, some `PARTIAL`, none `INCORRECT` | `LOW` recognition | per-item selected/correct category |

Soft matching (`SOFT`) is a documented string normalization, not semantic understanding.
Deterministic incorrect production can be `HIGH`: result describes direction, while
evidence class describes reliability of the observation. A composite result is one
observation of the subject objective; it is never copied to context materials.

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
[replay-sources.json](replay-sources.json), [restart.json](restart.json),
[flows.json](flows.json),
[reducer-v1.json](reducer-v1.json) and
[adversarial.json](adversarial.json). Each fixture validates against the matching
definition in [study.schema.json](study.schema.json); public wire DTOs reject unknown
fields at every described level. Adversarial request/effect payloads are descriptive
test plans rather than wire DTOs, so their inner keys intentionally vary by case.

Material progress includes the required bounded `title` described in the
[LearningItem summaries contract](../items/README.md#readable-summaries).
