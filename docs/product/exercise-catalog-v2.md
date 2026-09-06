---
artifact:
  id: exercise-catalog-v2
  type: product-requirements
  title: "Mnema exercise and learning-evidence contracts"
  status: proposed
  created_at: "2026-08-15"
  updated_at: "2026-09-06"
  owners: ["project-owner"]
---

# Каталог упражнений и learning evidence

## Принцип

Материал, проверяемый навык, упражнение, попытка и изменение расписания — разные
сущности.

```text
LearningItem → immutable ItemRevision
             → one or more MemoryObjectives
ExerciseDefinition → immutable ExerciseRevision
ExerciseRevision → one or more pinned ItemRevisions with explicit roles
Scheduled attempt → per-objective evidence → versioned reducer → StudyState
Browse / replay / extra practice ───────────X no canonical update
```

`LearningItem` — канонический термин, локальный внутри одной колоды. Forward/reverse
могут иметь разные `MemoryObjective`, если нужны независимые расписания; это не
общая межколодная сущность знания. Пользователь видит прогресс материала, а не
обязан разбираться в objectives. Одно упражнение
может использовать несколько items, но показанный distractor/context не получает
progress. Scheduler update разрешён только для objective, по которому есть
наблюдаемый assessed response.

AI не входит в этот epic. P0/P1 работают с deterministic evaluator или явной
self/human rubric. Будущий #77 может добавить versioned evaluator, но не прямую
запись в `StudyState`.

## Learning evidence, а не «вес кнопки»

Relative class описывает достоверность конкретного измерения, а не обещанный
педагогический эффект механики:

| Class | Значение | Примеры |
|---|---|---|
| `HIGH` | самостоятельное воспроизведение objective надёжно проверено | correct unhinted typed production, deterministic domain answer |
| `MEDIUM` | полезный сигнал с cues, partial result или context dependence | cloze, matching per assessed pair, correct after a hint |
| `LOW` | правильный ответ мог возникнуть из recognition/guess/self-report | single choice, uncalibrated self-grade |
| `NONE` | assessment не произошло | Browse, context/distractor exposure, cancel, timeout, evaluator failure |

Evidence class зависит от результата, evaluator и подсказок. Response time хранится
для диагностики, но сам по себе не меняет correctness или strength: устройство,
IME и assistive technology влияют на время.

Точные numeric weights не являются product requirement. Их калибруют по следующему
unhinted retrieval и versioned experiment, не по субъективной сложности UI.

## Общий контракт попытки

Клиент отправляет один идемпотентный envelope, привязанный к зафиксированным
revision:

```json
{
  "attemptId": "client-generated-uuid",
  "sessionId": "server-issued-session-id",
  "presentationId": "server-issued-question-id",
  "deckId": "personal-deck-uuid",
  "deckRevisionId": "uuid",
  "effectiveSnapshotId": "uuid",
  "exerciseRevisionId": "uuid",
  "presentedBindings": [
    {"memberKey": "uuid", "itemRevisionId": "uuid", "role": "ASSESSED|CUE|OPTION|CONTEXT"}
  ],
  "response": {},
  "hintsUsed": [],
  "confidence": "KNEW|UNSURE|GUESSED|null",
  "durationMs": 4200
}
```

Evaluator сохраняет raw response, exact revisions, evaluator version и возвращает
per-objective results:

```json
{
  "status": "ASSESSED|NOT_ASSESSED|UNAVAILABLE",
  "objectiveEvidence": [
    {
      "objectiveId": "uuid",
      "result": "CORRECT|PARTIAL|INCORRECT|UNSURE",
      "evidenceClass": "HIGH|MEDIUM|LOW|NONE",
      "reasonCodes": ["UNHINTED", "DETERMINISTIC"]
    }
  ],
  "feedback": {}
}
```

Сервер сверяет envelope с сохранённой presentation: клиент не выбирает правильный
ответ, роли/набор целей или session mode. Effective snapshot фиксирует именно
личную колоду, включая выбранные private changes, а не только чужую source revision.
Versioned scheduler policy преобразует evidence в transition только для
`SCHEDULED`. `REPLAY` и `PRACTICE` возвращают feedback, но не пишут canonical
study state, introduction/exposure, due, streak или experiment outcome. Повторная
отправка того же `attemptId` и payload возвращает сохранённый outcome; повторное
использование ID с другим payload — idempotency conflict. Cancel, navigation away,
timeout или evaluator failure не становятся incorrect attempt.

Проверка выполняется слоями:

1. normalization, aliases, sets/order/unit/tolerance и явные deterministic rules;
2. специализированный deterministic evaluator;
3. self/human rubric, только когда автоматическая проверка недостоверна;
4. AI evaluator — deferred #77 после отдельного owner/privacy/evidence gate.

