# Prompt library (active v1, candidate v2)

Versioned prompt sections for the AI layer. These are resource files read by `app.mnema.learning.ai.prompt`
(AI-02, [#282](https://github.com/MattoYuzuru/Mnema/issues/282)): `PromptLibrary` loads and validates them, `PromptRenderer`
renders the placeholders as specified below, `PromptAssembler` orders the layers and enforces the budgets. Generation
sessions that call them belong to AI-04. Architecture: [AI generation platform §8](../../../../../../../../docs/architecture/ai-generation-platform.md);
the text is distilled from the
[context and quality research](../../../../../../../../docs/reviews/ai-layer-research-2026-10/context-and-quality.md) §4 and
adapted to the accepted contracts: [MBM v1](../../../../../../../../contracts/generation/mbm-v1/README.md) and the
[exercise output](../../../../../../../../contracts/generation/exercises/README.md). The assistant is «Мнема»; sections are
Russian because the product language is Russian.

## Candidate v2 (not active)

The full v2 snapshot keeps the bodies of v1 unchanged except the exercise CLOZE rule: passage
and accepted answers remain verbatim in the source language; only the instructions follow the
output language. `MNEMA_GOLDEN_PROMPT_VERSION=v2` evaluates it without changing
`learning.ai.prompt.version=v1`. Candidate reports and the owner acceptance sample are part of
[#300](https://github.com/MattoYuzuru/Mnema/issues/300); activation requires the machine gate,
owner acceptance and calibrated judges. Existing v1 provenance remains valid.

## Layout and layers

Order is stable first, volatile last, so the prefix is cached (DeepSeek caches a shared prefix only after it has
seen it in two requests). L0-L2 are byte-identical for every user within a `prompt_version`.

| File | Layer | Cache scope | Typical size |
|---|---|---|---|
| `v1/system.md` | L0 core: Mnema, data policy, privacy, MBM v1 format, honesty, example | global | 1.6-2.1k tokens |
| `v1/style.md` | L1 Mnema writing style | global | 1.0-1.3k |
| `v1/skills/{vocabulary,grammar,stem-concept,code,exam-summary}.md` | L2 per-type skills; **all five** are in the global prefix | global | 0.9-1.2k |
| `v1/deck-brief.md` | L3-L5 deck, exemplars with a style card, latest material, outline | per deck | 0.3-1.6k + data |
| `v1/material.md` | L6-L7 sources and task for a material | per call | up to 12k + 0.5k |
| `v1/edit.md` | L7 for an EDIT step (after the document and history) | per call | 0.5-0.65k |
| `v1/exercises.md` | exercise skill, schema and task | per call | 1.0-1.3k + schema |
| `v1/exercise-edit.md` | revise ONE existing exercise (REVISE_EXERCISE, #294): the exercise in the output form, the material, the instruction | per call | 0.7-0.9k + schema |
| `v1/intent.md` | intent of «Попросить Мнему…» (#294): one sentence to one operation of a closed vocabulary | per call | 0.8-1.0k |
| `v1/plan.md` | planner of «Сначала показать план» (AI-14, #295): a plan of exercises or materials as strict JSON, within a credit budget, for a thinking model | per call | 0.9-1.1k + titles and clipped notes |
| `v1/assessment.md` | grader core and answer (own prefix, not part of the generation prefix) | per exercise | 0.6-0.8k + data |

Working input is 12-25k tokens per generation call with a hard ceiling of 32k for Flash non-thinking. The task and the
key rules are repeated **at the end**. Documents are marked with XML-like tags and treated as untrusted data; the
core says so (`<data_policy>`).

## File format

```text
---
section: system            # unique section name
prompt_version: v1         # the version directory; stored on every artifact revision
purpose: "one sentence"
---
<prompt text, sent verbatim after the front matter>
```

A section's body is never edited after `v1` is released: a change is a new directory (`v2`) so cached prefixes,
artifact provenance and the golden eval stay comparable. Typos in a released version are fixed by a new version, not
in place. **Adding a section to a released version is allowed** (it changes no released byte and no cached prefix): AI-16
([#294](https://github.com/MattoYuzuru/Mnema/issues/294)) added `exercise-edit` and `intent` to `v1`, and AI-14 ([#295](https://github.com/MattoYuzuru/Mnema/issues/295)) added `plan`; every section that
existed stays byte-stable, and a change to one of them is still a new version.

## Placeholders

One syntax only, in the runtime-filled sections (`deck-brief`, `material`, `edit`, `exercises`, `assessment`):

| Form | Meaning |
|---|---|
| `{{path.to.value}}` | required value; an unresolved placeholder is a hard error, never an empty string |
| `{{path\|"default"}}` | value with a literal default used when the value is absent or empty |

`system`, `style` and `skills/*` contain **no** placeholders (they must stay byte-stable). Rendering rules:

- Values are inserted into the prompt text in **one pass**: a placeholder that appears *inside* an inserted value (in a note, a
  material, a history entry) is **not** expanded again. Every value that comes from users, notes, materials, search results,
  history, objectives or learner answers is escaped first, in text and in attributes: `&` to `&amp;`, `<` to `&lt;`, `>` to
  `&gt;`, `"` to `&quot;`, so data cannot close a tag or break an attribute. A learner answer is inserted as a JSON string
  (`learner_answer_json`). `answer_source` is `TYPED` (text typed by the learner) or `SPEECH` (a transcript, so recognition
  errors are possible).
- A placeholder whose name ends in `_blocks` or `_lines` receives a code-rendered block: `exemplar_blocks`
  (`<exemplar id="E1" kind="starred">MBM</exemplar>` per exemplar), `note_blocks` (`<note id="N1">text</note>`),
  `search_result_blocks` (`<search_result n="1" url="…" title="…">snippet</search_result>`), `material_blocks`
  (`<material id="m1">` with one `[[b3]]` handle line per top-level block), `outline.lines`
  (`m12 · title · first line, 120 characters · exercises: 3`), `objective_lines`, `existing_exercise_lines`,
  `neighbor_lines`, `criteria_lines`, `misconception_lines`, `allowed_links` (one URL per line).
- `{{schema}}` is rendered from `contracts/generation/exercises/output.schema.json`; AI-13 keeps a classpath copy
  `ai/exercises/output.schema.json` (a test keeps the two identical) and the prompt carries it minified.
- The exercise section, the exercise revision, the intent, the plan and the research planner are assembled with the `<data_policy>` block of `system.md` in front of them as their own cacheable segment, taken
  verbatim (the section carries the material, the objectives and the existing exercises as data; the rest of the core describes the MBM
  format and does not apply to a JSON answer). `task.mechanics` renders `Механики: A, B, C` (registry names, comma separated) and may add a
  variety request after it; the Stub reads the count and the mechanics from there.
- No personal data ever enters a placeholder (account IDs, email, names, payment data). The server **always redacts** email,
  phone and card-number patterns in all user text before rendering (mandatory, not optional); the preflight warning that lets the
  user exclude other PII-looking fragments is an additional, user-facing step (`PERSONAL_DATA_SUSPECTED`). The model is also told
  not to repeat personal data (`<privacy>` in `system.md`).

Placeholder names used by v1 and their owners:

| Section | Placeholders |
|---|---|
| `deck-brief` | `deck.title`, `deck.description`, `lang.output`, `lang.target`, `level`, `counts.items`, `counts.exercises`, `deck_terms`, `style_card.{words,headings,lists,tables,examples,audio}`, `exemplar_blocks`, `recent_material`, `outline.{total,shown,lines}` |
| `material` | `allowed_links`, `note_blocks`, `search_result_blocks`, `request`, `task.{skill,words,media}`, `lang.output`, `level` |
| `edit` | `document` (rendered by code: the outline of the material as `[[bN]] first line`, at most 200 lines, then the tags `<context_before>`, `<target>` with the handles `b(index + 1)` and `<context_after>`), `history` (the last five finished instructions, one per line), `preset` (the Russian label «Проще», «Короче», «Пример», «Подробнее», or none), `instruction` (user text, or «без дополнительных указаний») |
| `exercises` | `schema`, `material_blocks`, `objective_lines`, `existing_exercise_lines`, `neighbor_lines`, `task.{count,mechanics}`, `lang.output` |
| `exercise-edit` | `schema`, `material_blocks`, `objective_lines`, `current_exercise_blocks` (rendered by code: the exercise in the output form as JSON inside `<current_exercise>`, redacted, only `& < >` escaped so the model copies valid JSON), `instruction` (user text), `lang.output` |
| `intent` | `context.{kind,title,operations,mechanics}` (kind `MATERIAL` or `EXERCISE`, the title of what the owner looks at, the operations the context allows, the mechanics of the registry), `request` (user text: the owner's sentence) |
| `plan` | `deck.{title,description}`, `counts.{items,exercises}`, `lang.output`, `target_lines` (rendered by code: `m1 · title · exercises: 3 (CLOZE 2, CHOICE 1)` per chosen material, EXERCISES only), `note_blocks` (`<note id="n1">` per chosen note, clipped, MATERIALS only), `outline.lines` (the latest titles of the deck, MATERIALS only), `request` (user text, default «не указана»), `limit_lines`, `task.{kind,hint,budget}` (kind `EXERCISES` or `MATERIALS`; the hint carries `Механики: A, B.` and `На материал: N.` for exercises; the budget is the batch hold in credits with the price of one unit) |
| `research` | `deck.title`, `lang.output`, `note_blocks` (`<note id="N1">` per source, clipped to 1500 tokens in all), `request` (user text, or the topic the plan chose), `task.cap` (the number of queries the model may propose; the server clamps to it anyway); strict JSON `{"queries": [...]}`, added by AI-18 (#299) |
| `assessment` | `exercise.{prompt,reference}`, `criteria_lines`, `misconception_lines`, `material_fragment`, `feedback_language`, `answer_source`, `learner_answer_json` |

### Skills: names, files and `task.skill`

The `<skill name="…">` tag, the file and the `task.skill` value of `material.md` map one to one. All five skills are in the global
prefix; `task.skill` only tells the model which one applies.

| `task.skill` | `<skill name>` | File |
|---|---|---|
| `style` | `style` | `v1/style.md` (always applies; not a choice) |
| `vocabulary` | `vocabulary` | `v1/skills/vocabulary.md` |
| `grammar` | `grammar` | `v1/skills/grammar.md` |
| `concept` | `concept` | `v1/skills/stem-concept.md` |
| `code` | `code` | `v1/skills/code.md` |
| `exam_notes` | `exam_notes` | `v1/skills/exam-summary.md` |
| `free` | none | no per-type skill (for example a humanities topic) |

`task.skill` is one of `vocabulary`, `grammar`, `concept`, `code`, `exam_notes`, `free`.

Language: the skills and the style are written in Russian, but they follow `<output_language>` (the `lang.output` of the deck brief):
Russian typography rules apply only when it is Russian. `<output_language>` is defined in `deck-brief.md`.

## What these files deliberately leave out

- **Code blocks** were absent from the first draft of v1. CONTENT-01 ([#303](https://github.com/MattoYuzuru/Mnema/issues/303))
  added fenced `code_block` to MBM v1 before any code loaded these files, so `system.md` and `skills/code.md` were updated in place: v1 is
  frozen from the first artifact revision that records `prompt_version: v1` (AI-04 [#287](https://github.com/MattoYuzuru/Mnema/issues/287)), not before.
- **A mandatory single `#` title.** The prompt asks for one `# title` as the first line, but the MBM compiler does not enforce it;
  the structure lint of AI-04 does, and the title fallback of Browse applies when it is missing.
- **`::verify`**, drafted in the research, is not an MBM v1 construct and is not mentioned.
- `::image mode="generate"` and `::video` are described as "only when the task allows"; availability is decided by the
  server, which rejects the directive otherwise (`MBM_CAPABILITY_OFF`).
- Style-card computation, outline budgets, exemplar selection and the slop lint belong to AI-04; they only fill the
  placeholders above.

## Sampling and gates

Starting temperatures (refined in the golden eval): materials 0.7-0.9, edits 0.5-0.7, exercises 0.3-0.5, grader 0.2-0.3;
thinking is switched off explicitly (except on the planner routes, which switch it on: `plan`, `plan-strong`). Every change of `prompt_version`, model or temperature needs the regression run of the
golden eval (AI-17, [#300](https://github.com/MattoYuzuru/Mnema/issues/300)).
