---
artifact:
  id: epic-77-run-3-evidence
  type: evidence
  title: "Epic #77 run 3: first vertical slice"
  status: historical
  created_at: "2026-10-03"
  owners: ["project-owner"]
---

# Epic #77, прогон 3: первый вертикальный срез

Доказательство для конкретных commit'ов `main`; не заменяет текущий код и контракты.
Окружение: macOS arm64 (Colima), JDK 25.0.4.1 (Homebrew `openjdk@25`), Node 24.21.0
(`node@24`), Chrome 154; CI — GitHub Actions `ubuntu-latest`. Каждый PR прошёл полный локальный
gate (`backend ./gradlew clean quality bootJar`, `frontend npm ci/lint/test/build`, `verify_docs.py`) и
real-browser harness `scripts/browser-identity/run.py --authoring --media --mechanics` (с #289 —
`--generation`) на точном дереве; подробности — evidence-комментарии в issue.

**Первый пользовательский результат эпика:** материал из запроса проходит composer → Мастерская →
одобрение и появляется в колоде (Browse) — на Stub-провайдере в реальном браузере, реальный HTTPS,
Identity + Learning + PostgreSQL + MinIO.

## Задачи

| Задача | PR → squash в `main` | Итог |
|---|---|---|
| #287 AI-04 | #324 → `8f64237f` | V28 (10 таблиц генерации, holds для media GC); PostgreSQL-очередь шагов: `SKIP LOCKED`, lease + fencing token, heartbeat, backoff, дедлайн run `PT6M` и lifetime `PT1H`, graceful shutdown, роли `api/worker/all`; `TEXT_DRAFT` с бюджетами контекста, checkpoints `BLOCKS_APPENDED`, repair → strong → `FAILED(INVALID_OUTPUT)`; HTTP сессий, событий, артефактов, отмены |
| #288 AI-05 | #325 → `afdd430f` | Approve (одна транзакция с `ItemService.publish`), атомарный bulk ≤20, reject/undo (undo переоткрывает закрытую сессию), handoff в `EditingDraft`, retry с новой reservation, delete, retention (`EXPIRED` → purge, предупреждение за 3 дня), `archiveUsedNotes` |
| #289 AI-06 | #326 → `e4bfbb69` | Composer (`/decks/:id/materials/new`) и Мастерская (`/decks/:id/workshop/:sid`): preflight, IME-безопасный Enter, пейджер-статусы, polling событий, блоковое проявление, одобрение/отклонение/handoff/retry/approve-all/hold-delete, «Мастерская: N активных»; harness `--generation` |
| #290 AI-08 | #327 → `7ff8460f` | Multi-select «На потом», чипы заметок, группировка, настройки по заметке (`overrides`), снимки текста заметок (V29), `sourceRefs[].status` и «заметка изменилась», «Архивировать использованные заметки (N)» |

## Ключевые проверки

- **Нет DB-транзакции и занятого соединения во время вызова провайдера:** DataSource-обёртка считает
  занятые соединения потока; проверка на каждом вызове и стрим-дельте в happy path, repair, strong
  route и отмене (`GenerationSessionIntegrationTest`).
- Падение worker → lease истекает → шаг переисполняется; опоздавший worker с устаревшим токеном ничего
  не пишет (begin, checkpoint, succeed, fail, heartbeat); конкурентная выдача `seq` — 8 писателей и
  поллер без пропусков и дублей.
- Approve: повтор `commandId` не создаёт второй материал; bulk атомарен; неготовый слот →
  `MEDIA_NOT_READY`; media GC собирает asset только после снятия hold.
