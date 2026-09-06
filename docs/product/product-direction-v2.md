---
artifact:
  id: product-direction-v2
  type: prd
  title: "Mnema greenfield product direction"
  status: proposed
  created_at: "2026-08-15"
  updated_at: "2026-09-06"
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

Уточнение владельца от 6 сентября: первый результат — **свои личные колоды в web**,
материалы и гибкие упражнения к ним. Каталог сообщества, sharing, forks и native
offline — следующие продуктовые этапы, не скрытые зависимости первого запуска.
Подробные сценарии и границы принятого решения:
[authoring and study workflows](./authoring-and-study-workflows.md).

## Кому и для чего

### Launch cohort A — языки

Люди, уже изучающие английский, французский, испанский, японский, китайский или корейский с преподавателем, курсом, фильмами или собственными заметками.

Job: быстро зафиксировать слово/фразу/пример голосом или текстом, обогатить материал и практиковать его через recall, cloze, listening, translation and ordering.

### Launch cohort B — студенты и экзамены

Студенты ВШЭ/ЦУ и других вузов с конспектами, кодом, формулами, схемами и вопросами к экзамену.

Job: собрать материал без проектирования шаблона и повторять его через typed, cloze, choice, ordering, diagram/code and explanation exercises.

### Future supply side — преподаватель/автор

Job: совместно поддерживать колоду, выпускать проверенные обновления, видеть текущих подписчиков и делиться публично, по ссылке/запросу или приватно.

Не нужно выбирать один тип данных для этих cohorts. P0 product loop у них общий; различаются acquisition message, starter content and later exercises. Два concierge cohorts должны решить, какой message масштабировать первым.

## Продуктовые решения

| ID | Status | Decision |
|---|---|---|
| D-01 | `pursue` | Native entity — rich learning item; templates, arbitrary fields and mandatory deck languages удаляются. |
| D-02 | `pursue` | Exercises are separate versioned interactions over stable content and memory objectives. |
| D-03 | `pursue` | Study is always deck-scoped; no global cross-deck Today queue. Browse remains separate. |
| D-04 | `defer` | Manual LearningItem/Study MVP has no AI dependency. Managed AI, provider selection and quota economics return only through reactivated #77. |
| D-05 | `pursue` | Immutable revisions reuse unchanged blocks/media/manifests; future editable forks have independent deck-local identity and progress. |
| D-06 | `defer UX; reserve model` | Private decks launch first. Public/restricted sharing, coauthors and publication review follow validated learning. |
| D-07 | `defer` | Selective upstream updates and later contributions/advanced merges are part of the future model. Manual pull is recommended; automatic tracking is not yet selected. |
| D-08 | `hypothesis` | Russia-first B2C. Free deck-count limits and subscription expansion are possible; 5 own + 5 copied is an example, not a committed tariff. Earlier AI Starter/trial research is not current pricing. |
| D-09 | `defer` | Existing self-host is unsupported during v2 rewrite; a sanitized downstream repository may appear later. |
| D-10 | `defer` | Anki import follows native launch; no APKG round-trip promise. |

## Core journeys

### Create

The same native output can be created through:

1. full editor with desktop split preview and mobile editor/preview switch;
2. durable quick text/batch notes, provisionally «На потом», without a TTL or study eligibility;
3. audio capture when the media capability exists; automatic transcription is not a manual-launch dependency;
4. future AI/import/enhancement only after the manual loop is validated; it is not part of current delivery.

The full editor supports long scrolling rich content, media, ruby, RTL, math, code and diagrams without exposing schema fields. Deck theme controls the initial visual style; arbitrary per-card layout is deferred.

Одна колода может смешивать слова, грамматику и любые конспекты. Пользователь
выбирает допустимые материалы и короткие представления для каждого упражнения:
matching не пытается поместить весь конспект в одну ячейку. Две стороны flashcard
принадлежат упражнению, а не самому материалу. Сохранённый материал может пока не
иметь упражнений. Незавершённые быстрые заметки не создают memory/study state.
Рабочие правки восстанавливаются из серверного черновика; обучение использует
только явно сохранённую версию. Параллельные вкладки не перетирают изменения молча.

### Study a deck

1. User opens one deck and chooses `Учить`.
2. Mnema shows due/new volume for that deck only.
3. Session routes among compatible enabled exercises within the deck.
4. User answers before reveal and receives deterministic or explicit self/human-rubric feedback; AI assessment is absent.
5. Pause/exit preserves confirmed attempts exactly once.
6. Completion shows progress and next due time for this deck.

После завершения доступны повтор сегодняшней выборки и практика по всей колоде
вне сегодняшнего лимита. Их можно запускать снова; оба режима не меняют расписание,
канонический прогресс, streak или результаты scheduler experiments. Полная практика
по умолчанию исключает ещё не вводившиеся материалы, допускает shuffle/weak-first
и ограничение времени; большие колоды подгружаются порциями. Новые due позже в тот
же день остаются нормальным обучением: запрета «одна сессия в сутки» нет.

