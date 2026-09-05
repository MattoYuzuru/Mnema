---
artifact:
  id: product-direction-v2
  type: prd
  title: "Mnema greenfield product direction"
  status: proposed
  created_at: "2026-08-15"
  updated_at: "2026-08-30"
  owners: ["project-owner"]
  assumptions:
    - "Approximately ten production accounts must survive; content, media, review and AI data may be reset."
    - "The initial hosted market and infrastructure are in Russia."
    - "The manual LearningItem/Study replacement launches without AI; managed AI is a separately reactivated future capability."
  unresolved_questions:
    - "Which launch message wins within the accessible cohorts: language learning or general exam/student learning?"
    - "Which deterministic exercise mix produces retained use in each launch cohort?"
---

# Greenfield-направление продукта Mnema

## Решение

**Продолжать проект, полностью сменив content paradigm и hosted positioning.**

Mnema — не «ещё один конструктор карточек» и не готовый curriculum как Duolingo. Она помогает человеку превратить собственный или авторский материал в несколько видов активной практики, регулярно возвращаться к нему и видеть реальный прогресс.

Рабочее обещание, которое ещё нужно проверить на пользователях:

> Запоминайте то, что важно именно вам: Mnema превращает материал в подходящие упражнения и помогает заниматься регулярно без настройки сложного инструмента.

Первый replacement не требует API key, provider/model, card schema или выбора алгоритма. Автор создаёт rich `LearningItem` и добавляет упражнения; учащийся выбирает колоду и запускает Browse или Study. Native content не зависит от Anki. `V2` в старых именах документов не означает `/v2`: продукт занимает канонические routes без legacy coexistence.

Принятые owner inputs находятся в [decision log](../decisions/owner-decisions-2026-08.md); техническая модель — в [content architecture](../architecture/content-platform-v2.md) и [native content format](../architecture/learning-content-format-v2.md).

## Кому и для чего

### Launch cohort A — языки

Люди, уже изучающие английский, французский, испанский, японский, китайский или корейский с преподавателем, курсом, фильмами или собственными заметками.

Job: быстро зафиксировать слово/фразу/пример голосом или текстом, обогатить материал и практиковать его через recall, cloze, listening, translation and ordering.

### Launch cohort B — студенты и экзамены

Студенты ВШЭ/ЦУ и других вузов с конспектами, кодом, формулами, схемами и вопросами к экзамену.

Job: собрать материал без проектирования шаблона и повторять его через typed, cloze, choice, ordering, diagram/code and explanation exercises.

### Supply side — преподаватель/автор

Job: совместно поддерживать колоду, выпускать проверенные обновления, видеть текущих подписчиков и делиться публично, по ссылке/запросу или приватно.

Не нужно выбирать один тип данных для этих cohorts. P0 product loop у них общий; различаются acquisition message, starter content and later exercises. Два concierge cohorts должны решить, какой message масштабировать первым.

## Продуктовые решения

| ID | Status | Decision |
|---|---|---|
| D-01 | `pursue` | Native entity — rich learning item; templates, arbitrary fields and mandatory deck languages удаляются. |
| D-02 | `pursue` | Exercises are separate versioned interactions over stable content and memory objectives. |
| D-03 | `pursue` | Study is always deck-scoped; no global cross-deck Today queue. Browse remains separate. |
| D-04 | `defer` | Manual LearningItem/Study MVP has no AI dependency. Managed AI, provider selection and quota economics return only through reactivated #77. |
| D-05 | `pursue` | Shared content/revisions are reused; subscription and personal changes are sparse, not copied decks. |
| D-06 | `pursue` | Public, request/link-restricted and private visibility; coauthors and review/approval supported. |
| D-07 | `pursue` | Automatic safe updates, clear summary, per-item conflicts and opt-out from tracking. |
| D-08 | `pursue` | Russia launch with Free, one-time 14-day trial without a card and Starter 299 ₽/30 days; Plus is deferred until demand and unit economics justify it. |
| D-09 | `defer` | Existing self-host is unsupported during v2 rewrite; a sanitized downstream repository may appear later. |
| D-10 | `defer` | Anki import follows native launch; no APKG round-trip promise. |

