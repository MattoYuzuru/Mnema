---
artifact:
  id: epic-77-run-4-evidence
  type: evidence
  title: "Epic #77 run 4: exercises, assessment, edits"
  status: historical
  created_at: "2026-10-04"
  owners: ["project-owner"]
---

# Epic #77, прогон 4: упражнения, проверка, правки

Доказательство для конкретных commit'ов `main`; не заменяет текущий код и контракты.
Окружение: macOS arm64 (Colima), JDK 25.0.4.1 (Homebrew `openjdk@25`), Node 24.21.0 (`node@24`),
Chrome 154; CI — GitHub Actions `ubuntu-latest`. Каждый PR прошёл полный локальный gate (`backend ./gradlew
clean quality bootJar`, `frontend npm ci/lint/test/build`, `verify_docs.py`, тесты скриптов) и real-browser harness
`scripts/browser-identity/run.py --authoring --media --mechanics --generation` (с #292 — `--assessment`) на точном
дереве, независимое sde-ревью backend и frontend (все MAJOR/MINOR исправлены до merge), зелёный CI; подробности —
evidence-комментарии в issue.

**Пользовательский результат прогона:** упражнения с ИИ для выбранных материалов с проверкой партии и меткой
«Новое»; семантическая проверка объяснений с прогрессивной строгостью; правки по выделению в Мастерской;
«Попросить Мнему…» для существующих материалов и упражнений; план перед генерацией.

## Задачи

| Задача | PR → squash в `main` | Итог |
|---|---|---|
| #304 STUDY-01 | #329 → `643d7ffb` | Варианты CHOICE перемешиваются при выдаче безопасным источником и сохраняются с presentation (чтение и REPLAY — тот же порядок, новая сессия — новый); оценка по `optionId` |
| #291 AI-13 | #330 → `9024feda` | Сессии `EXERCISES`: strict JSON → schema → lint (все 7 механик, + `MECHANIC_NOT_ALLOWED`) → compile → `ExerciseCommand.readCreate` → self-evaluation через `AttemptEvaluation`; repair → strong → `FAILED`; approve через `GeneratedExercisePublisher` с reuse objective по названию; re-pin без модели; «Новое» (V30, TTL 7 дней); билдер и экран проверки партии |
| #293 AI-11 | #331 → `9ab57bcd` | `POST …/edits` (REWRITE с пресетами, FREE, REMOVE_MEDIA), шаг `EDIT` с сохранением node ID и байтовой неизменностью соседей, `409 EDIT_IN_PROGRESS`, revert в пределах текущего черновика, V31; окно «Попросить Мнему…», нижняя панель на телефоне, diff, история |
| #292 AI-20 | #332 → `4efa35d8` | Рубрика v1 (CORE/DETAIL/TERM), грейдер DeepSeek с цитатами, политика `ai-semantic-v1` (S1–S3 на сервере, `HIGH` никогда), асинхронная проверка с deadline 20 с и «Оценить себя», dispute компенсирующей transition (V32), golden set 144 ответа (`proposed`) |
| — | #333 → `175d424b` | Часовой флак `parkedSession` (тесты падали за 10 мин до полуночи МСК) → `MiddayUsageClock`; гонка в тесте таймаута очереди правок |
| #294 AI-16 | #334 → `43f2f932` | `generation-intents` (бесплатно, 30/ч, клампинг на сервере), `REVISE_ITEM` (revise с `If-Match`, `NativeRevisionPlanner`), `REVISE_EXERCISE` (конвейер #291, голос на Stub-исполнителе), V33; «Попросить Мнему…» в профиле материала и редакторе упражнения |
| #295 AI-14 | #338 → `3c65b4a7` | «Сначала показать план»: `PLANNING` → шаг `PLAN` (Flash с thinking, эскалация на Pro) → `PLAN_READY` → правка плана → `plan-approval` создаёт ровно запланированные артефакты через обычный admission; план списывается отдельно (`SMART_PLAN_FLASH`, кап «Умный план»), V34 |

## Ключевые проверки

- **Live DeepSeek, генерация упражнений** (`GenerationExercisesLiveProviderTest`, opt-in): 5/5 упражнений прошли
  весь конвейер за 6,2 с, механики CHOICE/ORDER/CLOZE/SELF_CHECK/MATCH, 2 вызова (1 repair), ≈0,0028 USD.
- **Live DeepSeek, проверка объяснений** (`SemanticEvalRunner`, 12 упражнений × 12 ответов, разметка агента `proposed`):

  | Строгость | Согласие | QWK | Self-check | Мягче разметки | Строже разметки |
  |---|---|---|---|---|---|
  | S1 | 0,903 | 0,983 | 4,2 % | 0 % | 3,1 % |
  | S2 | 0,854 | 0,959 | 8,3 % | 0,8 % | 4,8 % |
  | S3 | 0,806 | 0,901 | 8,3 % | 1,6 % | 9,6 % |

  False-accept вне темы («рецепт блинов»), набора терминов, заблуждения, инъекции и многословно-неверного — 0 %.
  Латентность: один прогон p50 1,42 с / p95 1,95 с; пара прогонов (S2/S3) p50 1,48 с / p95 1,95 с — в пределах SLA
  архитектуры §11 (p50 ≤3 с, p95 ≤8 с). 432 вызова без ошибок, ≈990 micro-USD на ответ, доля cache hit 75 %.
- **Нет DB-транзакции во время вызова провайдера** — проверка расширена на шаги упражнений, `EDIT`, `ASSESS`, `PLAN` и intent.
- **Инъекции в бюджет** (#294): модель не выбирает цель, бюджет и количество сверх лимитов; конкурентные вызовы intent
  не превышают часовой лимит.
- **Харнесс** (Stub, реальный HTTPS, Identity + Learning + PostgreSQL + MinIO): сценарии `exercises.mjs`,
  `selection-edits.mjs`, `assessment.mjs`, `ask-mnema.mjs`, `planner.mjs`; с #294 harness запускается под
  `caffeinate -d -i` (сон дисплея macOS подвешивает headless Chrome).
- **Live DeepSeek, планировщик** (`GenerationPlanLiveProviderTest`, opt-in): `PLAN_READY` за 3,6 с, один вызов (JSON-режим
  вместе с thinking работает), 3 пункта / 9 упражнений, ≈1040 micro-USD.

## Скриншоты

| Срез | Файлы |
|---|---|
| Упражнения с ИИ (#291) | `exercises-builder-1440.png`, `exercises-builder-390.png`, `exercises-review-1440.png`, `exercises-review-390.png`, `exercises-saved-1440.png`, `exercises-study-new-1440.png` |
| Правки по выделению (#293) | `workshop-edit-window-1440.png`, `workshop-edit-rewriting-1440.png`, `workshop-edit-diff-1440.png`, `workshop-edit-sheet-390.png`, `workshop-edit-strip-390.png` |
| Проверка объяснений (#292) | `assessment-rubric-editor-1440.png`, `assessment-assessing-1440.png`, `assessment-assessing-390.png`, `assessment-result-complete-1440.png`, `assessment-result-complete-390.png`, `assessment-result-partial-1440.png`, `assessment-self-check-1440.png`, `assessment-disputed-1440.png` |
| «Попросить Мнему…» (#294) | `ask-open-1440.png`, `ask-chips-1440.png`, `ask-chips-390.png`, `ask-revise-item-result-1440.png`, `ask-revise-exercise-result-1440.png` |
| План (#295) | `planner-builder-1440.png`, `planner-ready-1440.png`, `planner-edited-1440.png`, `planner-edited-390.png`, `planner-launched-1440.png` |

## Не проверено

- Screen reader / VoiceOver, Safari и Firefox, реальная IME (только синтетическая композиция); Shift+F10 в окне правки —
  только компонентные тесты.
- Голосовые ответы и STT (AI-15), реальная озвучка в REVISE_EXERCISE (AI-09, на Stub asset прежний).
- Live-разбор intent (#294) на DeepSeek.
- Разметка golden set проверки — агентская; κ с владельцем — gate AI-17.
- Несколько dispatcher-инстансов одновременно.