Исправление ответа сохраняет историю и текущее расписание. Отдельное действие
«Учить заново» явно перезапускает выбранные материалы без удаления истории.
Прогресс показывается на уровне материала внутри колоды, без обещания вечного
«100% выучено»; способ агрегации underlying objectives ещё требует проверки.

`Смотреть` opens free browse and never updates scheduler state.

### Maintain, fork and update — after personal launch

1. Author/coauthors edit a draft and publish an immutable revision through review/approval when configured.
2. An editable fork reuses immutable content while keeping independent identity, edits, exercises and learning state. Proposed first UX: the user requests a change preview and selects an upstream pull; automatic tracking remains open.
3. Conflicting items show `мой вариант` and `новый вариант` side by side with highlighted differences.
4. Subscriber keeps mine, accepts source or explicitly combines supported nodes; unresolved items do not block other safe updates.
5. Tracking can be disabled. Source withdrawal preserves the last reachable revision for existing subscribers.
6. Later users may combine supported parts of conflicting material and propose selected improvements upstream. This supersedes the earlier permanent “no upstream” restriction; neither contribution UI nor real-time collaboration blocks personal launch.

### Discover and share — later community release

- `PUBLIC`: appears in catalog/profile.
- `REQUEST_RESTRICTED`: accessed by link/class and join approval.
- `PRIVATE`: owners/coauthors only.

An author profile has visibility settings, public decks, last update and current subscriber/usage count. No creator payouts and no update-notification subscriptions in v2.

Будущий onboarding предлагает создать свою колоду или выбрать качественную колоду
сообщества. Каталог, рекомендации, likes/quality signals и рейтинг — гипотезы
community release; обещание готового большого каталога допустимо в маркетинге
только после появления реального предложения. На текущем макете каталог не
показывается. Notification mechanics остаются отдельным будущим решением.

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
- full editor, recoverable server editing drafts and durable quick text notes;
- deterministic evaluator and standard attempt envelope;
- fully manual/free product with no provider or AI runtime dependency;
- own private decks with efficient revision storage; reserve provenance/visibility for later sharing;
- replay and whole-deck extra practice with no canonical effects; explicit learning restart;
- Russian paper/antiquity design, accessible and progressively loaded;
- two 15–20-person concierge cohorts: language and exam/student.

### P1 — visible differentiation

- multiple select, matching, ordering/assembly, listening dictation, multi-value recall and measured image occlusion/labeling;
- coauthors + publication review;
- cheap editable forks, selective update previews and conflict UI; automatic tracking only after a separate choice;
- public author profiles/reputation signals;
- community catalog/onboarding experiments and a separately specified payment/entitlement pilot;
- evidence-calibrated exercise policies and targeted interleaving.

### P2 — segment depth

- code completion/test runner, pronunciation/oral recall, numeric/symbolic answers;
- human/self-rubric explanation experiments; AI grading remains outside this roadmap until #77 is reactivated;
- native Android/iOS offline deck packs and review sync;
- richer within-material merges and proposals to improve upstream decks;
- higher-price speaking/tutor experiment only after retention and unit economics.

The full capability envelope is in [exercise catalog](./exercise-catalog-v2.md). Building all exercise types before proving retained learning would be wasteful.

## Acceptance criteria

### Content and creation

- user reaches a valid native item without choosing template, field schema, deck languages, provider or scheduler;
- desktop editor and preview remain synchronized; mobile switch does not lose selection/draft;
- ruby has hover, focus and tap behaviour; RTL mixed text, math, code, media and long scroll are accessible;
- draft/autosave operations are idempotent and never lose the original on failure;
- acknowledged editing drafts survive navigation/logout; quick notes never expire automatically and cannot enter Study;
- unrelated material blocks and all item content survive a metadata-only deck edit without physical duplication;
- unsupported content is shown explicitly and preserved, not silently deleted.

### Study

- Study launched from deck A never presents an item from deck B;
- Browse never changes due state;
- replay/whole-deck practice cannot update canonical state, even with a forged client flag;
- changed correct answers preserve scheduling until the user explicitly restarts learning;
- the same attempt ID never produces two outcomes or two scheduler updates;
- the result explains aliases/normalization/typo tolerance;
- self/human results are distinguished from deterministic evidence and remain explainable;
- all primary actions work with keyboard/screen reader/reduced motion and touch.

### Future updates and collaboration

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

Manual creation/study with own decks and media are the first product. Existing AI pricing/provider research is future evidence, not a committed package. A later subscription may expand limits on owned/copied decks and possibly native offline. The illustrative 5 + 5 limit is unpriced and unapproved. Expiry never deletes or disables existing decks: only adding beyond the free allowance is blocked until the user reduces holdings or renews. Any paid offer requires entitlement semantics and the [legal/payment checklist](./russia-legal-launch-checklist-2026.md).

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
2. Whether deck-count limits support a fair paid offer; owned/copied accounting, price and free allowance are undecided.
3. Whether offline belongs in a paid tier; earlier AI trial hypotheses apply only if #77 is reactivated.
4. Which P1 exercise most improves second-week return for each cohort.
5. Whether teacher sharing creates a repeatable acquisition loop before paid ads scale.
