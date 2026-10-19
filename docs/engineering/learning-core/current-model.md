---
artifact:
  id: learning-core-source-audit
  type: research-evidence
  title: "Learning core source map at local epic4"
  status: historical
  created_at: "2026-10-13"
  updated_at: "2026-10-13"
  owners: ["project-owner"]
---

# Что уже есть в core

Снимок кода 2026-10-13, не production-проверка: main `ae556ece` и ветка эпиков Сообщества 3–4
`f4f5e636` (оба уже в main; последняя миграция Learning теперь V76). Перед реализацией сверить пути со свежим main.

## Сущности и путь ответа

`LearningItem` локален в колоде и содержит immutable revisions. `MemoryObjective` —
отдельно проверяемая цель этого материала. Несколько упражнений могут использовать
одну цель (`objective.operation=reuse`); разные навыки требуют разных целей.
Objective сейчас содержит identity и короткое название, а не полноценную онтологию навыков.

ExerciseRevision фиксирует subject/item revision, цель, content, ключ и evaluator.
Presentation фиксирует выданный вопрос и nonce. Attempt идемпотентен и получает
feedback/evidence; versioned reducer меняет StudyState только в SCHEDULED.
Контекст, варианты и части composite exercise не получают чужой progress.

Источники: [Study](../../../contracts/study/README.md),
[AttemptEvaluation](../../../backend/services/learning/src/main/java/app/mnema/learning/study/attempt/AttemptEvaluation.java),
[AttemptConclusion](../../../backend/services/learning/src/main/java/app/mnema/learning/study/attempt/AttemptConclusion.java).

## Проверки и «дешёвый classifier»

Реализовано семь механик: SELF_CHECK, FREE_RESPONSE, CLOZE, CHOICE, MATCH, ORDER, CATEGORIZE.
FREE_RESPONSE проверяется deterministic текстовыми правилами либо `ai-semantic` при доступной capability.
AI уже классифицирует критерии rubric: MET/PARTLY/NOT_MET/CONTRADICTED/UNCLEAR;
Java `SemanticPolicy` агрегирует их в COMPLETE/PARTIAL/INSUFFICIENT либо uncertainty fallback.
CORE/DETAIL/TERM и misconceptions отражают содержание, не красоту или длину ответа.

S1 допускает мягкое знакомство, S2/S3 повышают требования. S1 использует один model run,
S2/S3 — два; disagreement ведёт к самооценке. AI evidence S1=LOW, S2/S3=MEDIUM, никогда HIGH.
Это уже дешёвый provider-based grading path, но не обученный локальный JVM classifier.
Согласие двух прогонов не равно независимой человеческой валидации.

[SemanticPolicy](../../../backend/services/learning/src/main/java/app/mnema/learning/study/attempt/SemanticPolicy.java),
[strictness](../../../backend/services/learning/src/main/java/app/mnema/learning/study/attempt/SemanticStrictness.java),
[golden set](../../../contracts/study/assessment-golden/README.md),
[live evidence и proposed labels](../evidence/epic-77/run-5/ai-17/README.md).
144 synthetic answers и хороший QWK — полезная проверка pipeline; labels ещё требуют human review.
Разные требования S1/S3 нельзя анализировать как одну неизменную шкалу знаний.

## Расписание и выбор следующего упражнения

[BaselineReducer](../../../backend/services/learning/src/main/java/app/mnema/learning/study/attempt/BaselineReducer.java)
использует level 0…7 и фиксированные интервалы 10m/4h/1d/3d/7d/14d/30d/60d.
CORRECT HIGH повышает на 2; MEDIUM на 1 до 6; LOW на 1 до 3.
Сильная ошибка сбрасывает до 0. Фактический elapsed interval и индивидуальная
трудность не участвуют в переходе. Это baseline, не калиброванная memory model.

SCHEDULED: due → introduced/unassessed → bounded new. Одна цель выдаётся не больше раза
в сессии. Для due цель выбирает первый eligible вариант по candidate ordinal; bounded
window/seed не дают гарантии содержательной смены вариантов. В epic4 ordinal относится
к manifest roster, а не к глобальной случайной выборке. PRACTICE может быть shuffle/weakest-first.
Новый selector должен сохранить bounded reads, pinned revisions и ACL.

## Progress уже учитывает цели, а не количество заданий

[Progress repository](../../../backend/services/learning/src/main/java/app/mnema/learning/study/progress/StudyProgressRepository.java)
считает DISTINCT objectives enabled head exercises. API возвращает enabled/introduced/assessed,
lastAssessedAt, nextDue и состояние материала.
ON_TRACK требует все enabled цели введёнными, assessed, level≥2 и future-due;
это не доказательство глубокого освоения. Дубликат упражнения на ту же цель не меняет denominator.
UI не обязан показывать сырые названия внутренних objectives.

Риск «два лёгких задания подтвердили всё» относится к качеству/полноте самих целей и
измерений. Деление на число упражнений его не исправляет. Auto-generation reuse по
нормализованному title — heuristic, не подтверждение смысловой эквивалентности целей.

## Retention и восстановимость — важное отличие нового среза

| Факт | b45cc99a | f4f5e636 / cached main после #460 |
|---|---|---|
| Scheduled evidence/transitions/receipt | до удаления аккаунта | сохранено |
| Scheduled raw response | 30 дней | сохранено |
| Practice/replay compact receipt | 24 часа + tombstone | сохранено |
| Presentations/exposures/hints/pair checks/AI state | в старом checkout нет нового horizon | cleanup default P7D, minimum P5D от start |
| Первая попытка exercise для strictness | выводилась по snapshots | отдельный study_exercise_attempt, backfilled V59 |

V59 snapshot cleanup нельзя отключать как обход отсутствующего learning ledger.
Scheduled evidence сохраняет objective/revision/result/evaluator/hints/time, но не точный
exerciseId/exerciseRevision/mechanic. После удаления presentation join к точному упражнению
теряется. Receipt с presentationId и суммарный exercise counter не восстанавливают event provenance.
Это уточняет прежние ответы в чате: старые scheduled ответы полезны для начальной оценки
памяти, но недостаточны для полного variant/criterion analysis.

В новом срезе ASSESSED PRACTICE/REPLAY увеличивают per-exercise attempt counter, который
влияет на cap первой AI-проверки; они всё равно не дают StudyState/due/streak credit.
Community interests получают некоторые PRACTICE session signals; engagement считает
completed SCHEDULED. Ни то ни другое не является learning-efficacy dataset.

Нормализованные observation records должны переживать snapshot cleanup. Старые пропуски
маркируются unknown; нельзя задним числом приписывать упражнение по текущему каталогу.

## Изменения контента и переносы

Правка ответа/цели сохраняет StudyState, restart создаёт новый learningEpoch.
Копии/обновления сообщества используют lineage/reuse scope, pinned effective snapshots
и private overrides; новый event должен сохранять именно выданную identity/revision,
не текущую source head и не прогресс автора. Нет cross-deck mastery entity.

## Границы полноты аудита

Изучены contracts, reducers/evaluation/selection/progress/retention, migrations,
canonical docs и локальные handoff. История реальных учеников, provider admission,
production flags и польза на аудитории не проверялись. Состояние каждого будущего
issue требуется перепроверить на свежем source head перед реализацией.
