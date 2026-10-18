---
artifact:
  id: onboarding-ob-e3-backfill
  type: evidence
  title: "OB-302: milestone backfill — idempotency and plans"
  status: current
  created_at: "2026-10-18"
  owners: ["project-owner"]
---

# Backfill вех по истории (OB-302)

Задача `onboarding.backfill` (`OnboardingBackfill`, `BackfillRepository`), включается `learning.onboarding.backfill.enabled`
(по умолчанию выключено). Проверка — real-DB тесты на Testcontainers PostgreSQL (JDK 25, Colima), 2026-10-18.

## Что доказано тестами

| Критерий OB-302 | Тест |
|---|---|
| Повторный запуск не меняет таблицу; прерванный продолжает с места и даёт ту же таблицу; ничего не объявляется (`notification` пуста) | `OnboardingBackfillJobIntegrationTest.aRunTwiceChangesNothingAnInterruptedRunEndsInTheSameTableAndNothingIsAnnounced` |
| Исполнитель доводит очередь до конца сам; второй воркер без advisory-lock ждёт с тем же курсором и ничего не пишет | `theExecutorRunsTheQueueToTheEndOnItsOwn`, `aWorkerThatDoesNotGetTheLockWaitsWithTheSameCursorAndWritesNothing` |
| План каждого выражения индексный (при `enable_seqscan=off` ни одного `Seq Scan`) — граница батча и слияние, первый прогон и повтор | `everyBatchStatementIsDrivenByAnIndexOnTheOwnersOfItsSource` |
| Работа батча зависит от числа владельцев, а не от размера источника | `theWorkOfABatchFollowsTheNumberOfOwnersAndNotTheSizeOfTheSource` |
| Условия каждой вехи повторяют живые writer'ы (практика/повтор/учебная колода/копии/материализация не засчитывают авторские вехи; заметка — стадии; упражнения — до 3; появление медиа) | `OnboardingBackfillSemanticsIntegrationTest` (10 сценариев) |
| Слияние с LIVE: прогресс не понижается, `achieved_at` LIVE сохраняется, `source` не меняется; LIVE после backfill работает и уведомляет | там же и `MilestoneRecorderIntegrationTest` |
| Планировщик ставит задачу один раз на версию реестра и только при включённом флаге | `OnboardingBackfillScheduleTest` |

Найдено и исправлено при интеграции: проверка «медиа появилось» для `MEDIA_IN_MATERIAL` в виде `NOT EXISTS` планировалась как
hash anti-join по всей `content_media_ref` (seq scan на каждый батч) — переписана как коррелированный `ARRAY(...)` по первичному
ключу родительской ревизии; тест планов это ловит.

## Время слияния батча (1000 аккаунтов, синтетика 3000 аккаунтов)

| Веха | мс | Веха | мс |
|---|---|---|---|
| GOAL_TOLD | 2,2 | OWN_EXERCISES | 3,8 |
| FIRST_DECK | 2,1 | AI_EXPLANATION | 1,2 |
| FIRST_SESSION | 2,8 | VOICE_ANSWER | 1,1 |
| OWN_MATERIAL | 3,0 | WORKSHOP_APPROVED | 1,2 |
| MEDIA_IN_MATERIAL | 1,9 | TWO_DAYS | 3,4 |
| CAPTURE_NOTE | 2,3 | COMMUNITY_COPY | 2,3 |

Граница батча — loose index scan по индексу владельцев источника (одна проба на владельца). Оценка для модели H (200k
аккаунтов): 200 батчей × 12 вех ≈ 2400 выражений по 1–4 мс плюс пауза `pause` (1 с) между slice по 3 с — порядка десятков минут
фоновой работы без длинных блокировок. Это экстраполяция, не измерение на объёме H.

## Что не восстанавливается

`VOICE_ANSWER` — только голосовые ответы с ИИ-оценкой (`study_assessment.answer_source='SPEECH'`); `speech_input` живёт 15 минут.
`AI_EXPLANATION` — `study_evidence` (SCHEDULED); практика после retention теряется. `CAPTURE_NOTE` — удалённые заметки.
`RHYTHM_WEEK` — нет истории до OB-401.
