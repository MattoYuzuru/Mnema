# Exercise generation output (generation-v1)

How a model proposes exercises and how the server turns a proposal into the **same**
publication command the authoring UI sends. Authority:
[AI generation platform §6](../../../docs/architecture/ai-generation-platform.md) and the
[Study contract](../../study/README.md) (`mechanics.json`, seven mechanics). Status: **contract only** —
AI-13 ([#291](https://github.com/MattoYuzuru/Mnema/issues/291)) implements it. The prompt source is
[`prompts/v1/exercises.md`](../../../backend/services/learning/src/main/resources/ai/prompts/v1/exercises.md).

```text
model ── strict JSON (output.schema.json) ──▶ 1 schema ─▶ 2 lint ─▶ 3 compile + ExerciseCommand.readCreate ─▶ 4 self-evaluation ─▶ artifact PROPOSED
                                                  └─ any finding: one repair call with the findings, then strong route, then FAILED(INVALID_OUTPUT)
```

The model never sees or invents a UUID. It writes **local IDs** and **handles**; the server allocates real
IDs through an injected allocator and builds the command from the pinned revision of the material.

## Model output

[`output.schema.json`](output.schema.json) is JSON Schema 2020-12 (restricted to the keywords the repository's
fixture validator supports). The root is an object `{"exercises": [ ... ]}` of 1..20 exercises (JSON-object
response modes need an object root). Each exercise is validated, linted and compiled **independently**: one
bad exercise fails only its own artifact. `anyOf` selects the branch by the `mechanic` constant. The schema is
a hint for providers (`json_object`, strict tools, structured outputs); the server validation below is the
source of truth.

Common fields of every exercise:

| Field | Meaning |
|---|---|
| `mechanic` | `SELF_CHECK`, `FREE_RESPONSE`, `CLOZE`, `CHOICE`, `MATCH`, `ORDER`, `CATEGORIZE` |
| `subject` | Material handle `m1`: the pinned material whose objective this exercise evidences (`subject` of the command) |
| `objective` | `{"ref":"t1"}` reuses an offered objective (`objective.operation=reuse`), or `{"title":"…"}` creates one (`create`, ≤160 UTF-16 units) |
| `prompt` | 1..8 blocks: `{"kind":"TEXT","text"}` (≤4000) or `{"kind":"MATERIAL","ref":"m1:b3"}` |

Handles: `m1` is a pinned material of the session; `m1:b3` is block `b3` of that material's pinned
revision (the same top-level-block handles as MBM edits, `[[b3]]`); `t1` is an offered objective. IDs:

| Local ID | Class | Used by |
|---|---|---|
| `o1` | option | CHOICE |
| `l1`, `r1` | left / right item | MATCH |
| `bl1` | blank | CLOZE |
| `i1` | item | ORDER, CATEGORIZE |
| `c1` | category | CATEGORIZE |

Mechanic-specific fields (all texts are plain text; media blocks are not generated in v1; only `prompt` and
SELF_CHECK/FREE_RESPONSE `reference` may hold `MATERIAL` blocks):

| Mechanic | Fields | Compiles to |
|---|---|---|
| `SELF_CHECK` | `reference` (1..8 blocks) | `content {prompt, reference}`, `answerKey {kind: SELF_REPORT}`, evaluator `self-check`/`1` |
| `FREE_RESPONSE` | `reference` (0..8 blocks), `accepted` (1..20 strings ≤512), `matchingMode` `STRICT`\|`SOFT`, optional `normalization` (default `UNICODE_NFC, TRIM, CASE_FOLD`) | `content {prompt, reference, responseInput: "TEXT"}`, `answerKey {kind: TEXT, accepted, normalization, matchingMode}`, `deterministic-text`/`1` |
| `CLOZE` | `passage` (2..64 `TEXT` and `BLANK {blank, size, firstLetterHint}` segments; `size` is `{mode: ANSWER_LENGTH}` or `{mode: FIXED, length: 5..20}`), `blanks` (1..12 `{blank, accepted ≤10 strings ≤200, matchingMode, normalization?}`) | `content {prompt, passage}`, `answerKey {kind: CLOZE, blanks}`, `deterministic-cloze`/`1` |
| `CHOICE` | `selectionMode` `SINGLE`\|`MULTIPLE`, `options` (2..12 `{id, text ≤300, correct}`; `whyWrong ≤300` is **required** on `correct: false` and forbidden on `true`) | `content {prompt, selectionMode, options}`, `answerKey {kind: CHOICE, correctOptionIds}`, `deterministic-choice`/`1`; `whyWrong` is dropped before publication |
| `MATCH` | `left`, `right` (2..6 `{id, text ≤300}` each), `pairs` (`{left, right}`) | `content {prompt, left, right}`, `answerKey {kind: MATCH, pairs}`, `deterministic-match`/`1` |
| `ORDER` | `items` (2..12 `{id, text ≤1000}`) **listed in the correct order** | `content {prompt, items}`, `answerKey {kind: ORDER, sequence}` = the listed order, `deterministic-order`/`1`; Study shuffles |
| `CATEGORIZE` | `categories` (2..6 `{id, label ≤80}`), `items` (2..12 `{id, text ≤300, category}`) | `content {prompt, categories, items}`, `answerKey {kind: CATEGORIZE, assignments}`, `deterministic-categorize`/`1` |

`whyWrong` (the research draft calls it `why_wrong`) is the rationale of each distractor; it is used by lint and
review, never shown to a learner. CHOICE options keep the model's order here; the learner-side shuffle is
STUDY-01 ([#304](https://github.com/MattoYuzuru/Mnema/issues/304)).

## Compile rules

`compile(exercise, context, ids)` is pure and Spring-free. `context` carries what the server pinned:

| Context | Meaning |
|---|---|
| `commandId`, `expectedDeckRevisionId` | Placeholders in the artifact payload; supplied at approval (fixtures use fixed values) |
| `materials` | `{m1: {memberKey, itemRevisionId, blocks: {b3: {nodeId, text}}}}` of the pinned revisions; `text` is the block's plain text |
| `objectives` | `{t1: {objectiveId, objectiveRevisionId}}` offered to the model |

1. `subject` → `exercise.subject {memberKey, itemRevisionId}` of `materials[subject]`.
2. `{"ref":"t1"}` → `objective {operation: "reuse", objectiveId, objectiveRevisionId}`; `{"title"}` →
   `objective {operation: "create", title}`.
3. `MATERIAL m1:b3` → `{"kind":"MATERIAL","memberKey","itemRevisionId","nodeId"}` of that revision; text blocks
   are kept verbatim (no trimming, no Unicode normalization).
4. Local IDs become UUIDs by class through the allocator. The compiler never calls `UUID.randomUUID()`.
5. Constants: `exercise.type` = mechanic, `schemaVersion` 2, `enabled` true, evaluator as in the table; no
   `bindings` (the server derives them).
6. The result is `{commandId, expectedDeckRevisionId, objective, exercise}`, byte-compatible with
   `contracts/study/mechanics.json` (`createCloze` and friends). The artifact stores `{objective, exercise}`
   (the payload of `getArtifact`); approval adds the two envelope fields.

### Golden allocator

Golden `expectedCommand` files use deterministic IDs per class, `<prefix>-0000-4000-8000-<12 hex digits>`, counted
from 1 in order of first appearance in the model output:

| Class | Prefix |
|---|---|
| option `o` | `0b000000` |
| blank `bl` | `b1a00000` |
| left `l` | `1e000000` |
| right `r` | `7e000000` |
| item `i` | `0d000000` |
| category `c` | `ca000000` |

`expectedIdMap` in a fixture lists the resulting local-to-UUID map; probes use **local** IDs and are translated through
it. Placeholder values: `commandId` `018f1d98-5c10-7abc-8abc-0123456789c1`, deck revision `2222…`, member `4444…`,
item revision `5555…`, objective `7777…771`/`773` (the same placeholders as `contracts/study`), material node IDs
`00000000-0000-4000-8000-0000000000{02,04,06,08,0a}` for blocks `b1..b5`.

## Validation phases

Findings are stable codes in [`lint.json`](lint.json) (`{code, path?}`, never echoing content). Order:

1. **SCHEMA** — `SCHEMA_INVALID` (one finding per exercise).
2. **LINT** — reference resolution and the per-mechanic table below. A lint whose prerequisite failed is skipped
   (`CLOZE_FRAGMENT_NOT_IN_MATERIAL` needs a key that covers the blanks).
3. **COMMAND** — the compiled JSON is read by `ExerciseCommand.readCreate(...)`, the parser of the publication
   endpoint. A rejection that no lint predicted is `COMMAND_REJECTED` (a compiler or lint gap, not a model fault).
4. **SELF_EVALUATION** — every probe of the fixture runs through `AttemptEvaluation`, the evaluator behind
   `/exercise-previews`. The **key as a response must be `CORRECT`**; a **distractor or shifted pair must be
   `INCORRECT`**. Probes are generated by the server per mechanic (see below); fixtures record them.

| Mechanic | Architecture lint (§6) | Codes |
|---|---|---|
| `SELF_CHECK` | reference nonblank and not equal to the prompt | `SELF_CHECK_REFERENCE_BLANK`, `SELF_CHECK_REFERENCE_EQUALS_PROMPT` |
| `FREE_RESPONSE` | normalized answer not in the prompt; alternatives distinct; SOFT never gives an empty string | `FREE_RESPONSE_ANSWER_IN_PROMPT`, `FREE_RESPONSE_ALTERNATIVES_NOT_DISTINCT`, `FREE_RESPONSE_SOFT_EMPTY` |
| `CLOZE` | each blank an existing fragment of the pinned text; key covers exactly the blank IDs; ANSWER_LENGTH answers of equal length | `CLOZE_FRAGMENT_NOT_IN_MATERIAL`, `CLOZE_KEY_BLANK_MISMATCH`, `CLOZE_ANSWER_LENGTH_MISMATCH` |
| `CHOICE` | 2..12 distinguishable options; SINGLE exactly one correct; no "all/none of the above" unless asked | schema bounds, `CHOICE_OPTIONS_NOT_DISTINCT`, `CHOICE_CORRECT_COUNT`, `CHOICE_ALL_OR_NONE_OF_THE_ABOVE` |
| `MATCH` | 2..6 pairs, bijection, sides distinct, labels do not give a pair away | schema bounds, `MATCH_NOT_BIJECTION`, `MATCH_LABELS_NOT_DISTINCT`, `MATCH_LABEL_LEAKS_PAIR` |
| `ORDER` | at least 2 distinguishable items, unambiguous order | `ORDER_ITEMS_NOT_DISTINGUISHABLE` (an unambiguous order needs the optional critic) |
| `CATEGORIZE` | at least 2 non-empty categories, each item in exactly one | `CATEGORIZE_UNKNOWN_CATEGORY`, `CATEGORIZE_TOO_FEW_NON_EMPTY_CATEGORIES` (exactly-one is structural: `category` is a single field) |
| all | handles and local IDs are consistent | `REF_UNKNOWN_HANDLE`, `REF_UNKNOWN_OBJECTIVE`, `DUPLICATE_LOCAL_ID` |

### Self-evaluation probes

`expectedSelfEvaluation` is `[{probe, response, expectedResult}]`; `response` has the shape of the Study submit
`response` (`contracts/study/mechanics.json` `submits`) with local IDs.

| Mechanic | Probes the server generates | Expected |
|---|---|---|
| `SELF_CHECK` | none: the learner rates themselves, there is no machine-checkable key | — |
| `FREE_RESPONSE` | `KEY` (each accepted alternative), `WRONG_NEIGHBOR`, `EMPTY` | `CORRECT`, `INCORRECT`, `INCORRECT` |
| `CLOZE` | `KEY`, one blank wrong, all blanks wrong | `CORRECT`, `PARTIAL` (two or more blanks), `INCORRECT` |
| `CHOICE` | `KEY`, each distractor alone | `CORRECT`, `INCORRECT` |
| `MATCH` | `KEY`, all pairs shifted by one | `CORRECT`, `INCORRECT` |
| `ORDER` | `KEY`, reversed sequence | `CORRECT`, `INCORRECT` |
| `CATEGORIZE` | `KEY`, every item moved to another category | `CORRECT`, `INCORRECT` |

## Fixtures

`fixtures/<mechanic>-<case>.json` have these members: `description`, `context`, `modelOutput` (a root object with one
exercise), and **either** `expectedCommand` (+ `expectedIdMap`, `expectedSelfEvaluation`) for a valid case **or**
`expectedLint` (sorted codes) for a failing one. Valid cases are checked by `GenerationContractFixtureTest`: the
output validates against the schema and `expectedCommand` parses through `ExerciseCommand.readCreate`; failing
cases must name codes of `lint.json`, and every non-exempt code has a fixture.

| Mechanic | Valid | Failing |
|---|---|---|
| `SELF_CHECK` | `self-check-material-reference` | `self-check-reference-blank`, `self-check-reference-equals-prompt` |
| `FREE_RESPONSE` | `free-response-alternatives` (reused objective), `free-response-soft` | `free-response-answer-in-prompt`, `-alternatives-not-distinct`, `-soft-empty`, `free-response-missing-accepted` (`SCHEMA_INVALID`) |
| `CLOZE` | `cloze-two-blanks` | `cloze-fragment-not-in-material`, `-key-blank-mismatch`, `-answer-length-mismatch` |
| `CHOICE` | `choice-single` | `choice-options-not-distinct`, `-correct-count`, `-all-of-the-above` |
| `MATCH` | `match-three-pairs` | `match-not-bijection`, `-labels-not-distinct`, `-label-leaks-pair` |
| `ORDER` | `order-steps` | `order-items-not-distinguishable` |
| `CATEGORIZE` | `categorize-two-groups` | `categorize-unknown-category`, `categorize-too-few-non-empty-categories` |
| any | — | `ref-unknown-handle`, `ref-unknown-objective`, `duplicate-local-id` |

## Not in this contract

- The `ai-semantic` rubric (CORE/DETAIL/TERM, misconceptions) for FREE_RESPONSE: AI-13/AI-20 (#291, #292).
- Media blocks, material-valued options and tiles, and `CHOICE` `MULTIPLE` fixtures beyond the lint cases.
- The planner and per-material quantity: spec shape in [`http.json`](../http.json).
- The optional critic pass ("Detailed") and the `STALE` re-pin.

## Open questions

- Evaluator semantics versus the architecture wording: a swapped pair gives `PARTIAL` (some pairs correct) in
  `AttemptEvaluation`, not `INCORRECT`; the probe shifts **all** pairs so `INCORRECT` is well defined.
- Whole-word matching for `FREE_RESPONSE_ANSWER_IN_PROMPT` is tokenizer-dependent for CJK; a language-aware
  tokenizer is AI-13's decision.
- `CHOICE_ALL_OR_NONE_OF_THE_ABOVE` needs a per-language phrase list; the English and Russian phrases are in the
  fixture only.
