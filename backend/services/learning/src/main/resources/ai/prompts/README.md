# Prompt library (skeleton, prompt_version v1)

Versioned prompt sections for the AI layer. **Skeleton only:** these are resource files; the loader, the
renderer and the provider calls belong to AI-02 ([#282](https://github.com/MattoYuzuru/Mnema/issues/282)). Nothing
here runs yet. Architecture: [AI generation platform §8](../../../../../../../../docs/architecture/ai-generation-platform.md);
the text is distilled from the
[context and quality research](../../../../../../../../docs/reviews/ai-layer-research-2026-10/context-and-quality.md) §4 and
adapted to the accepted contracts: [MBM v1](../../../../../../../../contracts/generation/mbm-v1/README.md) and the
[exercise output](../../../../../../../../contracts/generation/exercises/README.md). The assistant is «Мнема»; sections are
Russian because the product language is Russian.

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
in place.

## Placeholders

One syntax only, in the runtime-filled sections (`deck-brief`, `material`, `edit`, `exercises`, `assessment`):

| Form | Meaning |
|---|---|
| `{{path.to.value}}` | required value; an unresolved placeholder is a hard error, never an empty string |
| `{{path\|"default"}}` | value with a literal default used when the value is absent or empty |

`system`, `style` and `skills/*` contain **no** placeholders (they must stay byte-stable). Rendering rules:

- Values are inserted verbatim into the prompt text. Values from users, notes, materials, search results and learner
  answers are escaped first (`&` to `&amp;`, `<` to `&lt;`) so data cannot close a tag. A learner answer is inserted as a
  JSON string (`learner_answer_json`).
- A placeholder whose name ends in `_blocks` or `_lines` receives a code-rendered block: `exemplar_blocks`
  (`<exemplar id="E1" kind="starred">MBM</exemplar>` per exemplar), `note_blocks` (`<note id="N1">text</note>`),
  `search_result_blocks` (`<search_result n="1" url="…" title="…">snippet</search_result>`), `material_blocks`
  (`<material id="m1">` with one `[[b3]]` handle line per top-level block), `outline.lines`
  (`m12 · title · first line, 120 characters · exercises: 3`), `objective_lines`, `existing_exercise_lines`,
  `neighbor_lines`, `criteria_lines`, `misconception_lines`, `allowed_links` (one URL per line).
- `{{schema}}` is rendered from `contracts/generation/exercises/output.schema.json`; the build of AI-13 copies it into the
  resources.
- No personal data ever enters a placeholder (account IDs, email, names, payment data); preflight excludes
  PII-looking fragments before rendering.

Placeholder names used by v1 and their owners:

| Section | Placeholders |
|---|---|
| `deck-brief` | `deck.title`, `deck.description`, `lang.output`, `lang.target`, `level`, `counts.items`, `counts.exercises`, `deck_terms`, `style_card.{words,headings,lists,tables,examples,audio}`, `exemplar_blocks`, `recent_material`, `outline.{total,shown,lines}` |
| `material` | `allowed_links`, `note_blocks`, `search_result_blocks`, `request`, `task.{skill,words,media}`, `lang.output`, `level` |
| `edit` | `document`, `history`, `preset`, `instruction` |
| `exercises` | `schema`, `material_blocks`, `objective_lines`, `existing_exercise_lines`, `neighbor_lines`, `task.{count,mechanics}`, `lang.output` |
| `assessment` | `exercise.{prompt,reference}`, `criteria_lines`, `misconception_lines`, `material_fragment`, `feedback_language`, `answer_source`, `learner_answer_json` |

## What these files deliberately leave out

- **Code blocks.** MBM v1 has no `code_block`; the `code` skill writes inline code. CONTENT-01
  ([#303](https://github.com/MattoYuzuru/Mnema/issues/303)) adds the directive; the prompt change is a `v2`.
- **`::verify`**, drafted in the research, is not an MBM v1 construct and is not mentioned.
- `::image mode="generate"` and `::video` are described as "only when the task allows"; availability is decided by the
  server, which rejects the directive otherwise (`MBM_CAPABILITY_OFF`).
- Style-card computation, outline budgets, exemplar selection and the slop lint belong to AI-04; they only fill the
  placeholders above.

## Sampling and gates

Starting temperatures (refined in the golden eval): materials 0.7-0.9, edits 0.5-0.7, exercises 0.3-0.5, grader 0.2-0.3;
thinking is switched off explicitly. Every change of `prompt_version`, model or temperature needs the regression run of the
golden eval (AI-17, [#300](https://github.com/MattoYuzuru/Mnema/issues/300)).
