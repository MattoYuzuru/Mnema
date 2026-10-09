---
artifact:
  id: domain-truth-map
  type: navigator
  title: "Mnema domain truth map and glossary"
  status: current
  created_at: "2026-10-01"
  updated_at: "2026-10-07"
  owners: ["project-owner"]
---

# Domain truth map and glossary

Purpose: stop agents inferring business rules from v1 code, old docs or evidence snapshots.
Start with the source for the question below; expand to linked implementation/tests
only when needed to resolve the task. Statuses are those of the
[documentation index](../README.md). When a source and the running code disagree, check the
executable source (tests, migrations, routes, fixtures) and fix the canonical doc; do not add a
competing copy.

## Where is the truth

| Question | Canonical source | Not a source of truth |
|---|---|---|
| Exercise mechanics, wire shapes, evaluators, session modes, errors, fixtures | [`contracts/study/README.md`](../../contracts/study/README.md) and the JSON fixtures beside it (executed by backend and frontend tests) | `docs/system-overview.md` counts of mechanics, v1 review/scheduler code |
| Why exercises and evidence work this way (objective vs exercise vs attempt, evidence classes, retention) | [Exercise catalog](../product/exercise-catalog-v2.md) | Epic refinements as backlog |
| Authoring, Capture («На потом»), drafts, Study modes, restart | [Authoring and Study workflows](../product/authoring-and-study-workflows.md) | UX refinement notes superseded by the contract |
| Owner decisions and open questions | [Owner decisions](../decisions/owner-decisions-2026-08.md) | Reviews, research, launch hypotheses (`proposed`) |
| Product direction, economics, legal | [`docs/product/`](../README.md#product): only `accepted` rows are requirements; `proposed` are hypotheses | |
| Content model and persisted document format | [Native content format](../architecture/learning-content-format-v2.md), [`contracts/content/`](../../contracts/content/native-v1) | v1 template/field/card model |
| Deck, item, authoring command shapes | [`contracts/decks`](../../contracts/decks/README.md), [`contracts/items`](../../contracts/items/README.md), [`contracts/authoring`](../../contracts/authoring/README.md) | |
| AI sessions, approval, edits, speech, usage and notifications | [Generation](../../contracts/generation/README.md), [speech](../../contracts/speech/README.md), [usage](../../contracts/usage/README.md), [notifications](../../contracts/notifications/README.md); operating flags: [AI runbook](../operations/ai-runbook.md) | frozen run evidence and research prompts |
| Runtime behaviour, database shape, routes | Service guides ([Identity](../../backend/services/identity-account/guide.md), [Learning](../../backend/services/learning/guide.md)), ordered migrations, controllers/tests, `frontend/src/app/app.routes.ts` | `docs/services/*`, `docs/core-entities-schema.md` (legacy v1) |
| Public events, editorial access, feedback boundary | [Events contract](../../contracts/events/README.md), [public updates and feedback](../architecture/public-updates-and-feedback.md) | Telegram handles as authentication; private notifications as release notes |
| UI direction, tokens, a11y boundaries | [Design and experience](../frontend/design-and-experience-2026-09.md), [Brand and UI contract](../frontend/mnema-brand-and-ui-contract.md), `frontend/src/theme/tokens.css` | `design/prototype` (evidence), Liquid Glass, the 2026-08 experience audit |
| Delivery, what “done” means, deployment | [Production delivery](../operations/production-delivery.md) and root `AGENTS.md` | removed Kubernetes staging/recovery runbooks (Git history only) |
| Issue / PR / Project status format | [Work item standard](./work-item-standard.md); live status is read from GitHub | [GitHub execution model](./github-execution-model.md) (historical setup) |
| Commands, machine setup | [Agent runbook](./agent-runbook.md) | prose in evidence files, old session handoffs |
| What was proven, when, on which revision | [Evidence index](./evidence/README.md) | Anything under `evidence/` as current behaviour |

Historical evidence, `legacy` v1 docs, the `v1-apache-final` tag and Git history explain *why*
something looked the way it did. They are read-only: never copy a rule, name or table from them
into new work (greenfield rule in root `AGENTS.md`).

## Glossary (terms agents confuse)

| Term | Meaning here | Do not confuse with |
|---|---|---|
| **LearningItem** | Deck-local unit of material, versioned as immutable `ItemRevision`s. Local to one deck; not a cross-deck knowledge entity. | a v1 card/template/field |
| **Content** | What the learner *sees*: native-document nodes and exercise slot blocks (`TEXT`, `MATERIAL`, `IMAGE`, `AUDIO`, `VIDEO`, `YOUTUBE`). Media type is content. | a mechanic |
| **Mechanic** | What the learner *does*: `SELF_CHECK`, `FREE_RESPONSE`, `CLOZE`, `CHOICE`, `MATCH`, `ORDER`, `CATEGORIZE`. There is no audio/video/listening mechanic. | content type, exercise “kind” |
| **Objective** (`MemoryObjective`) | The single assessable skill (stable identity + short title, versioned) that an exercise attempts to evidence. Forward and reverse of an item are separate objectives. Never stores option, blank or pair IDs. | the exercise, or the item |
| **Exercise** | Immutable `ExerciseRevision`: subject item revision + content + answer key + evaluator policy, bound to exactly one assessed objective (P0). | an attempt |
| **Evaluator** | How a response is checked: `self-check`, `deterministic-text|cloze|choice|match|order|categorize`; `ai-semantic` is implemented for free explanations when the server assessment capability is available. Server-side only. | the mechanic or the answer key |
| **Answer key** | Correct answer data inside the exercise revision; never sent to the learner before feedback. | evaluator policy |
| **Presentation** | One issued, immutable, server-built question inside a session (`presentationId`, nonce, resolved content, no answer key). The client submits against it and chooses no revisions or roles. | the exercise definition |
| **Session mode** | Immutable per session: **scheduled** (due → introduced → bounded new; may write exposure, evidence, `StudyState`), **replay** (re-run a completed same-day session; feedback only), **practice** (introduced objectives, `includeNew` explicit; feedback only). Replay/practice never change canonical progress. | Browse, which also never writes |
| **Attempt / evidence** | An attempt is one idempotent submission; per-objective evidence (`HIGH/MEDIUM/LOW/NONE`) comes from result, evaluator and hints. | a scheduler update |
| **StudyState** | Per-objective scheduler state changed only by the versioned reducer from scheduled evidence. | exercise or attempt data |
| **EditingDraft / CaptureNote** | Server-side unsaved edits (expire) vs. quick notes «На потом» (no idle TTL, outside Study). | each other |
| **Capability** | Two unrelated senses. (1) Server feature flag + provider, e.g. `aiAssessment`/`speechToText`, reported by `GET /api/capabilities`; off by default, availability depends on configuration, provider health and budget. Publication of a dependent exercise fails `CAPABILITY_UNAVAILABLE` when its capability is unavailable. (2) Native-content projection capabilities that authorize media/logical references. | the agent “capability inventory” in `docs/engineering/capability-inventory.yaml` (tool/command catalog) |
| **Epic #77** | AI generation/Workshop, assessment/speech, usage/plans/promo; current contracts and runtime guides own behavior. | v1 `ai` service or a promise that every production flag is enabled |
| **Epic #74 / #75 / #76, epic #265 (#266 / #267 / #268)** | Content+authoring / Study / media; #266 unified the exercise mechanics, #267 rebuilt the exercise editor as one step-by-step page with the stateless `POST /api/exercise-previews` boundary, #268 added `ORDER` and `CATEGORIZE` (seven total). Epic records are historical; the contract is current. | backlog |