Опечатка не становится общим правильным ответом автоматически. Personal alias
хранит источник; author отдельно публикует shared alias в новой revision.

## Каталог кластеров

Каталог — граница возможностей, не обещание реализовать все mechanics одновременно.

| ID | Кластер | Cardinality | Основная цель | Evaluation | Typical evidence | Priority |
|---|---|---|---|---|---|---|
| E-00 | Browse/read, bookmark, progressive reveal без ответа | 1..N items | знакомство и навигация | none | `NONE` | P0 |
| E-01 | Recall → reveal → behavioral self-check | 1 item / 1 objective | свободное извлечение до показа | self rubric | `LOW`, later calibrated | P0 |
| E-02 | Short typed production: термин, дата, translation aliases | 1 / 1 | самостоятельно произвести ответ | deterministic | `HIGH` unhinted | P0 |
| E-03 | Cloze/code/formula completion | 1 item / 1..N blanks | cued production | deterministic per blank | `MEDIUM–HIGH` | P0 single; P1 multi |
| E-04 | Single choice; later multiple select, T/F+correction, odd-one-out | focal item + deck options | recognition/discrimination | deterministic | correct choice `LOW`; correction `MEDIUM` | P0 single; P1 rest |
| E-05 | Multi-value/list recall | 1 item / 1..N elements | полный set/order | deterministic partial | `HIGH` full; `MEDIUM` partial | P1 |
| E-06 | Matching/categorization | 2..N items from one deck revision | relation/discrimination | deterministic per assessed binding | `MEDIUM` | P1 |
| E-07 | Ordering/timeline/sentence/code assembly | one segmented item or 2..N items | structure/order/procedure | deterministic partial order | `MEDIUM` for order objective | P1 |
| E-08 | Contrast, contextual decision, error spotting + correction | focal + 1..N contrast items | apply or distinguish concepts | structured deterministic/self | `MEDIUM`, context-bound | P1 structured |
| E-09 | Explain/why/compare/free recall/worked next step | 1 item or explicit small set | conceptual reconstruction | self or human; AI deferred | `LOW` self, stronger only with valid rubric | P1 experiment/P2 |
| E-10 | Listening dictation/audio → typed | 1 / 1, audio capability | auditory retrieval | deterministic transcript/aliases | `MEDIUM–HIGH` | P1 language cohort |
| E-11 | Pronunciation/oral production/shadowing | 1 / 1 | spoken form/prosody | self/human; scorer deferred | `LOW` self | P2 |
| E-12 | Labeling/hotspot/image occlusion/sketch | 1 item, several regions/objectives | spatial/visual relation | deterministic regions or self/human | `MEDIUM` | P2 unless cohort gate |
| E-13 | Numeric/unit, symbolic, code completion/tests | normally 1 / 1 | exact domain application | specialized deterministic | `HIGH` when evaluator valid | P2 |

Recognition не бесполезно, но correct choice не означает `EASY`. Free response может
быть хорошей учебной практикой, но self-grade остаётся слабым измерением. Эти две
оси нельзя смешивать.

## Multi-item contract

1. Все bindings принадлежат выбранной личной deck и одному pinned effective snapshot, содержащему точные item/exercise revisions.
2. У каждого binding есть роль `ASSESSED`, `CUE`, `OPTION` или `CONTEXT`.
3. Только `ASSESSED` binding связывается с `MemoryObjective` и scheduler evidence.
4. Aggregate UI score не копируется всем участвующим items.
5. Matching/categorization возвращает per-objective result. Если валидное
   разложение невозможно, mechanic остаётся feedback-only и не влияет на schedule.
6. Forward/reverse objectives не получают двойной credit от одного directional
   exercise.
7. Incorrect pair не превращает два независимых objectives в две ошибки без двух
   наблюдаемых assessed responses.
8. Neighbor как option/context может иметь diagnostic display event, но не canonical introduction/exposure или review credit.
9. Набор revision фиксируется до ответа. Concurrent edit/remove не меняет
   начатое упражнение и не создаёт partial scheduler update.
10. Candidate pool использует явно включённые пользователем совместимые bindings внутри deck;
    случайная близость по rank сама по себе не делает distractor хорошим.

Пример владельца с четырьмя idioms поддерживается двумя способами: один focal
objective + три pinned options или четыре independently assessed pair results в
одном attempt group. Во втором случае каждый result обновляется отдельно; один
общий `4/4` не раздаёт mastery автоматически.

## Authoring: материал не равен ячейке упражнения

