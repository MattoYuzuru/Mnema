---
artifact:
  id: epic-77-refinement
  type: implementation-plan
  title: "Epic #77 AI layer refinement and delivery runs"
  status: accepted
  created_at: "2026-10-02"
  updated_at: "2026-10-04"
  owners: ["project-owner"]
  source_tasks: ["GitHub Epic #77"]
---

# Epic #77: AI-слой — refinement и прогоны

Принятый scope, границы и порядок поставки реактивированного Epic #77. Продуктовые
решения — [AI layer contract](../product/ai-layer-2026-10.md); архитектура —
[AI generation platform](../architecture/ai-generation-platform.md); промпты для
прогонов — [epic-77 prompts](./prompts/epic-77-ai-layer.md). Задачи оформляются по
[work item standard](./work-item-standard.md) и живут в
[Project #4](https://github.com/users/MattoYuzuru/projects/4).

## Результат эпика

На локальном full-stack пользователь: актуализированный стек; создаёт материалы из
заметок «На потом» и с нуля через composer; видит батч в Мастерской, правит блоки по
выделению, одобряет в колоду; получает упражнения для материалов с проверкой партии;
отвечает на объяснения текстом и голосом с прогрессивной семантической проверкой;
получает уведомления; видит статистику и хаб колоды; имеет usage-бар, paywall с
тирами, промокоды; озвучка и иллюстрации работают через порты с кэшем и caps. Всё
работает на stub-провайдере в CI и на реальных провайдерах локально по ключам из
окружения.

## Общие правила поставки

- Каждая задача — законченный пользовательский или операционный результат за 1–3
  agent-days, своя ветка, PR, полный quality gate, protected squash. Нет «backend
  готов, frontend сломан».
- Greenfield: fresh schema и миграции, без `/v2`, dual paths, compat-адаптеров.
  Rollback — protected revert + пересоздание disposable local DB.
- Capability gates fail-closed: флаг **и** настроенный адаптер; CI использует только
  Stub; live-тесты — opt-in Gradle-задача с ключами из локального окружения.
- Реальные пользователи и реальные данные — только после human/legal gate (H-01) и
  AI-17 (eval как gate).
- Доска: только поле `Status`; Backlog → Ready → In progress → In review → Done; Done
  — после merge и evidence-комментария в issue.

## Прогоны (5 запусков агента)

| Прогон | Задачи | Первый пользовательский результат |
|---|---|---|
| 1. Платформа и контракты | INFRA-01 стек; AI-00 docs/contracts/prompt library skeleton; H-01 human/legal (владелец) | Стек актуален; контракты и fixtures в репозитории |
| 2. Фундамент без AI | AI-01 usage ledger; AI-02 provider foundation; AI-03 MBM compiler; CONTENT-01 узел `code_block`; AI-07 центр уведомлений; AI-12 хаб колоды, статистика, multi-select, «Эталон»; AI-UI примитивы | Уведомления, хаб и статистика работают; оператор видит `aiGeneration: available` локально |
| 3. Первый вертикальный срез | AI-04 сессии/шаги; AI-05 approve/handoff/retention; AI-06 composer + Мастерская; AI-08 из «На потом» | Материал из запроса одобрен в колоду; батч из заметок |
| 4. Упражнения, проверка, правки | STUDY-01 перемешивание CHOICE; AI-13 генерация упражнений; AI-20 `ai-semantic`; AI-11 inline-правки; AI-16 диалог материала и правка существующего; AI-14 планировщик | Упражнения с ИИ, проверка объяснений, правки по выделению |
| 5. Медиа, голос, деньги, эксплуатация | INFRA-02 egress-шлюз; AI-10 поиск изображений; AI-09 TTS с кэшем; AI-15 STT; AI-18 веб-исследование; AI-17 эксплуатация и eval; AI-19 paywall/онбординг/Max-тизер; AI-21 промокоды/A/B/промо-попап | Полный AI-релиз на локальном стенде |

Зависимости: `INFRA-01 → AI-00 → {AI-01, AI-02, AI-03, CONTENT-01, AI-07, AI-12,
AI-UI} → AI-04 → AI-05 → AI-06 → {AI-08, STUDY-01, AI-13, AI-11} → {AI-20, AI-16,
AI-14} → INFRA-02 → {AI-10, AI-09, AI-15, AI-18} → {AI-17, AI-19, AI-21}`. AI-20 требует AI-02 и STT-путь из AI-15 только для
голосового ответа; текстовый путь — раньше.

### Статус прогонов

| Прогон | Статус | Evidence |
|---|---|---|
| 1 | Done 2026-10-02: #278 (#306, #308), #279; #280 — у владельца | [run-1](./evidence/epic-77/run-1/README.md) |
| 2 | Done 2026-10-03: #286 (#312), #283 (#313), #284 (#314, #317), #285 (#316, #318, #321), #303 (#315), #281 (#319), #282 (#320) | [run-2](./evidence/epic-77/run-2/README.md) |
| 3 | Done 2026-10-03: #287 (#324), #288 (#325), #289 (#326), #290 (#327) | [run-3](./evidence/epic-77/run-3/README.md) |
| 4 | Done 2026-10-04: #304 (#329), #291 (#330), #293 (#331), #292 (#332), #294 (#334), #295 (#338) | [run-4](./evidence/epic-77/run-4/README.md) |
| 5 | Код влит: #300 (#370, операции воркера и eval-evidence), #301 (#371), #302 (#372), публичные события (#377); #300 ждёт приёмки владельцем (калибровка судей, 144 метки оценивания), Done не выставлен | [run-5](./evidence/epic-77/run-5/ai-17/README.md) |

Изменено относительно плана: стек поднят двумя PR (frontend, backend) вместо семи; решения
владельца по usage (2026-10-02) записаны в [AI layer contract](../product/ai-layer-2026-10.md)
и [`contracts/usage`](../../contracts/usage/README.md); в контракте генерации упражнений лимит
сессии — 60 упражнений при 20 материалах (см. «Known doc conflicts» в
[`contracts/generation`](../../contracts/generation/README.md)).

Прогон 2 изменил относительно плана: `MEDIA_READY` не вводился (нет в контракте уведомлений); prompt
library v1 (`system.md`, `skills/code.md`) разрешает блоки кода — изменена на месте до первого
использования, заморозка v1 — с первой ревизии AI-04; в контракт usage добавлены `UsageLedger.renew`
(сессионный резерв для шагов, отложенных daily burst), вид окна в dedupe-ключе `USAGE_EXHAUSTED` и
`503 USAGE_UNAVAILABLE`; AUTO-усилие в estimate оценивается по худшему случаю (Free не стартует AUTO-сессию в
первую неделю — вопрос для AI-04/AI-06); массовое удаление больше 100 материалов идёт чанками с честным
`PARTIAL` (лимит 500); `ProblemExtension` в `platform.api` — общий механизм extension members.

Прогон 3 изменил относительно плана: `AUTO` для материалов оценивается, исполняется и списывается как `MEDIUM` до
планировщика (вопрос «Free не стартует AUTO в первую неделю» закрыт); bulk approve — ≤20 за команду (контракт), а
не 100; дочерние command id — name-based v4 (политика UUID допускает только v4/v7); «Вернуть» после последнего
отклонения переоткрывает закрытую сессию; архивация заметок не трогает заметки, закреплённые «живыми»
артефактами; источник-заметка закрепляется снимком текста (V29, заметки изменяемы на месте); добавлены
`learning.runtime.roles`, lifetime шага `PT1H` и курс `learning.generation.usd-rub-rate` для стоимости в ledger.

Прогон 4 изменил относительно плана: решения владельца 2026-10-03 — SLA проверки объяснений по архитектуре §11 (p50 ≤3 с,
p95 ≤8 с, «Оценить себя» на 5-й секунде, deadline 20 с), а не 5/30 с из issue; golden set проверки размечен агентом
(`status: proposed`), ревью владельца — вход gate AI-17; голос в `REVISE_EXERCISE` — модель + Stub-исполнитель (asset не
меняется), реальная озвучка и её acceptance перенесены в #297. Варианты CHOICE перемешиваются сохранённой при выдаче
перестановкой, а не seed от nonce (порядок не выводим из клиентских идентификаторов). Контекст генерации упражнений —
`<data_policy>` + `exercises.md` без полного L0–L2 префикса (`system.md` требует MBM-ответ). Re-pin упражнений — только когда
весь показанный модели текст не изменился. Правки по выделению: медиа внутри диапазона остаются на месте, цели — подряд
идущие блоки, revert — в пределах текущего поколения черновика, EDIT-шаги в приоритете с таймаутом очереди; правки
упражнений сессий `EXERCISES` по-прежнему запрещены (только REVISE_EXERCISE). Неуверенность грейдера (включая противоречие
только в одном из двух прогонов) — self-check, не result `UNSURE`; dispute восстанавливает и `last_assessed_at/next_due`.
Для ai-semantic сервер всё ещё требует TEXT `answerKey` (редактор выводит его из эталона) — follow-up. В v1 prompt library
добавлены новые секции (`intent`, `exercise-edit`, `plan`); выпущенные секции не менялись. Планировщик: батч-hold `PLAN_READY` истекает по TTL резерва (`plan.cost.holdActive`), Pro-план по тиру не
выбирается (только эскалация после невалидного плана); для промпта без заметок план расширяет hold при запуске.
Follow-up: #335 (база ревизии правки), #336 (ai-semantic без TEXT-ключа), #337 («Править самому» для REVISE_ITEM).
Тестовая инфраструктура: `MiddayUsageClock` убрал часовой флак generation-тестов перед полуночью МСК; harness запускается
под `caffeinate -d -i` (сон дисплея macOS подвешивает headless Chrome).

Прогон 5 — вводные владельца 2026-10-04 (комментарий в #77, заменяют результаты #280 как входные условия): добавлена первая
задача INFRA-02 (#340) — stateless HTTP CONNECT egress-шлюз на VPS владельца в Финляндии для провайдеров, недоступных из РФ
(данные, Learning и ключи остаются в РФ, шлюз видит только имена хостов; kill-switch и fallback обязательны); TTS (#297) — primary-
кандидат Google Gemini 3.8 Flash TTS через шлюз, сравнение с Yandex SpeechKit v1 для RU; STT (#298) — spike self-host (GigaAM-v3 /
Qwen3-ASR) против Gemini-транскрибации через шлюз, голос через шлюз только обезличенно и с согласием; изображения (#296) — без Pexels
(Pixabay, Openverse, Wikimedia Commons); веб-поиск (#299) — spike Yandex Search API против Brave/Perplexity через шлюз, без ключа —
Stub + recorded fixtures; текстовый fallback — OpenRouter (те же модели DeepSeek) вместо ненастроенного GigaChat. Задачи без ключей
завершаются на Stub и recorded fixtures с пометкой «live не проверено». Порядок: #340 → #296 → #297 → #298 → #299 → #300 → #301 → #302.

## Delivery slices

| № | Issue | Outcome | Входит | Не входит | Риски |
|---|---|---|---|---|---|
| INFRA-01 | Актуализировать стек | Java 25, Spring Boot 4.1 (Framework 7, Security 7, Jackson 3), Angular/Node latest, Gradle/Docker/CI images, линтеры и предупреждения; полный gate зелёный | обе службы, media-worker, frontend, CI, docs | новые фичи | Jackson 3 и Security 7 breaking changes; coverage |
| AI-00 | Docs и контракты | `contracts/generation/` (state machines, MBM v1 + golden fixtures, events, коды ошибок), `contracts/usage/`, `contracts/notifications/`, skeleton prompt library; навигатор docs | контракты, fixtures, prompt skeleton | runtime code | переспецификация — MBM v1 минимален |
| H-01 | Human/legal gate | уведомление РКН, disclosure-тексты, аккаунты провайдеров и ключи в окружении, решение по SpeechKit | действия владельца | код | блокирует реальные данные |
| AI-01 | Usage ledger | бар credits в профиле; reservation сверх лимита отклоняется; rate card v1; `EntitlementSource` (config); estimate/reserve/settle/release; `GET /api/usage` | таблицы, API, бар | платежи | гонки reservations |
| AI-02 | Provider foundation | порты, OpenAI-compatible adapter, routing, timeouts/retry/breaker, `ai_provider_call`, Stub, расширение `/api/capabilities` + frontend parser, prompt library v1 (скиллы), offline eval runner | `ai`, capability | UI генерации | утечка ключей в логи |
| AI-03 | MBM v1 compiler | MBM ↔ native-v1 детерминированно; golden fixtures; ошибки с позицией; link allowlist hook; каждый тест прогоняет `NativeDocumentReader` | `generation.mbm` | LLM | расхождение с reader |
| CONTENT-01 | Узел `code_block` | native-v1 `code_block {lang, source}` с лимитами как у mermaid, директива MBM, редактор/renderer/plain-text, fixtures; `math` — позже | content, editor, renderer | исполнение кода | opaque-совместимость |
| AI-07 | Центр уведомлений | durable контракт, cursor, retention, колокольчик, тосты (6/3 с, пауза, свайп, Study-quiet), producer для media processing | shell, API | push/email | шум — dedupe |
| AI-12 | Хаб колоды | `insights` endpoint + виджеты с таблицами; список с числом упражнений и сортировкой; «Эталон» ★; multi-select; bulk-удаление (hold с последствиями); форма за «Изменить» | хаб, API | AI | нагрузка projection |
| AI-UI | UI-примитивы | `SegmentedChoice`, `Toggletip`, `UsageMeter`, `.generate-cta` на существующих настройках | frontend | — | — |
| AI-04 | Сессии и шаги (текст) | по API: сессия из prompt, события, предложенные материалы; dispatcher; `TEXT_DRAFT` с checkpoints; отмена; тест «нет открытой транзакции во время вызова» | миграции, worker, API | approve, медиа | удержание соединений |
| AI-05 | Approve, handoff, retention | `GeneratedItemPublisher`; single + bulk approve; reject/undo; handoff в `EditingDraft`; удаление сессии; retention worker; provenance | API, worker | упражнения | обход CAS |
| AI-06 | Composer и Мастерская | от «Что будем учить сегодня?» до одобренного материала; preflight; пейджер; проявление блоков; approve/reject/approve-all/hold-delete; список активных сессий; a11y/mobile/reduced motion | frontend | медиа, правки | перегруженный экран |
| AI-08 | Из «На потом» | выбор N заметок → материалы; «материал на заметку / объединить»; per-note overrides; pinned версии; «Архивировать использованные (N)» | frontend + API | N:M conversion | изменённая заметка |
| STUDY-01 | Перемешивание CHOICE | `LearnerContent` перемешивает варианты CHOICE детерминированно по seed сессии; корректность по ID; replay воспроизводит порядок | study presentation | другие механики | ломка replay |
| AI-13 | Генерация упражнений | билдер; strict JSON per mechanic; lint; self-evaluation; reuse objectives; `GeneratedExercisePublisher`; `STALE` re-pin; экран проверки партии; метка «Новое» | полный срез | планировщик | ложные ключи |
| AI-20 | Семантическая проверка | `ai-semantic` реализует `SemanticAssessmentProvider`; рубрика CORE/DETAIL/TERM + misconceptions; грейдер с цитатами по критериям; строгость S1–S3 только на сервере; UNSURE провайдера → self-check; feedback с цитатами; dispute компенсирующей transition; p50 ≤3 с, deadline 20 с | evaluator, Study UI | произношение | leniency bias |
| AI-11 | Inline-правки | `data-node-id`; окно в панели выделения; `EDIT`; контекст под cache; сохранение IDs; diff; undo/redo; mobile bottom sheet | frontend + API | span-level | потеря выделения |
| AI-16 | Диалог материала и правка существующего | intent → spec-чипы → подтверждение; `REVISE_ITEM`/`REVISE_EXERCISE`; revise через `PUT` + `If-Match` | frontend + API | агентный чат | инъекции в бюджет |
| AI-14 | Планировщик | «Предложить план» по статистике → `PLAN_READY` → правка → запуск; стоимость видна | API + UI | автозапуск | дорогой вызов |
| INFRA-02 | Egress-шлюз | per-provider HTTP CONNECT-прокси (`egress=proxy`), basic auth, allowlist хостов на прокси, kill-switch; Squid-конфиг и runbook для VPS | конфиг адаптеров, `deploy/ai-egress-proxy/`, runbook | хранение данных/ключей на шлюзе; развёртывание VPS (владелец) | блокировка аккаунта провайдера — kill-switch и fallback |
| AI-10 | Поиск изображений | `::image mode=search`; Pixabay/Openverse/Wikimedia (Pexels убран 2026-10-04); сохранение файла; атрибуция; host allowlist + SSRF-тесты; 3–4 варианта | adapter + UI | генерация | лицензии |
| AI-09 | TTS с кэшем | spike Gemini 3.8 Flash TTS через шлюз против Yandex SpeechKit v1 для RU; `::audio`; staging в pipeline #76 (`origin=generated`); `generation_media_ref`; кэш по хэшу; sub-caps; «Озвучить заново» | adapter + UI | подкасты | стоимость |
| AI-15 | Speech-to-text | spike self-host (GigaAM-v3 / Qwen3-ASR) против Gemini через шлюз; `speech-inputs` (≤60 с, эфемерно); `TRANSCRIBE`; `SpeechToTextProvider`; fair-use bucket; VAD; rate limits; согласие; микрофон в composer/окне/«На потом»/Study-ответе | provider + UI | live STT | abuse; латентность |
| AI-18 | Веб-исследование | spike Yandex Search API против Brave/Perplexity через шлюз; `RESEARCH`; бюджеты по effort; allowlist ссылок; «Источники» | adapter + pipeline | post-hoc фактчек | стоимость |
| AI-17 | Эксплуатация и eval | роли `api`/`worker`; `NOTIFY`; глобальные дневные бюджеты; дашборд метрик; runbook; golden eval отчёт (O-06) как gate | ops | деплой | ложная уверенность |
| AI-19 | Paywall и онбординг | `/plans`; «Для чего вам Mnema?»; usage в профиле; Max-тизер за toggle; страница `/ai`; `entitlement_inbox` контракт; права из return URL запрещены тестом | frontend + API | T-Bank | — |
| AI-21 | Промокоды, A/B, промо-попап | модель промокодов, admin-scope endpoint, лимиты/анти-фрод/аудит; A/B-назначение и метрики; попап с частотными ограничениями | API + UI | админ-UI (отдельная задача) | фрод |

Отложено отдельными эпиками: генерация видео; генерация изображений; AI-оценка
произношения; админ-UI промокодов; «live»-STT.

## Acceptance эпика

- Все slices merged с evidence; интегрированный локальный full-stack сценарий S1–S8
  пройден в реальном браузере на desktop/mobile с клавиатурой и reduced motion.
- CI зелёный на Stub; live-eval отчёт приложен; golden eval показывает validity pass
  rate, repair rate, cost per accepted item, p50/p95 latency.
- Capability gates fail-closed; прямой API не обходит квоты и флаги; AI не пишет
  `StudyState`; REPLAY/PRACTICE без canonical effects.
- Docs и навигатор отражают фактическое поведение; `scripts/verify_docs.py` зелёный.