- Real-browser `workshop_composer_stub_real_api`: composer (preflight из реального estimate,
  Shift+Enter, синтетический IME Enter и keyCode 229 не отправляют), стриминг (`aria-busy`,
  `.is-arriving`, один `role=status`), «Стоп» → `CANCELLED`, уход/возврат/перезагрузка, одобрение →
  Browse, отклонить/вернуть (в том числе последнее), retry после правки заметки, handoff → публикация из
  редактора, «Одобрить все готовые (3)», hold-to-delete; заметки: 4 заметки → 4 материала, настройка
  одной заметки только в её spec, «заметка изменилась», архивация ровно трёх использованных и
  идемпотентный повтор, `MERGE_INTO_ONE` → 1 материал с 2 источниками.
- Отзывчивость: 1440/768/390/320 без горизонтального скролла, 320 при 2× тексте и 200 % zoom,
  reduced motion, forced colors, видимый фокус, основные цели ≥44 px. Скриншоты — PNG в этой папке.

## Независимые ревью

| Объект | Итог |
|---|---|
| #287 backend (sde reviewer) | 0 BLOCKER; 1 MAJOR (дедлайн и задержка повторов без верхней границы) + 8 MINOR (claim в `REVIEW`, порядок проверок, маленький `budgetPercent`, цена AUTO, ложный `GENERATION_FAILED`, shutdown/interrupt, слоты при отмене, пагинация) — исправлено |
| #288 backend (sde reviewer) | 0 BLOCKER; 1 MAJOR (архивация заметки делала соседние предложения STALE) + 3 MINOR — исправлено; решения по undo после последнего отклонения, лимиту при retry и портам catalog |
| #289 frontend (sde reviewer) | 2 MAJOR (replay без ETag ломал cancel/reject/retry; битое событие останавливало опрос) + 7 MINOR — исправлено |
| #290 frontend / backend (sde reviewer) | 1 MAJOR (не-привязка `placeholder`) + 6 MINOR; 1 MAJOR (повторная заметка: оценка ≠ генерация → 400) + 3 MINOR — исправлено |

Harness нашёл и до merge исправлены: английские месяцы в ссылках на Мастерские; строгий парсер
отклонял `sessionDetail.notes` после #288; опрос не возобновлялся после переоткрытия сессии;
CodeQL — no-op replace в spec.

## Отклонено / изменено относительно docs

- `AUTO` для материалов в этом релизе оценивается, исполняется и списывается как `MEDIUM` (до
  планировщика AI-14) — Free может стартовать AUTO-сессию в первую неделю (вопрос прогона 2 закрыт).
- `planFirst`, `EXERCISES`, `REVISE_*` → `422 SPEC_NOT_SUPPORTED`; аудио/поиск картинок недоступны до AI-09/AI-10.
- Bulk approve — ≤20 за команду (решение 5 контракта), а не 100 из текста #288.
- Дочерние command id — name-based v4 (SHA-256), а не UUIDv5: политика UUID принимает только v4/v7.
- «Вернуть» после последнего отклонения переоткрывает закрытую сессию (`CLOSED` больше не terminal).
- Архивация заметок не трогает заметки, ещё закреплённые «живыми» артефактами.
- Источник-заметка закрепляется снимком текста (V29), потому что заметки изменяемы на месте; правка
  заметки после закрепления больше не ломает шаг, а показывается как `CHANGED`.
- Стоимость провайдера (micro-USD) переводится в micro-рубли ledger по `learning.generation.usd-rub-rate`
  (85, информационно).

## Не проверено

- Live DeepSeek «20 глаголов движения ≤6 мин»: opt-in тест запускает владелец (ключ только в его
  окружении) — `MNEMA_AI_LIVE=true ./gradlew :services:learning:test --tests
  'app.mnema.learning.generation.GenerationLiveProviderTest'`.
- Screen reader, реальная IME-раскладка, Safari/Firefox, touch/swipe.
- Несколько dispatcher-инстансов одновременно (`SKIP LOCKED` проверен одним инстансом на контекст).
- Re-pin STALE по node IDs (не реализован), предупреждения о ПД в estimate, метрики accept ratio и
  reserved credits.
