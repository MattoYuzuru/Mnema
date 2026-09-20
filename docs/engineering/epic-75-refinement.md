---
artifact:
  id: epic-75-refinement
  type: implementation-plan
  title: "Epic #75 Study refinement"
  status: accepted
  created_at: "2026-09-20"
  updated_at: "2026-09-20"
  owners: ["project-owner"]
  source_tasks: ["GitHub Epic #75"]
---

# Epic #75: принятый Study contract и delivery slices

Этот документ фиксирует решения владельца для реализации Epic #75. Точный wire
contract и исполняемые примеры находятся в [`contracts/study`](../../contracts/study/README.md).
Архитектурная модель остаётся в [Content and Study platform](../architecture/content-platform-v2.md),
а продуктовые правила — в [exercise catalog](../product/exercise-catalog-v2.md).

## Принятые решения

- `MemoryObjective` — одна проверяемая direction/answer contract внутри одного
  deck-local `LearningItem`. Forward/reverse — независимые objectives.
- В P0 одна presentation оценивает ровно один objective. Остальные bindings имеют
  роли `CUE`, `OPTION` или `CONTEXT`; multi-assessed matching отложен в P1.
- Первый reducer — прозрачный `mnema-baseline-v1`: восемь уровней и интервалы от
  10 минут до 60 дней. Пользователь не выбирает алгоритм.
- `confidence` и response time — диагностические данные; они не меняют результат
  или интервал. Hints снижают только положительный evidence class.
- Scheduled raw response хранится отдельной строкой 30 дней. Receipt, normalized
  evidence, before/after transition и version identity живут до удаления аккаунта.
  Replay/practice не сохраняют raw response и не имеют canonical effects.
- Progress показывает `NOT_STARTED`, `LEARNING`, `DUE` или `ON_TRACK`, coverage,
  last assessed и nearest due. Процент «выучено» не вычисляется.
- Явный restart увеличивает learning epoch, обнуляет текущее состояние выбранных
  objectives и сохраняет историю. Ответ от старой epoch становится `NOT_ASSESSED`.
- Scheduled budget — параметр одной очереди: quick 10 presentations/2 new objectives,
  standard 20/5. Due и уже введённые выбираются раньше новых; 10–15 минут остаётся
  UI-ориентиром, а не серверным временем остановки.

## Транзакционная граница

Attempt обрабатывается receipt-first: owner-scoped receipt lookup возвращает exact
retry до evaluator; затем идут авторизация, deterministic evaluation, transactional
receipt recheck, terminalization presentation и только затем lock/reduction
`StudyState`. `UNIQUE(presentation_id)` не допускает второй переход под новым
`attemptId`. Candidate generation привязана к immutable exercise root; если она не
готова, API возвращает `PREPARING`, а не делает полный scan.

## Delivery slices

| Issue | Outcome |
|---|---|
| [#212](https://github.com/MattoYuzuru/Mnema/issues/212) | Shared contracts, reducer table and adversarial fixtures |
| [#213](https://github.com/MattoYuzuru/Mnema/issues/213) | Immutable objective/exercise revisions |
| [#214](https://github.com/MattoYuzuru/Mnema/issues/214) | Bounded session snapshots and candidate pools |
| [#215](https://github.com/MattoYuzuru/Mnema/issues/215) | Attempt/evidence/reducer/restart vertical |
| [#216](https://github.com/MattoYuzuru/Mnema/issues/216) | Accessible exercise authoring |
| [#217](https://github.com/MattoYuzuru/Mnema/issues/217) | Scheduled self-check and typed Study UI |
| [#218](https://github.com/MattoYuzuru/Mnema/issues/218) | Single-blank cloze and single choice |
| [#219](https://github.com/MattoYuzuru/Mnema/issues/219) | Replay, practice, progress and retention |
| [#58](https://github.com/MattoYuzuru/Mnema/issues/58) | Session budget and completion behavior |
| [#221](https://github.com/MattoYuzuru/Mnema/issues/221) | Integrated acceptance and closure evidence |
| [#220](https://github.com/MattoYuzuru/Mnema/issues/220) | Related persistent local full-stack launcher |

## Acceptance traceability

| Acceptance contract | Owning delivery issue |
|---|---|
| `AC-STUDY-01`…`04` | #214 snapshot isolation; #215 assessed/cancel/unavailable behavior |
| `AC-STUDY-05`…`07` | #215 exactly-once/restart; #219 replay/practice/retention |
| `AC-STUDY-08` | #58 bounded session budget; #214 candidate generation |
| `AC-EVAL-01`…`05` | #215 deterministic evaluation/reducer; #218 choice/cloze evidence |
| `AC-MULTI-01`…`04` | #213 immutable bindings; #214 pinned presentation; P1 multi-assessed deferred |
| `AC-A11Y-01`…`03` | #216 authoring; #217 self-check/typed; #218 cloze/choice; #221 integrated verification |
| `AC-LEGACY-01` | #213–#219 implementation; #221 integrated dependency/route check |

Каждый slice проходит отдельный protected squash PR. Epic считается завершённым
только после integrated backend/frontend/browser verification на объединённом
`main`, обновления документации и закрытия всех P0 acceptance gates.