## Core journeys

### Create

The same native output can be created through:

1. full editor with desktop split preview and mobile editor/preview switch;
2. quick text/batch draft;
3. voice capture with transcript and optional retained original audio;
4. future AI/import/enhancement only after the manual loop is validated; it is not part of current delivery.

The full editor supports long scrolling rich content, media, ruby, RTL, math, code and diagrams without exposing schema fields. Deck theme controls the initial visual style; arbitrary per-card layout is deferred.

### Study a deck

1. User opens one deck and chooses `Учить`.
2. Mnema shows due/new volume for that deck only.
3. Session routes among compatible enabled exercises within the deck.
4. User answers before reveal and receives deterministic or explicit self/human-rubric feedback; AI assessment is absent.
5. Pause/exit preserves confirmed attempts exactly once.
6. Completion shows progress and next due time for this deck.

`Смотреть` opens free browse and never updates scheduler state.

### Maintain and subscribe

1. Author/coauthors edit a draft and publish an immutable revision through review/approval when configured.
2. Subscriber normally receives conflict-free changes automatically plus a compact added/changed/removed summary.
3. Conflicting items show `мой вариант` and `новый вариант` side by side with highlighted differences.
4. Subscriber keeps mine, accepts source or explicitly combines supported nodes; unresolved items do not block other safe updates.
5. Tracking can be disabled. Source withdrawal preserves the last reachable revision for existing subscribers.
6. A clone/fork becomes independently editable; changes are not proposed upstream.

### Discover and share

- `PUBLIC`: appears in catalog/profile.
- `REQUEST_RESTRICTED`: accessed by link/class and join approval.
- `PRIVATE`: owners/coauthors only.

An author profile has visibility settings, public decks, last update and current subscriber/usage count. No creator payouts and no update-notification subscriptions in v2.

## Roadmap by product evidence

### Gate 0 — foundation and destructive-migration approval

- approve content/exercise contracts and fresh account-only reset;
- preserve and rehearse restoration of production accounts;
- fix immutable asset caching and release safety;
- prototype native editor/renderer against ruby, RTL, math, code, media, Mermaid and long content;
- establish attempt and privacy-safe funnel evidence.

### P0 — useful learning loop

- deck-scoped Browse and Study;
- reveal/behavioral self-check, typed answer, single-blank cloze and single choice;
- full editor plus quick text/voice draft;
- deterministic evaluator and standard attempt envelope;
- fully manual/free product with no provider or AI runtime dependency;
- public/private/restricted deck basics and shared revision storage;
- two 15–20-person concierge cohorts: language and exam/student.

### P1 — visible differentiation

- multiple select, matching, ordering/assembly, listening dictation, multi-value recall and measured image occlusion/labeling;
- coauthors + publication review;
- safe auto-updates and conflict UI;
- public author profiles/reputation signals;
- Russian recurring payment pilot at 299 ₽ and quota/cancellation validation;
- evidence-calibrated exercise policies and targeted interleaving.

### P2 — segment depth

- code completion/test runner, pronunciation/oral recall, numeric/symbolic answers;
- human/self-rubric explanation experiments; AI grading remains outside this roadmap until #77 is reactivated;
- native Android/iOS offline deck packs and review sync;
- higher-price speaking/tutor experiment only after retention and unit economics.

The full capability envelope is in [exercise catalog](./exercise-catalog-v2.md). Building all exercise types before proving retained learning would be wasteful.

## Acceptance criteria

### Content and creation

- user reaches a valid native item without choosing template, field schema, deck languages, provider or scheduler;
- desktop editor and preview remain synchronized; mobile switch does not lose selection/draft;
- ruby has hover, focus and tap behaviour; RTL mixed text, math, code, media and long scroll are accessible;
- draft/autosave operations are idempotent and never lose the original on failure;
- unsupported content is shown explicitly and preserved, not silently deleted.

### Study

- Study launched from deck A never presents an item from deck B;
- Browse never changes due state;
- the same attempt ID never produces two outcomes or two scheduler updates;
- the result explains aliases/normalization/typo tolerance;
- self/human results are distinguished from deterministic evidence and remain explainable;
- all primary actions work with keyboard/screen reader/reduced motion and touch.