Пользователь свободно смешивает грамматику, слова и конспекты в одной колоде.
`ExerciseContentBinding.displaySpec` выбирает стабильные узлы/фрагменты либо
собственный короткий label/asset; это не обязательные поля у каждого материала.
Matching использует только явно разрешённый совместимый pool. Слово → перевод,
слово → аудио и пользовательская подпись → подпись допустимы; огромный документ
не становится подписью по умолчанию. Удалённая ссылка требует исправления,
а не подстановки полного материала. Начальный предложенный предел matching label —
80 graphemes; длина и media capabilities валидируются для каждой механики с
проверкой mobile/zoom. Полный материал остаётся доступен отдельно для чтения.

У flashcard явно настраиваются prompt и reveal; интервальное повторение —
политика планирования этих и других упражнений, а не особый двухсторонний материал.
Расширенные настройки раскрываются постепенно; редактор даёт быстрый путь через
выбор фрагментов и preview, не требует заполнять технический binding JSON.

## Progress, restart and optional practice

Принятые сценарии: [authoring and study workflows](./authoring-and-study-workflows.md).
Правка правильного ответа сохраняет историю и текущее расписание без автоматической
revalidation. «Учить заново» — отдельная подтверждённая идемпотентная команда,
которая перезапускает выбранные материалы, сохраняя старые events. Material progress
агрегирует objective state внутри колоды; формула/подписи требуют проверки, не
обещают вечного mastery и не копируют group score всем участникам.

После normal Study пользователь может уйти, повторить сегодняшнюю законченную
выборку (`REPLAY`) или практиковать всю колоду (`PRACTICE`), многократно. Replay
использует показанные revisions/questions без прежних ответов. Полная практика
по умолчанию исключает не введённый материал; seed, cursor, pinned eligibility и
небольшие batches обеспечивают shuffle/weak-first без полной загрузки колоды.
Все assessed ответы получают feedback, но только scheduled mode имеет canonical
effects. Новые due позже в тот же день не блокируются дневным флагом completion.

## Feedback, confidence и spacing

- Response фиксируется до reveal/correctness.
- Feedback показывает overall/per-part result, reference answer и применённые
  aliases/normalization/tolerance/hints.
- Choice/matching всегда дают corrective feedback; неправильный distractor не
  становится learned alias.
- Feedback не считается второй assessed attempt.
- Confidence, если собирается, фиксируется до feedback и сначала используется
  только для calibration; оно не отменяет deterministic incorrect.
- Self-check описывает поведение: «не вспомнил», «после существенной подсказки»,
  «самостоятельно, но частично», «самостоятельно и полно», а не только
  `Hard/Good/Easy`.
- Spacing — scheduler/session policy, не exercise type.
- Interleaving применяется внутри deck к действительно близким категориям и
  проверяется экспериментом; universal random mixing запрещён.

## P0 / P1 / P2

### P0 — доказать корректный learning loop

1. Browse, полностью отделённый от scheduler.
2. Recall → reveal → behavioral self-check.
3. Short typed production.
4. Single-blank cloze.
5. Single choice как recognition/error scaffold.
6. Common attempt/feedback/evidence/idempotency contract.
7. Keyboard, screen-reader and touch baseline общего shell.
8. Server-enforced replay/full-deck practice and explicit restart; durable quick notes are excluded from all study modes.

Multiple select перенесён из P0 в P1: он добавляет partial-scoring semantics, но не
доказывает новый loop сверх single choice.

### P1 — разнообразие без AI

- multiple select и true/false + correction;
- multi-value recall;
- matching/categorization с per-objective outcomes;
- ordering/assembly при однозначном decomposition;
- structured contrast/context decisions;
- limited self/human-graded explanation experiment;
- listening dictation;
- image labeling/occlusion только при cohort evidence;
- confidence calibration и targeted interleaving experiments.

### P2 — сегментные/дорогие evaluators

- pronunciation/oral production;
- general long-form explanation, essay/interview and cases;
- sketch/concept map;
- numeric/symbolic/code evaluators;
- cross-item objectives, если per-item decomposition доказанно недостаточно;
- delayed human assessment workflow.

AI grading не является P2 promise этого epic; он остаётся deferred #77.

## Accessibility requirements

- каждый exercise завершается keyboard-only, screen-reader, touch and speech-control flow;
- matching, sorting и hotspot имеют non-drag/non-gesture alternative;
- correctness, partial feedback, moved position and validation error объявляются
  assistive technology без неожиданного focus loss;
- radio/checkbox используют native semantics или полный documented interaction pattern;
- audio имеет accessible controls; transcript/accommodation policy не превращает
  disability в incorrect result;
- text scaling, zoom, reduced motion, RTL and IME не меняют учебный смысл;
- duration не штрафует assistive input.

