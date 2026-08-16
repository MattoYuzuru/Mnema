---
artifact:
  id: exercise-catalog-v2
  type: product-requirements
  title: "Mnema exercise catalog and learning contracts v2"
  status: proposed
  created_at: "2026-08-15"
  updated_at: "2026-08-15"
  owners: ["project-owner"]
---

# Каталог упражнений Mnema v2

## Принцип

Материал, способ его показа, проверяемый навык и упражнение — разные сущности.

```text
Learning item → immutable content revision
              → one or more memory objectives
              → zero or more versioned exercises
Exercise attempt → deterministic evidence → optional AI evidence → scheduler outcome
```

Простая «карточка Anki» остаётся одним из пресетов: показать prompt, вспомнить, открыть reveal, оценить себя. Она больше не определяет форму всех данных. Автор колоды может добавлять совместимые упражнения к одному материалу; AI может предлагать их, но не создаёт исполняемый пользовательский код.

Просмотр не обновляет интервальный прогресс. Прогресс принадлежит `memory objective`: например, `слово → смысл` и `смысл → слово` могут планироваться независимо, тогда как typed и reveal одного направления могут давать evidence одному objective.

## Общий контракт попытки

Каждая оцениваемая механика обязана вернуть один стандартный envelope:

```json
{
  "attemptId": "client-generated-uuid",
  "exerciseRevisionId": "...",
  "itemRevisionId": "...",
  "objectiveId": "...",
  "response": {},
  "hintsUsed": [],
  "durationMs": 4200
}
```

Сервер сохраняет точную ревизию, версию evaluator, deterministic result, optional AI result и окончательное решение. Повторная отправка того же `attemptId` не создаёт вторую попытку.

Проверка выполняется слоями:

1. нормализация, aliases, допустимые варианты, единицы, tolerance и явные правила;
2. специализированный детерминированный evaluator, если нужен;
3. AI только для действительно открытых ответов или как объяснимый второй сигнал;
4. self-grade/manual override там, где автоматическая проверка не заслуживает доверия.

Опечатка пользователя не становится общим правильным ответом автоматически. Её можно сохранить как личный accepted alias с источником и аудитом; автор отдельно решает, добавлять ли её в общую ревизию.

## Каталог возможностей

Каталог — граница расширяемости, а не обещание реализовать всё сразу.

### Просмотр и управляемое раскрытие

| Тип | Ответ | Проверка | Применение |
|---|---|---|---|
| Browse/read | bookmark/telemetry | не оценивается | свободный просмотр длинного материала |
| Progressive reveal | раскрытые стадии | не оценивается | пошаговое доказательство, стих, код, схема |
| Recall → reveal → self-rating | Again/Hard/Good/Easy | self-grade | универсальная Anki-подобная механика |

### Активное воспроизведение

| Тип | Ответ | Проверка | Применение |
|---|---|---|---|
| Short typed answer | строка | aliases, normalization, typo tolerance | термин, дата, определение |
| Multi-value/list recall | набор строк | set/order policy, partial score | признаки, этапы, перечисления |
| Cloze | значения по blank ID | deterministic | текст, код, формула, стих |
| Listening dictation | текст | transcript aliases | языки, музыка, фонетика |
| Oral semantic recall | audio + transcript | STT + rubric/AI позже | интервью, объяснение, устная речь |
| Pronunciation/shadowing | audio | specialized scorer/self-grade | произношение и интонация |
| Draw/sketch recall | strokes/asset | self-grade/CV позже | анатомия, схемы, графики |
| Code completion | текст по blank ID | tokens/AST fragments | syntax/API recall |
| Code solution against tests | source | isolated deterministic runner | алгоритмы и программирование |

### Узнавание и различение

| Тип | Ответ | Проверка | Применение |
|---|---|---|---|
| Single choice | option ID | deterministic | первый контакт, диагностический scaffold |
| Multiple select | option IDs | deterministic/partial | несколько верных признаков |
| True/false + correction | boolean + текст | deterministic + optional rubric | misconceptions |
| Error spotting | ranges/node IDs + fix | anchored rules | язык, код, доказательства |
| Odd one out | option + reason | choice deterministic | классификация и границы понятия |

### Связи, структура и порядок

| Тип | Ответ | Проверка | Применение |
|---|---|---|---|
| Matching pairs | пары ID | deterministic | термин–смысл, событие–дата |
| Categorization | item→bucket | deterministic | классы, части речи, диагнозы |
| Ordering/timeline | ordered IDs | deterministic/partial distance | процессы, история, алгоритмы |
| Sentence/code assembly | token IDs | accepted sequences | синтаксис, код, формулы |
| Diagram labeling/hotspots | label→region | deterministic | анатомия, география, UI |
| Image occlusion | region response/reveal | deterministic/self-grade | визуальные структуры |
| Concept-map completion | nodes/edges | graph constraints | причинные и системные связи |

### Применение и объяснение

