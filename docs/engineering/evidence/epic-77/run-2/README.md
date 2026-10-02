---
artifact:
  id: epic-77-run-2-evidence
  type: evidence
  title: "Epic #77 run 2: foundation without AI"
  status: historical
  created_at: "2026-10-03"
  owners: ["project-owner"]
---

# Epic #77, прогон 2: фундамент без AI

Доказательство для конкретных commit'ов `main`; не заменяет текущий код и контракты.
Окружение: macOS arm64 (Colima), JDK 25.0.4.1 (Homebrew `openjdk@25`), Node 24.21.0
(`node@24`), Chrome 154; CI — GitHub Actions `ubuntu-latest`. Каждый PR прошёл полный локальный
gate (`backend ./gradlew clean quality`, `frontend npm ci/lint/test/build`, `verify_docs.py`) и
real-browser harness `scripts/browser-identity/run.py --authoring --media --mechanics` на точном
дереве; подробности — evidence-комментарии в issue.

## Задачи

| Задача | PR → squash в `main` | Итог |
|---|---|---|
| #286 AI-UI | #312 → `6becc350` | `SegmentedChoice`, `Toggletip`, `UsageMeter`, `.generate-cta`; показ в редакторе ответа |
| #283 AI-03 | #313 → `34a4ccdc` | Компилятор MBM v1 ↔ native-v1 (`generation.mbm`), рендер с handles, auto-fix, repair list |
| #284 AI-07 | #314 → `8d55cde4`; #317 → `cf5de985` | Центр уведомлений: V24, publisher в транзакции, API с ETag, колокольчик, «Входящие», тосты, тихий режим Study; producer — сбой медиа. #317 — закрытое уведомление больше не возвращается из запроса, бывшего в пути |
| #285 AI-12 | #316 → `28575592`; #318 → `41682fff`; #321 | Хаб колоды: insights, сортировка по числу упражнений, «Эталон» (V25), чанковое массовое удаление; маршрут `/decks/:id/materials` удалён. #318 — прогрев scale-теста; follow-up — планы запросов хаба без зависимости от статистики (холодный insights 2,1 с → ≈0,1 с на 10k) |
| #303 CONTENT-01 | #315 → `2cd3abc9` | `code_block {lang, source}` в native-v1, MBM, редакторе и renderer; ревалидация сохранённого opaque-узла |
| #281 AI-01 | #319 → `92455421` | Usage ledger (V26): reserve/settle/release/renew/expire, fair-use, `GET /api/usage`, estimate, `ProblemExtension`, блок «ИИ-бюджет» |
| #282 AI-02 | #320 → `53263f02` | Провайдеры: порты, OpenAI-compatible адаптер (DeepSeek, GigaChat), routing/fallback/breaker/budget, `ai_provider_call` (V27), 8 capabilities, prompt library loader, Stub, offline eval |

## Ключевые измерения

- MBM: все golden fixtures байт-в-байт; ≈8,5k property-входов без исключений вне контракта;
  враждебный ввод 256 KiB отклоняется ≤150 мс.
- Insights хаба на 10 000 материалов / 30 000 упражнений: 0,71–0,79 с при merge #316; после follow-up
  (свежие таблицы без статистики) — 77–121 мс холодно, 69–83 мс прогрето; первая сортированная страница — 117–154 мс
  и заполняет превью только своих 25 записей.
- Usage: две параллельные reservation на остаток для одной → ровно один успех и один
  `409 USAGE_LIMIT_REACHED` (детерминированная гонка через barrier, 12 гонщиков, HTTP).
- Eval на Stub (`MNEMA_AI_EVAL=stub`): 99 fixtures, первая валидность 81,3 %, repair 18,8 %,
  итоговая 100 %. Live DeepSeek — opt-in тест владельца (результат — комментарий в #282; ключ проверен: `GET /models` → 200).

## Независимые ревью

| Объект | Итог |
|---|---|
| #283 MBM (sde reviewer) | 1 MAJOR (рендер терял inline `lang`/`dir`, future-version узлы, attrs) + 5 MINOR — исправлено |
| #282 провайдеры (security) | 2 MAJOR (квадратичный regex e-mail в Redactor; нет таймаута до первого байта → fallback не срабатывал) + 6 MINOR — исправлено |
| #281 usage (sde reviewer) | 3 MAJOR (потеря `USAGE_EXHAUSTED` последнего окна Free; тело estimate читалось в транзакции; резерв без продления) + 7 MINOR — исправлено |
| #285 bulk delete (архитектор) | замечаний нет |

Real-browser harness нашёл и до merge исправлены: ловушка клавиатуры в блоке кода (#303),
появление закрытого уведомления (#284/#317), обрезка рамки фокуса при переходе по якорю (#281),
ширина хаба и кнопки удаления (#285).

## Отклонено / изменено относительно docs

- `MEDIA_READY` не добавлен (нет в принятом контракте уведомлений; шум в редакторе).
- Prompt library v1 (`system.md`, `skills/code.md`) изменена на месте, чтобы разрешить блоки кода:
  v1 ещё не загружался кодом; заморозка — с первой ревизии AI-04.
- `UsageLedger.renew` добавлен к контракту reservation (сессионный резерв для шагов, отложенных
  daily burst); AUTO-усилие в estimate оценивается по худшему случаю.
- Ключи dedupe `USAGE_EXHAUSTED` включают вид окна (контракт уточнён).
- Массовое удаление >100 материалов — чанками с честным `PARTIAL`; лимит 500.

## Не проверено

- Screen reader, touch-устройства, Safari/Firefox, forced colors визуально.
- GigaChat вживую (только записанные fixtures; finish reasons `blacklist`/`error` не сверены с
  документацией), OpenRouter вживую.
- Колоды на 100 000 материалов; холодная страница из 100 материалов заполняет 100 превью в одной
  транзакции.