Официальные accessibility boundaries: [WCAG 2.2 dragging movements](https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements), [WAI-ARIA radio pattern](https://www.w3.org/WAI/ARIA/apg/patterns/radio/) и [audio-only alternatives](https://www.w3.org/WAI/WCAG22/Understanding/audio-only-and-video-only-prerecorded.html).

## Evidence и ограничения вывода

Evidence-backed:

- retrieval practice улучшает delayed retention относительно restudy, хотя restudy
  может выглядеть лучше немедленно ([Roediger & Karpicke, 2006](https://pubmed.ncbi.nlm.nih.gov/16507066/));
- recall в среднем даёт больший testing benefit, чем recognition, но initial
  retrieval success и domain matter ([Rowland, 2014](https://pubmed.ncbi.nlm.nih.gov/25150680/), [Smith & Karpicke, 2014](https://pubmed.ncbi.nlm.nih.gov/24059563/));
- multiple-choice feedback снижает последующие lure intrusions, а feedback полезен
  для correct low-confidence answers ([Butler & Roediger, 2008](https://pubmed.ncbi.nlm.nih.gov/18491500/), [Butler et al., 2008](https://pubmed.ncbi.nlm.nih.gov/18605878/));
- generation обычно лучше пассивного чтения, но зависит от task constraints
  ([generation meta-analysis](https://pubmed.ncbi.nlm.nih.gov/32671573/));
- spacing benefit устойчив, но optimal gap зависит от retention horizon
  ([Cepeda et al., 2008](https://doi.org/10.1111/j.1467-9280.2008.02209.x));
- interleaving зависит от similarity/material и не является universal win
  ([Brunmair & Richter, 2019](https://pubmed.ncbi.nlm.nih.gov/31556629/));
- self-assessment страдает от fluency illusions, поэтому не становится сильным
  scheduler signal без calibration ([Koriat & Bjork, 2005](https://pubmed.ncbi.nlm.nih.gov/15755238/)).

Эти работы поддерживают semantic evidence classes и обязательный feedback, но не
дают готовых numeric scheduler weights для Mnema.

Product hypotheses для проверки на cohorts:

- bands `HIGH/MEDIUM/LOW` предсказывают следующий unhinted recall;
- deck-neighbor matching улучшает discrimination без confusion;
- per-objective multi-item feedback понятен и не создаёт false mastery;
- confidence capture улучшает calibration без лишнего friction;
- listening/contrast mechanics повышают retained use в соответствующем cohort;
- deterministic neighbor selection создаёт качественные distractors без AI.

## Acceptance gates epic #75

- **AC-STUDY-01:** Study из deck A не показывает items deck B даже как distractors.
- **AC-STUDY-02:** Browse/reveal без assessed response никогда не меняет due/mastery.
- **AC-STUDY-03:** Scheduled attempt изменяет только objectives с ролью `ASSESSED` и сохраняет объяснимый evidence class/reasons.
- **AC-STUDY-04:** cancel/timeout/evaluator failure дают `NOT_ASSESSED`, не incorrect.
- **AC-STUDY-05:** retry одного attempt имеет exactly-once scheduler effect.
- **AC-STUDY-06:** replay/practice, включая retry/offline sync и подменённый client flag, имеют zero canonical effects.
- **AC-STUDY-07:** ответ после edit сохраняет state; явный restart сохраняет history и перезапускает только выбранные материалы.
- **AC-STUDY-08:** full-deck practice не загружает всю колоду, не делает unbounded random scan и не включает quick notes/never-introduced по умолчанию.
- **AC-AUTHOR-01:** смешанная колода исключает не включённую грамматику из vocabulary matching; custom labels/media меняют представление, не исходный материал.
- **AC-EVAL-01:** deterministic evaluator имеет приоритет; AI отсутствует.
- **AC-EVAL-02:** result различает correct, partial, incorrect, unsure, not-assessed and unavailable.
- **AC-EVAL-03:** feedback раскрывает reference и применённые checking rules.
- **AC-EVAL-04:** recognition не превращается автоматически в strongest evidence.
- **AC-EVAL-05:** hints снижают positive evidence по явной policy.
- **AC-MULTI-01:** item/exercise/deck revisions фиксируются до submit.
- **AC-MULTI-02:** multi-item response возвращает per-objective outcomes; context/options не получают update.
- **AC-MULTI-03:** directional relation обновляет только объявленный objective.
- **AC-MULTI-04:** невозможность decomposition отклоняет scheduler-affecting publication.
- **AC-A11Y-01:** все P0 mechanics проходят keyboard/screen-reader/touch flow.
- **AC-A11Y-02:** drag/gesture никогда не является единственным способом ответа.
- **AC-A11Y-03:** feedback/focus/media alternatives доступны и предсказуемы.
- **AC-LEGACY-01:** ни один flow не зависит от card/template API, current review state или old algorithms.

P1 mechanic продвигается после P0 только если он доступен, его evidence
калибруется по следующему unhinted retrieval, он не повышает misconception/lure
rate и его cohort value оправдывает authoring/runtime cost.