| Тип | Ответ | Проверка | Применение |
|---|---|---|---|
| Explain in own words | long text/audio | self/manual or structured AI | понимание концепта |
| Why/how question | long text | rubric/key concepts | причинное мышление |
| Scenario/case decision | option/text | rules or AI rubric | медицина, право, product cases |
| Continue worked solution | next step/text | solution graph | математика, физика, алгоритмы |
| Numeric/problem solving | value/unit/work | tolerance/unit rules | STEM |
| Symbolic equivalence | expression | symbolic engine later | алгебра, логика, химия |
| Translation/adaptation | text/audio | aliases then AI | естественные языки |
| Use in sentence | text/audio | constraints then AI | продуктивная лексика |
| Compare/contrast | structured/prose | dimensions/rubric | сложные концепты |
| Essay/interview answer | long text/audio | structured AI/manual | экзамены и собеседования |

AI verdict для открытых ответов имеет стабильную схему, а не свободный текст:

```json
{
  "verdict": "CORRECT|MOSTLY_CORRECT|MOSTLY_INCORRECT|INCORRECT|UNSURE",
  "score": 0.0,
  "confidence": 0.0,
  "matchedCriteria": [],
  "missingCriteria": [],
  "feedback": ""
}
```

`UNSURE` не должен превращаться в уверенную автоматическую оценку. Пользователь видит основание, может оспорить результат, а исходный ответ и deterministic evidence остаются неизменными.

## Совместимость упражнений с материалом

Каждая ревизия материала публикует вычисляемые capabilities, например `HAS_AUDIO`, `HAS_ORDERED_SEGMENTS`, `HAS_CLOZE_ANCHORS`, `HAS_HOTSPOTS`, `HAS_EXECUTABLE_CODE_SPEC`. Exercise type декларирует обязательные capabilities и валидирует node references при публикации.

Это позволяет смешивать в одной колоде язык, код, схемы, стихи и видео. Нельзя назначить listening без audio segment или diagram labeling без region map; UI объясняет, чего не хватает, и предлагает совместимый тип.

## Рекомендуемый порядок реализации

### P0 — доказать учебный цикл

1. Browse/read.
2. Recall → reveal → self-rating.
3. Short typed answer.
4. Cloze.
5. Single choice и multiple select.

Это минимальный набор с одним `StudySessionShell`, общим attempt contract и детерминированной проверкой. Он покрывает текущую механику и первые новые режимы без дорогого AI в критическом пути.

### P1 — дать ощутимое разнообразие

1. Matching pairs.
2. Ordering/sentence assembly.
3. Listening dictation.
4. Multi-value recall.
5. Diagram labeling/image occlusion.
6. Deterministic translation aliases and accepted variants.

### P2 — сегментные режимы

1. Code completion and isolated test runner.
2. Pronunciation/oral recall.
3. Numeric/symbolic solving.
4. Explain/why/scenario/use-in-sentence with structured AI assessment.
5. Draw and concept-map recall.

Приоритет retrieval и spacing важнее декоративного разнообразия: крупный обзор оценил practice testing и distributed practice как high-utility, а self-explanation/interleaving — как полезные, но более контекстные техники ([Dunlosky et al.](https://pubmed.ncbi.nlm.nih.gov/26173288/)); retrieval practice особенно полезна для долгосрочного удержания и выигрывает от feedback ([Roediger & Butler](https://pubmed.ncbi.nlm.nih.gov/20951630/)).

## UX requirements

- пользователь запускает обучение из конкретной колоды; глобальная межколодная очередь запрещена для v2;
- Browse и Study — две явные команды;
- автор добавляет упражнения к материалу через «+ упражнение» и preview, а не через schema builder;
- AI suggestions объясняют, какой objective и какие content nodes они используют;
- одна сессия может чередовать упражнения только внутри выбранной колоды;
- answer input, reveal, feedback, override, pause и exit полностью доступны с клавиатуры;
- hover-функции имеют focus/tap эквивалент;
- длинный контент скроллится, а основное действие остаётся достижимым;
- собственные audio/video controls имеют semantic fallback, captions/transcript и reduced-motion behaviour.

## Изменение материала и прогресс

Каждая правка классифицируется внутренне; пользователь видит обычные формулировки, а не `semantic breaking`:

- `PRESENTATION_ONLY`: прогресс сохраняется;
- `SEMANTIC_COMPATIBLE`: прогресс сохраняется, новая ревизия фиксируется в следующей попытке;
- `OBJECTIVE_CHANGED`: изменился правильный ответ, rubric или то, что проверяется. История остаётся связанной со старой revision; новая objective revision получает `REVALIDATION_REQUIRED` и одну ближайшую проверку, а не молчаливый полный reset.

Автоматически стирать историю нельзя. AI может предложить классификацию, но детерминированные правила сначала сравнивают answer specs, objective and referenced nodes; low-confidence решение требует подтверждения.

## Acceptance gates

- один content item публикуется с несколькими exercises без копирования контента;
- разные exercises одного objective корректно обновляют одну study state, а reverse objective — другую;
- invalid node/capability references отклоняются до публикации;
- retries идемпотентны;
- deterministic result и AI result различимы и аудируемы;
- browse никогда не изменяет scheduler;
- deck-scoped session не выдаёт item из другой колоды;
- accessibility и offline retry входят в контракт общего shell, а не реализуются отдельно каждым exercise.