### Updates and collaboration

- publication is immutable and does not copy every unchanged item;
- before/after summary counts are correct and retries create no duplicates;
- personal notes, additions, hides, preferences and history survive source updates;
- unresolved conflict has an explicit retained base and does not silently take latest content;
- source deletion/withdrawal does not break an existing subscriber's last revision;
- only authorized author/reviewer can publish.

### Deferred AI and later billing

- first value requires no AI service, provider key, quota or paid entitlement;
- every create/Browse/Study journey remains complete with AI absent;
- #77 requires a new owner activation plus quality, privacy, availability and cost evidence;
- any later billing entitlement comes from verified durable payment state, not a return URL.

### Data control

- account deletion immediately revokes access and follows the category-specific legally approved retention schedule; no recovery duration is a default until counsel confirms its basis;
- public authors cannot see subscriber answers/progress/private notes;
- public content is not used to improve AI before an explicit grant and provider/privacy policy exists;
- offline retry and multi-device attempts are idempotent and converge by a documented reducer.

## Metrics and gates

| ID | Metric | Initial gate/use |
|---|---|---|
| M-01 | signup → first valid item → first assessed attempt in 24h | ≥45% in concierge-ready traffic |
| M-02 | time from ready material/deck to first submitted answer | p75 ≤2 minutes |
| M-03 | activated user with a second session in 7 days | initial target ≥25%, split by cohort |
| M-04 | deck session completed or intentionally stopped after ≥80% planned volume | ≥70% without inflated session time |
| M-05 | authored item reaches first valid assessed attempt without schema repair | establish baseline in concierge cohort |
| M-06 | source update without personal-data loss | 100% in test corpus and production telemetry |
| M-07 | p95 media + egress variable cost / active learner | establish before paid launch |
| M-08 | future paid conversion after validated non-AI or AI value | deferred; no universal target yet |
| M-09 | future AI cost per accepted result | #77 only after reactivation |
| M-10 | weekly users completing ≥2 assessed sessions | proposed north-star with quality guardrails |

Do not optimize card count, raw review count, opens or streak in isolation. Segment/channel/exercise/provider/price must be attached to every funnel metric without logging full private material.

## Packaging and economics

Manual creation/study, public decks and own media are the first product. Existing AI pricing/provider research is retained as future evidence, not as a dependency or committed package. Any paid offer is reconsidered after manual-product validation and the [legal/payment checklist](./russia-legal-launch-checklist-2026.md).

The immutable `v1-apache-final` revision permits commercial derivatives and its
grants remain irrevocable. New official revisions use the accepted personal-use
source-available license: organizational, shared, hosted, commercial and
machine-learning use requires a separate written license. This legal boundary
is not a product moat by itself; hosted advantage must still come from quality,
safe rich rendering, maintained decks, varied practice, collaboration and reliable operations.

## Risks and guardrails

| Risk | Guardrail |
|---|---|
| Product remains a configuration tool | no templates/fields/provider/scheduler decisions before first result |
| Broad audience creates incoherent roadmap | same P0 loop, separate acquisition cohorts, no segment-heavy P2 before cohort evidence |
| Deferred AI assumptions leak into core | no provider/jobs/quota dependency in #73–#76; #77 requires explicit reactivation |
| Media destroys margin | explicit caps, p95 ledger and no unlimited promise |
| Rich content becomes an XSS platform | typed renderer; no native JS/arbitrary CSS; Anki converts to native nodes and never executes legacy content |
| Public updates destroy trust | immutable revisions, sparse overlays, explicit conflicts and retained last source revision |
| Two repositories diverge | self-host unsupported now; later one-way sanitized release, not manual dual development |
| Gamification replaces learning | assessed-session/retention metrics rather than opens/streak |

## Remaining product experiments

1. Language vs exam/student landing message and activation in accessible cohorts.
2. Starter quota at fixed 299 ₽ and whether real usage justifies a later Plus tier.
3. 14-day trial activation at first AI intent and whether no-card trial improves retained activation.
4. Which P1 exercise most improves second-week return for each cohort.
5. Whether teacher sharing creates a repeatable acquisition loop before paid ads scale.
