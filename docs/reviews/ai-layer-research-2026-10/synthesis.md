---
artifact:
  id: synthesis-research-2026-10
  type: research
  title: "AI layer research synthesis (2026-10-01)"
  status: historical
  created_at: "2026-10-01"
  owners: ["project-owner"]
---

# AI-слой Mnema: итоговый синтез ресерча (2026-10-01)

Статус: proposal для решения владельца, не принятое решение и не разрешение на реализацию. Факты о коде — из `origin/main` `9d462f7b` (после #266, #267, #270). Внешние факты — официальные источники, доступ 2026-10-01. Подробные отчёты с источниками:

- `research-architecture.md` — архитектура, домен, оркестрация, формат, безопасность, декомпозиция (10,8 тыс. слов).
- `research-economics.md` — цены шести категорий провайдеров, модель credits, тарифы, paywall, legal (7,8 тыс. слов, 112 ссылок).
- `research-ux.md` — экраны, компоненты, a11y, CSS-змейка, статистика, уведомления, paywall, имя (6,4 тыс. слов).

Юридические выводы ниже — не консультация; каждое «проверить с юристом» означает именно это.

---

## 0. Коротко: десять главных выводов

1. **AI-слой живёт внутри `services:learning`** как модули `generation`, `ai`, `usage`, `notification`; независимое масштабирование и изоляция ключей — через роль процесса одного jar (`api` / `worker` / `all`). Отдельный `services:ai` отклонён: он потребовал бы доступа к owner-scoped контенту «от имени» пользователя после истечения токена и распределённой публикации.
2. **Ваша интуиция про «сессию на материал» верна**: модель — `GenerationSession` («Мастерская», батч) → N × `GenerationArtifact` (нить одного материала/упражнения с собственными immutable-ревизиями и журналом инструкций). Approve вызывает существующие `ItemService`/`ExerciseService` в той же транзакции; CAS, receipts, 412 работают как сейчас. AI-черновики — свои таблицы, не `EditingDraft`.
3. **Модель пишет не native JSON, а MBM** (Mnema Block Markup: подмножество Markdown + директивы медиа-слотов), который детерминированно компилируется в native-v1 и проходит тот же `NativeDocumentReader`, что и публикация. Упражнения — strict JSON per mechanic + семантический lint + self-evaluation тем же evaluator, что в Study. У модели нет agency: её вывод — данные.
4. **Прогресс в UI — события блоками, не токены**: курсор событий + adaptive polling (как решение #76), «проявление чернилами» ≤600 мс, при reduced-motion — сразу. Token-streaming и SSE/WebSocket в v1 не нужны; контракт событий позволяет добавить streaming-endpoint позже.
5. **Текст почти ничего не стоит** (средний материал ≈0,8–0,95 ₽ в худшем случае на DeepSeek V4.1 Flash). Маржу определяют медиа, Free-аудитория и легальный доступ к провайдерам. Видео — не включать на старте (5 с = 13–200 ₽ ≈ 20–200 материалов). Изображения на старте — поиск по лицензионным стокам (≈0,05 ₽), генерация — позже в старших тирах.
6. **Один usage-бар на создание + fair-use на голос/проверку + штучные caps на дорогие медиа** (вариант C). Пользователь видит проценты и «≈N материалов», не рубли и не токены. Внутри — credits (1 credit = 0,10 ₽ p95-себестоимости), versioned rate card, reserve → settle → release.
7. **449 / 990 / 1 900 ₽ проходят guardrail ≤25%** переменных затрат с НПД и эквайрингом: бюджет AI ≈80 / 178 / 341 ₽ → ≈12–17 / 29–39 / 63–85 «материалов с упражнениями и озвучкой» в месяц. Free: 50 credits/мес, 30 мин STT, 50 AI-проверок (≈1,2–1,5 ₽/MAU ожидаемо). Max запускать, когда готовы TTS и изображения.
8. **Бесплатный STT всем — да, как fair-use 30 мин/мес**, предпочтительно self-host faster-whisper в РФ (голос — ПД; у US/EU-API санкционные оговорки); Groq/Voxtral — fallback после legal OK.
9. **Два блокера вне кода.** (a) Хостинг в Финляндии для российских пользователей противоречит принятому решению «инфраструктура в РФ» и ч. 5 ст. 18 152-ФЗ (с 01.07.2025), а Hetzner с 2024 г. расторгает договоры с клиентами с российскими адресами; финский сервер не делает OpenAI/Google/ElevenLabs разрешёнными. (b) Spring Boot 3.5.x вышел из OSS-поддержки 30.06.2026 (проект на 3.5.16); нужна отдельная миграция на 4.1. Оба не блокируют разработку на stub-провайдере, но блокируют production с реальными данными.
10. **Google TTS отвергнут** (недоступен российскому ИП; качество Chirp 3 HD среднее — #57/92). Google как таковой не вариант без иностранного юрлица. Primary LLM — direct DeepSeek V4.1 Flash (non-thinking; thinking теперь включён по умолчанию — выключать явно), эскалация — V4 Pro, RU-fallback — GigaChat через cloud.ru.

---

## 1. Где проект сейчас и что уже готово для AI

| Факт | Следствие для AI-слоя |
|---|---|
| Learning — modular monolith: `JdbcClient`, command receipts, row-version CAS, RFC 9457, virtual threads включены | Новые модули следуют тем же примитивам |
| #266: fail-closed capability gate `GET /api/capabilities` → `{aiAssessment, speechToText}` = `{available, reason}`; доступно только при `flag && provider bean`; seams `SemanticAssessmentProvider.assess(rubric, response)`, `SpeechToTextProvider.transcribe(assetId)`; публикация `ai-semantic`/`TEXT_OR_SPEECH` без capability → 409 `CAPABILITY_UNAVAILABLE` | Расширять этот контракт (`aiGeneration`, `textToSpeech`, `imageSearch`, `imageGeneration`, `webSearch`, позже `videoGeneration`), а не строить второй. Frontend-парсер требует точный набор ключей — backend и frontend меняются одним PR |
| На `main` 5 механик (`SELF_CHECK`, `FREE_RESPONSE`, `CLOZE`, `CHOICE`, `MATCH`); `ORDER`/`CATEGORIZE` — #268 In progress | Генератор берёт список механик из серверного registry, не из захардкоженного «7» |
| Media #76: durable jobs внутри процесса Learning (`@Scheduled` scan + virtual threads + Docker FFmpeg без сети), lease/token/heartbeat/backoff, состояния `PENDING_UPLOAD → VERIFYING → PROCESSING → READY`, polling с backoff + refresh on focus | Готовый прецедент in-process worker; сгенерированные байты идут через тот же pipeline с новым `origin=generated` |
| `media_asset.origin ∈ {upload, recording, import}`; `unattached-ready-hold=P7D`; two-scan GC | Новый hold-источник `generation_media_ref`; новой политики GC не нужно |
| `CaptureItemPublisher`: capture conversion публикует через порт с completion в той же транзакции; bulk publication до 100 changes | Approve AI-материала — тот же паттерн; «Одобрить все» — bulk |
| `EditingDraft`: 30 дней, ≤200/аккаунт, ≤1 MiB, только native document | Цель handoff «Править самому», не хранилище AI-артефактов |
| Каждый приватный запрос Learning синхронно проверяет Identity `/userinfo` (≤32 параллельно, 2 с) | Частота polling = нагрузка на Identity; один цикл опроса на сессию, не на артефакт |
| nginx локального launcher: `/api` с `proxy_read_timeout 10s`, cookie вырезаются | Long-lived streams потребовали бы отдельного `location`; `EventSource` c bearer невозможен |
| Renderer не выводит node IDs в DOM | Inline-правка требует режима аннотации блоков `data-node-id` |
| Learning не получает сигнал об удалении аккаунта | Каждый запуск фоновых шагов ограничен по времени (`PT1H`) |
| Economics-док (август): DeepSeek V4 Flash $0.44/$1.32 peak | Устарел: V4.1 Flash $0.30/$1.20 peak, $0.15/$0.60 off-peak, peak только пн–пт |
| Spring Boot 3.5.16 | OSS-поддержка 3.5.x закончилась 2026-06-30 (api.spring.io, проверено); актуальная OSS-ветка 4.1.x до 2027-07-31 |
| Local checkout отстаёт от `origin/main` на 11 коммитов, есть незакоммиченные правки | Не трогал; факты брал из read-only worktree |

---

## 2. Видение владельца как продуктовая модель

**Три слоя + сквозные механизмы**

| Слой | Что входит | Статус рекомендации |
|---|---|---|
| L1 Генерация | материалы (из «На потом», с нуля через composer), упражнения (из колоды, из multi-select, из профиля материала), правки существующего (материал/упражнение) по выделению или инструкции | ядро первого AI-релиза |
| L2 Приём данных | speech-to-text в composer, popover правки, «На потом», позже — голосовой ответ в Study (seam уже есть) | fair-use бесплатно; self-host в РФ предпочтительно |
| L3 Медиа | озвучка (TTS с кэшем), изображения (поиск по стокам → генерация позже), веб-поиск для фактчека по effort, видео | TTS и поиск картинок — в первом релизе за флагами; генерация картинок — Pro/Max; видео — отложено |
| Сквозное | Мастерская (батч + превью + правки), usage/credits + paywall, центр уведомлений, статистика колоды, multi-select/bulk actions, маркировка «ИИ» | уведомления, хаб колоды, статистика и UI-примитивы можно начинать без AI |

**Сценарии** (пользовательские истории, которые станут acceptance в эпике)

- S1 Урок корейского → 10 заметок «На потом» → чекбоксы → composer с настройками → батч материалов в Мастерской → одобрение по одному или «все готовые».
- S2 Пустой материал → composer «Юзуру, что будем учить сегодня?» → превью → одобрить / править самому / оставить в Мастерской / удалить (hold 3 с).
- S3 Колода с материалами без упражнений → статистика покрытия → «Упражнения с ИИ» → билдер (механики, приоритет, количество / Auto / % бюджета) → фон → уведомление → экран проверки партии → «Сохранить выбранные (14)» → метка «Новое» → сессия.
- S4 Профиль материала → «Попросить Мнемозину…» → «все типы по 3» → план-чипы → подтверждение → партия → редактор #267 или AI-правка.
- S5 Выделил абзац → popover (пресеты + поле [+ микрофон при STT]) → «слишком сложно» → блок переписан на месте → «Показать изменения / Оставить / Вернуть / Ещё раз».
- S6 Multi-select материалов → нижняя панель → «Упражнения с ИИ для выбранных» / «Удалить выбранные · 7» (hold).
- S7 Paywall `/plans`: Free выбран, рекомендуемый тир выделен по цели из онбординга, одна шкала AI-бюджета + отдельный счётчик колод, неотмеченная галочка автопродления, отмена в один клик.
- Не входит: AI-редактирование колоды целиком; удаление чего-либо по инициативе AI; свободный агентный чат с инструментами.

---

## 3. Архитектура: решения и обоснования

### 3.1 Размещение и границы

```text
app.mnema.learning.generation        сессии, артефакты, ревизии, шаги, approve, HTTP, events
app.mnema.learning.generation.mbm    чистый компилятор MBM ↔ native-v1 (без Spring)
app.mnema.learning.ai                порты capability, routing, adapters, stub, provider_call log
app.mnema.learning.usage             rate card, allowance, reservations, ledger, estimate
app.mnema.learning.notification      durable уведомления (общие)
app.mnema.learning.capability        существующий gate, расширяется
```

`generation` зависит от портов `catalog` (publish/read), команд `media`, `ai`, `usage`, `notification`. `catalog`, `study`, `media` никогда не зависят от `generation`. Порты публикации принадлежат вызывающей стороне, как `CaptureItemPublisher`. Правило content-platform «workers пишут только integration-owned таблицы или вызывают application command» соблюдается. Роли процесса: локально `all`; при разделении `api` без ключей провайдеров, `worker` — только actuator наружу и ключи в env.

Выделять настоящий `services:ai` — только по измеренным триггерам (worker-нагрузка бьёт по p95 API при раздельных процессах; другая security-зона для ключей; провайдеры из другой юрисдикции; независимый release cadence).

### 3.2 Доменная модель

| Сущность | Назначение | Мутабельность |
|---|---|---|
| `generation_session` («Мастерская») | один запуск в колоде: `kind` (`MATERIALS`, `EXERCISES`, `REVISE_ITEM`, `REVISE_EXERCISE`), immutable `spec`, state, reservation | state/pointers под CAS |
| `generation_session_source` | закреплённые источники: заметка + её `row_version`, материал + `item_revision_id`, prompt; роль `SOURCE` / `STYLE_EXAMPLE` | immutable |
| `generation_artifact` | нить одного будущего материала/упражнения: `target_kind`, `ordinal`, `state`, `current_revision_id`, `published_ref` | state/pointers под CAS |
| `generation_artifact_revision` | снимок предложения: payload (native-v1 или exercise command без deck-IDs), `handles` (handle → nodeId), `cause` (`INITIAL`/`EDIT`/`MEDIA`/`REPIN`), `prompt_version`, `model_route`, `validation`; ≤30 на артефакт | immutable |
| `generation_artifact_turn` | журнал инструкций пользователя (≤2000 символов, текст или результат STT), целевые node IDs, результат | immutable |
| `generation_media_slot` / `generation_media_ref` | медиа-место в AST с заранее выделенным `assetId`; hold для GC как `draft_media_ref` | под CAS / удаляется при закрытии |
| `generation_step` | durable job: `kind` (`PLAN`, `RESEARCH`, `TEXT_DRAFT`, `EDIT`, `TTS`, `IMAGE_GENERATE`, `IMAGE_SEARCH`, `VIDEO_GENERATE`, `TRANSCRIBE`), `depends_on`, lease/heartbeat/backoff/deadline/cancel, `idempotency_key` | lease-поля |
| `generation_event` | курсор прогресса для UI (bigserial, append-only, TTL) | append-only |
| `ai_provider_call` | аудит и стоимость: provider, model, usage, `cost_micros`, latency, outcome; без текстов промптов; 90 дней | append-only |
| `generation_provenance` | происхождение опубликованного: revision → session, model routes, prompt versions | immutable |

Состояния артефакта: `QUEUED → GENERATING → PROPOSED ⇄ REVISING; PROPOSED → REJECTED (undo) / STALE (re-pin) / PUBLISHED / HANDED_OFF; GENERATING → FAILED → QUEUED (retry)`. Состояния сессии: `PLANNING → PLAN_READY → RUNNING → REVIEW → CLOSED` (+ `CANCELLED`, `EXPIRED`).

**Approve** (материал): `POST …/artifacts/{aid}/approval` с `If-Match` версии колоды → в одной транзакции lock артефакта, проверка слотов (все `READY` или явно удалены), `GeneratedItemPublisher.create(...)` → `ItemService` (сам извлекает media refs и привязывает assets) → completion переводит артефакт в `PUBLISHED`, пишет provenance. Повтор `commandId` — stored receipt; устаревшая версия — 412, повтор с новым `commandId`. «Одобрить все готовые (N)» — bulk publication (≤100). Упражнение — `GeneratedExercisePublisher` → `ExerciseService` с предпочтением `objective.operation=reuse`, чтобы один навык не расщеплялся на несколько расписаний; если материал получил новую ревизию — `STALE` и серверный re-pin без LLM по стабильным node IDs.

**Почему не `EditingDraft`**: у AI-артефакта история ревизий, provenance, media holds, AI-состояния и до 20 штук на сессию — это засорило бы список черновиков и упёрлось бы в лимит 200. «Править самому» = handoff текущей ревизии в обычный `EditingDraft` (для нового материала `member_key = null`).

**Retention**: сессия живёт `P30D` от последней активности (как drafts), уведомление за 3 дня; retention worker по образцу `StudyRetentionWorker` удаляет неопубликованное и снимает holds; дальше — существующие P7D и two-scan GC.

### 3.3 Оркестрация

- Свой PostgreSQL step queue по образцу `MediaProcessingRepository`: `FOR UPDATE SKIP LOCKED`, lease + fencing token, heartbeat на virtual thread, backoff с jitter, deadline, cancel, мягкий per-account cap в claim и жёсткие лимиты на admission (≤3 активные сессии, ≤20 артефактов в сессии, reservation).
- **Fan-out без barrier**: `TEXT_DRAFT` в одной транзакции создаёт ревизию, где media nodes уже ссылаются на заранее выделенные `assetId`, создаёт слоты и дочерние шаги `READY` (`TTS`, `IMAGE_*`, `VIDEO_*`). Когда asset становится `READY`, renderer сам показывает медиа. «Ожидание последнего ресурса» — производное условие (нет слотов в работе), не блокирующий шаг. Частичный успех — норма: текст `PROPOSED`, видео `FAILED` → у слота «Повторить / Заменить / Убрать блок»; approve блокируется только неразрешённым слотом.
- Ни одна DB-транзакция не открыта во время вызова провайдера (как #76 держит FFmpeg вне транзакции).
- Таймауты Mnema короче провайдерских (DeepSeek закрывает соединение через 10 мин без inference): connect 5 с, idle stream 60 с, `TEXT_DRAFT` ≤6 мин, `EDIT`/`TTS` ≤2 мин, `IMAGE` ≤3 мин, видео — по deadline задачи провайдера с опросом.
- Классы ошибок: 429 → backoff с `Retry-After` (до 6); transient 5xx/timeout → до 3, затем fallback route; invalid output → один repair, затем strong route, затем `FAILED(INVALID_OUTPUT)`; refusal → `FAILED` без retry; budget exhausted → без вызова провайдера.
- Circuit breaker per `(provider, capability)` — ~80 строк своего кода; Resilience4j/spring-retry не оправданы (Spring Framework 7 даёт `RetryTemplate`/`@ConcurrencyLimit`, но на Boot 3.5 их нет).
- Пробуждение worker: `afterCommit` → executor (режим `all`), sweeper раз в 2 с, при разделении ролей — `NOTIFY/LISTEN` как подсказка (источник истины — таблица).
- Отклонены: JobRunr (нужные функции — только Pro, LGPL), db-scheduler (нет DAG; доменное состояние дублировалось бы), Spring Modulith events (не job queue), Kafka/RabbitMQ (docs: не требуются).

### 3.4 Прогресс в UI

| Вариант | Вердикт |
|---|---|
| Adaptive polling курсора событий `GET …/events?after=` (1 с при видимой вкладке и активных шагах, 5–15 с в фоне, немедленно при `visibilitychange`) | **v1** — паттерн уже есть в media upload; работает с текущим nginx и Identity-проверкой на каждый запрос |
| Streaming событий через HttpClient `partialText` (проверено: XHR-бэкенд 22.1.5 заполняет `partialText`) + `rxResource({stream})` | путь обновления, если p50 до первого блока > 5 с или polling ощущается рваным; тот же контракт событий |
| `EventSource` SSE | отклонён: не передаёт `Authorization`, cookie вырезаются |
| WebSocket | отклонён: новый протокол, sticky/broker при репликах |
| Token streaming | отклонён в v1: генерация идёт в фоновом шаге; сырые токены MBM/JSON нестабильны для показа; внутри worker streaming используется для checkpoint'ов и прерывания |

События: `ARTIFACT_STATE`, `BLOCKS_APPENDED` (скомпилированные native-блоки ≤32 KiB), `MEDIA_SLOT_STATE`, `USAGE_UPDATED`, `SESSION_STATE`; `seq` монотонный; большие тела — `GET …/artifacts/{aid}`. На клиенте блок проявляется «чернилами» ≤600 мс, при reduced-motion — сразу; `aria-busy` на статье; `role="status"` получает только сводки («Материал 3 из 10 готов»), не чаще 1 раза в 2 с; кнопка «Остановить генерацию» всегда есть.

### 3.5 Формат вывода модели

**Материалы — MBM v1** (подмножество Markdown + директивы), набор целиком задан возможностями native-v1 и узлами #76:

```text
# Глаголы движения: 行く・来る・帰る
Абзац с **strong**, *em*, `code`, [ссылка](https://…) и {漢字|かんじ}.
- пункт / 1. пункт
> цитата
---
::table{caption="Спряжение"}  + pipe-таблица (≤12 колонок, ≤100 строк)
::mermaid{title="…" description="…"}  + fenced source
::audio{slot="a1" lang="ja" voice="female" title="Произношение"} 行く
::image{slot="i1" mode="search|generate" alt="…"} запрос или prompt
::sources                                      список [n] из RESEARCH
```

Почему не native JSON напрямую: модель должна была бы придумывать UUIDv4 на каждый узел; JSON в 3–5 раз больше output-токенов (самая дорогая часть); рекурсивную схему нельзя провалидировать strict-режимом DeepSeek; частичный JSON плохо показывать. Markdown уже признан authoring/interchange view в `learning-content-format-v2.md`. Правила компилятора: серверные UUID; handle `[[b3]]` сохраняет node ID при правке, если тип блока не изменился; `alt`/`title` обязательны; ссылки — только из allowlist сессии (источники RESEARCH и URL из заметок пользователя); результат проходит тот же `NativeDocumentReader` (1 MiB, 10 000 узлов, глубина 32, лексический профиль `href`/`lang`); неизвестная директива — ошибка, не opaque-узел. Repair — один повтор с перечнем «строка → правило».

**Упражнения — strict JSON** с `anyOf` по механике из `contracts/study/mechanics.json`; модель использует короткие локальные ID (`o1`, `l1`, `bl1`) и handles блоков материала (`m2:b3`), сервер выделяет настоящие ID и компилирует `MATERIAL {memberKey, itemRevisionId, nodeId}` закреплённой ревизии. Проверка до показа: (1) тот же разбор `ExerciseCommand`, что при публикации; (2) семантический lint per mechanic (ответ не содержится в условии, различимые варианты, биекция пар, пропуск — существующий фрагмент pinned-текста и т. п.); (3) self-evaluation тем же `AttemptEvaluation`, что у `/exercise-previews`: ключ как ответ → `CORRECT`, дистрактор → `INCORRECT`; (4) опционально для «Подробно» — дешёвая модель-критик ставит флаг «сомнительно».

Возможности провайдеров (официально): DeepSeek `json_object` без enforced-схемы; strict tool calls — beta без `minLength/maxItems`; OpenRouter structured outputs зависят от провайдера. Следствие: схема провайдера — подсказка, источник истины — серверная валидация Mnema.

### 3.6 Правки по выделению

Гранулярность — блок (выделение внутри абзаца расширяется до блока). Renderer в режиме Мастерской помечает блоки `data-node-id`. `POST …/artifacts/{aid}/edits {commandId, expectedRevisionId, target:{nodeIds}, action ∈ REWRITE|IMAGE_SEARCH|IMAGE_GENERATE|AUDIO_REGENERATE|FREE, instruction}`; одна правка на артефакт одновременно (409 `EDIT_IN_PROGRESS`). Контекст собирается в порядке, удобном для prefix cache DeepSeek: (1) системный prompt + спецификация MBM + few-shot — байт-в-байт стабильны в пределах `prompt_version`; (2) бриф сессии (настройки, источники, образцы стиля — общий для батча); (3) outline + целевые блоки ± сосед; (4) ≤5 последних инструкций. Пункты 1–2 одинаковы для всех артефактов и правок → cache-hit ($0.003–0.006 за 1M). Undo — `revert {toRevisionId}` под CAS: перевод указателя, ничего не удаляется.

### 3.7 Провайдеры и capabilities

| Порт | Первая реализация |
|---|---|
| `TextGeneration` | OpenAI-compatible chat adapter: direct DeepSeek (primary), GigaChat (RU fallback), OpenRouter (если аккаунт оператора доступен — unverified) |
| `SpeechSynthesis` | API-кандидат после проверки контрагента (Fish Audio / MiniMax) с **кэшем по (нормализованный текст, язык, голос, модель, версия)**; при cache hit credits не списываются |
| `Transcription` | self-host faster-whisper (large-v3-turbo int8) в РФ; Groq/Voxtral — после legal OK; доменный `SpeechToTextProvider` реализуется поверх |
| `ImageSearch` | Pexels + Pixabay + Openverse/Wikimedia; файл сохраняется у себя (offline-совместимо), атрибуция/лицензия — в provenance и `caption`; host allowlist + анти-SSRF |
| `ImageGeneration` | позже: Recraft V4.1 Flash / FLUX.2 klein / Qwen-Image / Kandinsky — после legal-проверки контрагента |
| `VideoGeneration` | не в первом релизе; порт зарезервирован |
| `WebSearch` | Yandex Search API (если исключение Yandex не распространяется на поиск) / Perplexity Search / Exa после legal; Gemini grounding не подходит (запрещает хранить результаты) |
| `SemanticAssessmentProvider` (есть) | позже поверх `TextGeneration`; отдельный эпик с dispute flow |

Адаптеры — JDK `HttpClient` + Jackson + records (как `IdentityHttp`): ограниченный body, deadline, без redirects; SSE от провайдера читается построчно на virtual thread. **Spring AI не брать**: 2.0.x требует Boot 4.0/4.1, 1.1.x — EOL 2026-06-30; его абстракции (Reactor `Flux`, chat memory, tools/MCP) не нужны, а провайдер-специфичные поля usage (cache-hit DeepSeek, `usage.cost` OpenRouter) нужны именно нам. Пересмотреть после миграции на Boot 4.1.

Routing — server-owned конфиг (`learning.ai.routes.text-fast=…`), fallback только на 429/5xx/timeout/invalid-after-repair; выбор модели скрыт (owner decision: без BYOK). Capability flags по правилу `flag && adapter configured`; reason codes `DISABLED`, `PROVIDER_NOT_CONFIGURED` + новый `TEMPORARILY_UNAVAILABLE` (circuit open / глобальный бюджет). Квота пользователя — не capability: `GET /api/usage`.

Observability: структурированные логи `ai_call provider=… model=… capability=… step_id=… outcome=… latency_ms=… in_hit=… in_miss=… out=… cost_micros=…` без промптов и ПД; Micrometer-метрики (`mnema_ai_calls_total`, `mnema_ai_cost_micros_total`, `mnema_generation_step_queue_age_seconds`, `mnema_generation_repairs_total`, `mnema_generation_accept_ratio`, `mnema_usage_reserved_credits`). Главная продуктовая метрика — **cost per accepted item**. Секреты — только имена env (`MNEMA_AI_DEEPSEEK_API_KEY`, …), только в `worker`, лимит трат на стороне провайдера как последний предохранитель. Тесты: Stub-провайдер по умолчанию для local/CI; recorded fixtures (stream-чанки, keep-alive, 429, пустой `content`); golden-контракты MBM; offline golden eval (O-06, ≥300 fixtures RU/EN/FR/ES/JA/ZH/KO + STEM/code) как gate включения реальных пользователей, не unit-тест.

### 3.8 Usage, credits, ledger

- Таблицы: `usage_allowance` (аккаунт × период: credits, график недельного разблокирования, media sub-caps, STT bucket), `usage_balance` (материализованный остаток, `row_version`, в одной транзакции с ledger), `usage_reservation` (`ACTIVE → SETTLED/RELEASED/EXPIRED`), `usage_ledger_entry` (append-only: `GRANT/DEBIT/REFUND/ADJUSTMENT/EXPIRE`, `cost_micros`, `idempotency_key` UNIQUE, `rate_card_version`).
- Поток: preflight estimate (`POST /api/decks/{id}/generation-estimates` → credits p50/p95) → старт резервирует p95 в той же транзакции, что создаёт сессию (`409 USAGE_LIMIT_REACHED` с остатком и датой обновления) → перед каждым вызовом остаток reservation задаёт `max_tokens` (жёсткий потолок на стороне провайдера) → `DEBIT` по факту идемпотентно в одной транзакции с результатом шага → `RELEASE` при завершении/отмене; осиротевшие reservations истекают по TTL; правки — маленькая отдельная reservation.
- «Платите за результат»: шаги `FAILED` по вине провайдера пользователю не списываются; repair внутри успешного шага списывается (учтён множителем 1,5–2). Превышение hold (редко) покрывает маржа, пользователь в минус не уходит.
- «Потратить X% usage на колоду»: `budget = X% × текущий остаток периода`, показывается абсолютным числом, становится reservation и входом планировщика; жёсткость обеспечивает reservation, а не обещание модели.
- Защита бесплатного STT: отдельный bucket в секундах, клип ≤60 с, ≤10 мин/день, VAD/обрезка тишины на сервере, rate limit (например, 20 диктовок за 10 мин → 429 с `Retry-After`), только verified account, глобальный дневной бюджет Free-когорты с graceful degradation («распознавание временно в очереди»).
- Entitlement: потребление — в Learning `usage`; покупки — в будущем billing-контексте (#79), который публикует **entitlement snapshot** (план, период, allowances, `valid_until`) в `entitlement_inbox` Learning идемпотентно; браузерный return URL никогда не меняет права. До billing — `EntitlementSource` port с конфигурационной реализацией (все Free; локальный owner — через конфиг). Удаление аккаунта удаляет/обезличивает ledger по retention schedule (O-09).

### 3.9 Центр уведомлений (общий механизм)

`notification(id, owner_id, kind, severity, params ≤4 KiB, route, dedupe_key UNIQUE per owner, created_at, dismissed_at, expires_at)` + `notification_cursor(owner_id, read_upto)` (watermark вместо флага на строку). `NotificationPublisher.publish(...)` вызывается в той же транзакции, что доменное изменение, — outbox не нужен. `GET /api/notifications?after=&limit=` → items + `unreadCount` + `activeWork`; `PUT …/read-cursor`; `DELETE …/{id}`. Тексты формирует клиент по `kind + params`; retention 30 дней / 200 на аккаунт. Опрос с ETag/304 раз в 30–60 с, раз в 10 с при `activeWork > 0`. Первые типы: `GENERATION_READY/PARTIAL/FAILED`, `USAGE_LOW/EXHAUSTED`, `GENERATION_SESSION_EXPIRING`; позже `MEDIA_PROCESSING_FAILED` из #76, напоминание перед списанием из #79.

### 3.10 Безопасность и privacy

- **У модели нет agency**: нет инструментов с побочными эффектами; вывод компилирует детерминированный код; худший результат инъекции — плохой текст, который пользователь видит до approve (OWASP LLM01/05/06/10).
- Заметки, материалы, веб-сниппеты — untrusted data, обрамляются как данные. Бюджеты и лимиты задаёт подтверждённый пользователем spec; intent parsing («сделай все типы по 3») возвращает spec, который сервер клампит и показывает чипами для подтверждения — **текст никогда не тратит credits без подтверждённого плана**.
- Ссылки — только из allowlist сессии; YouTube — только `videoId`; Mermaid strict; байты от провайдера/из интернета — untrusted upload через тот же FFmpeg-контейнер без сети с более строгими лимитами (изображение ≤10 MiB, аудио ≤10 мин).
- «Найти похожее в интернете»: только лицензированные источники; скачивание с allowlist хостов, HTTPS, без cross-host redirects, DNS-резолв с отказом для private/loopback/link-local/metadata (включая IPv4-mapped IPv6), потоковый предел размера. Общий веб-фетч — только позже через egress proxy с allowlist.
- Минимизация данных: в prompt никогда не попадают email, имя, payment data, account UUID; `user_id` = `HMAC(account_id, rotating key)` (DeepSeek требует `[a-zA-Z0-9\-_]+`, ≤512, без ПД); preflight предупреждает о похожих на ПД фрагментах (email/телефон) с вариантом исключить, без автоматического вымарывания (в учебном тексте «email» может быть темой); raw prompts/ответы не хранятся дольше job; disclosure-экран перед первым AI-действием (какие данные, кому, в какую страну, обучение/нет); отдельное согласие на голос.
- DeepSeek по Privacy Policy использует данные для обучения (opt-out — письмом) и хранит в КНР; Китай в списке «адекватных» стран РКН (Приказ № 128) → уведомление о трансграничной передаче до начала; передача в US-хосты (через OpenRouter/DeepInfra) — в «неадекватную» страну с более высоким порогом. Per-provider kill-switch и routing — в конфиге без релиза.

### 3.11 Веб-поиск по effort

И ограничивать, и списывать: потолок на шаг — ради предсказуемой reservation, latency (+1–3 с на запрос) и меньшей поверхности инъекций; фактическое потребление — по факту. Дефолты: Короткий — 0; Средний — 2 (только при включённом «Проверять факты»); Подробный — 6; Auto — модель предлагает, сервер клампит до 3; максимум 15 — настраиваемый cap, не дефолт (15 запросов ≈ 3,7–16 ₽ — дороже 40 материалов). Pipeline контролируемый: `RESEARCH` (дешёвая модель предлагает ≤N запросов strict JSON) → сервер выполняет через `WebSearch`, дедуплицирует, нумерует → `TEXT_DRAFT` ставит `[n]`, `::sources` компилируется в heading + список ссылок (URL обязаны быть в результатах). Отдельный `citation` node — будущая задача контента.

---

## 4. Экономика

### 4.1 Себестоимость типовых операций (worst = peak, cache-miss, ×1,5, 100 ₽/$; base = 85 ₽/$)

| Операция | Маршрут | Worst, ₽ | Base, ₽ | Upside, ₽ | Вес, credits |
|---|---|---:|---:|---:|---:|
| Короткий материал | DeepSeek V4.1 Flash non-thinking | 0,40 | 0,34 | 0,12 | 4 |
| Средний материал | то же | 0,95 | 0,80 | 0,28 | 10 |
| Подробный материал | то же | 2,16 | 1,84 | 0,67 | 22 |
| 5 упражнений на материал | то же | 0,72 | 0,61 | 0,21 | 8 |
| Правка по выделению / чат | то же | 0,36 | 0,31 | 0,09 | 4 |
| AI-проверка одного ответа | Flash, rubric в кэшируемом префиксе | 0,05–0,09 | — | 0,02 | fair-use |
| «Умный план» | Flash+thinking / Pro+thinking | 1,98 / 7,52 | 1,68 / 6,40 | — | 20 / 75 |
| 1 мин STT | Groq turbo (≥10 с / 5 с) / Voxtral | 0,07 / 0,13 / 0,30 | — | — | fair-use |
| TTS слово/фраза ≤100 симв. | Fish (RU) / Gemini-класс | 0,28 / 0,12 | — | 0 при cache hit | 2–3 |
| TTS-клип 30 с (400 симв.) | Fish RU / MiniMax turbo / Chirp 3 HD | 1,12 / 2,40 / 1,20 | — | 0 при cache hit | 10 |
| «Подкаст» 3 мин | Fish RU + Flash / MiniMax | 7,2 / 14,9 | — | — | 75 (cap) |
| Изображение эконом / качество | Recraft Flash, FLUX.2 klein / FLUX.2 pro, Recraft | 0,7–1,4 / 3–7 | — | — | 15 / 60 (cap) |
| Поиск лицензированного изображения | Pexels/Pixabay/Openverse + Flash для запроса | 0,05 | — | — | 1 |
| Видео 5 с | Omni 360p / Kling 2.6 / Veo 3.1 Fast | 15 / 21 / 50 | — | — | 250–500 (cap) |
| Фактчек low / high (15 запросов) | Perplexity / Exa / Tavily | 0,8–1,6 / 3,7–16 | — | — | 15 / 120 (cap) |

Порядки: **1 средний материал ≈ 1 озвученный клип ≈ 1 эконом-картинка ≈ 1 low-фактчек ≈ 10 минут STT; 1 видео ≈ 20–50 материалов.** Thinking у DeepSeek теперь включён по умолчанию — для генерации выключать явно, иначе output ×2–4.

### 4.2 Модель usage (вариант C)

- **Один бар «AI-бюджет»** на создание: материалы, упражнения, правки, озвучка, картинки, фактчек, подкасты.
- **Fair-use «Голос и проверка»** вне бара: минуты STT и число AI-проверок в день/месяц; тихий счётчик, появляется при >80%. Учёба не блокируется генерацией — это ядро retention.
- **Count-caps** внутри бара на подкасты, «качественные» изображения, high-фактчек, видео — против «съел месяц за вечер».
- Paid: месячный бар сразу, дневной burst-cap ≤35%; Free: ¼ бара в понедельник без накопления; rollover — нет; «Буст +50%» разовой покупкой — после recurring.
- Пользователь видит проценты и «≈12 материалов с упражнениями или ≈25 озвучек»; credits — внутренняя единица; веса пересматриваются через 14 дней реальных замеров без ретроактивного изменения.

### 4.3 Тарифы и allowances (hard guarantee: сумма всех caps ≤25% цены с НПД 4% и эквайрингом ≈3%)

| | Free | Trial (14 дн., 1 раз, без карты, старт по AI-intent) | Plus 449 ₽ | Pro 990 ₽ | Max 1 900 ₽ |
|---|---|---|---|---|---|
| Бар, credits/мес | 50 (12–13/нед) | 150 | 360 | 820 | 1 780 |
| STT fair-use, мин/мес (≤/день) | 30 (10) | 60 | 120 (30) | 300 (60) | 600 (120) |
| AI-проверка ответов/мес (≤/день) | 50 (5) | 200 | 500 (40) | 1 000 (80) | 1 500 (120) |
| Подкасты 3 мин (cap) | — | — | 2 | 6 | 15 |
| Изображения | только поиск по стокам | поиск + 3 эконом | эконом в баре (≤20/день) | эконом + 10 «качество» | эконом + 25 «качество» |
| Фактчек | low | low | low + 2 high | low + 5 high | low + 12 high |
| «Умный план» | — | 1 | 4/мес (Flash) | еженедельно | еженедельно + 4 Pro |
| Видео | — | — | — | — | нет на старте (опция 3 клипа low-res) |
| Max себестоимость, ₽ | ≈11,5 | ≈33 | 80 (24,9%) | 177 (24,9%) | 341 (25,0%) |
| ≈ «материал + 5 упражнений + сопутствующее»/мес | ≈2 | — | 12–17 | 29–39 | 63–85 |

Маржа при типичном использовании (40%) ≈86%; СБП-подписка вместо карты (без НДС на комиссию) +2,4 п.п. После двух когорт — переход от hard guarantee к measured p95 с ростом бара ×1,35–1,5. Потолок НПД 2,4 млн ₽/год ≈ 445 подписчиков Plus → алерты на 1,8 и 2,1 млн и runbook перехода на УСН (уже в checklist). Годовые (−25%) — после двух когорт. Max — только когда готовы TTS и изображения, иначе это пустой якорь (decoy-эффект слаб).

### 4.4 Free и бесплатный STT

Ожидаемая стоимость Free MAU ≈1,2–1,5 ₽ (25% пользуются AI, тратят полбара), ≈2,9–4,2 ₽ при активном использовании; break-even ≈0,26–0,39% Paid/MAU. STT 30 мин/мес на Groq в худшем случае 2–4 ₽/пользователя, в среднем 0,12–0,24 ₽/MAU; self-host CPU в РФ (≈2,8 тыс. ₽/мес за 8 vCPU) выгоден с ≈10 тыс. MAU и снимает трансграничную передачу голоса — на старте запускать на уже оплаченном сервере приложения с очередью, бенчмарк латентности на 5–15-секундных клипах обязателен.

### 4.5 Paywall и психология цены

- Free выбран по умолчанию снижает конверсию (default effect, d=0,68): компромисс — радио стоит на Free (честно, без pre-ticked платных опций — ст. 16 ЗоЗПП в ред. 69-ФЗ), но **визуально выделен рекомендуемый тир** с бейджем по цели из онбординга и конкретикой («≈35 тем с упражнениями в месяц»). A/B «Free selected vs recommended selected» — после ≥1–2 тыс. показов.
- «Чашка кофе»: опубликованных A/B нет; per-day framing (Gourville 1998: 52% vs 30%) работает для малых сумм и разворачивается для крупных. Писать «≈15 ₽ в день» для 449 ₽; «одно занятие с репетитором» для 990 ₽ — только как дополнение, не замена; сравнения с чужими товарами (такси, репетитор) — ФЗ «О рекламе» ст. 5 → юрист.
- Онбординг «Для чего вам Mnema?» (Экзамены и сессия / Собеседование / Язык / Работа / Для себя / Пропустить) меняет рекомендуемый тир, примеры в баре, placeholder composer и первый AI-сценарий; ответ не уходит провайдеру.
- Законные формулировки: цена в рублях полной суммой; отдельная неотмеченная галочка автопродления с суммой и датой; отказ от сохранённой карты электронно в один клик (376-ФЗ с 01.03.2026); напоминание перед списанием законом не требуется, но остаётся guardrail из checklist. FAQ: «Все колоды остаются… нельзя только добавлять новые сверх бесплатного лимита».
- Лимит колод — слабый и рискованный рычаг (бэклэш Evernote/Quizlet; внутри колоды безлимит → всё сольют в одну). Если вводить: только свои колоды, Free ≥10, копии публичных не считать (иначе налог на teacher→learner loop), существующие никогда не блокировать.
- Российский ценовой коридор AI-подписок ≈200–1 700 ₽; 449 ₽ = Яндекс Плюс (узнаваемый якорь); 990 ₽ — уровень языковых приложений; 1 900 ₽ выше топ-тиров агрегаторов (1 690 ₽) — оправдывать медиа и объёмом. Шаг ×2,2 / ×1,9 соответствует рынку.

---

## 5. UX: экраны и решения

### 5.1 Composer (один компонент на три входа)

```text
← Колода «Японский N4»
НОВЫЙ МАТЕРИАЛ
Юзуру, что будем учить сегодня?                                 (h1 = видимый label)
┌──────────────────────────────────────────────────────────────┐
│ Например: 20 глаголов движения с примерами из аниме           │  textarea, field-sizing
│ [＋ Заметки «На потом» · 4 выбрано ✕]                         │
│                                        ≈ 6% лимита [ Создать ➤ ]  ← snake CTA
└──────────────────────────────────────────────────────────────┘
Колода: [Японский N4 ▾]            Или откройте пустой редактор →
────────────────────────────────────────────── Настройки · Авто
Подробность   (•Авто) (Кратко) (Средне) (Подробно)
  Авто: Мнемозина выберет объём по заметке — обычно 2–4 абзаца.   ← живое пояснение
Вложения      [✓] Изображения  [✓] Аудио  [ ] Видео  [✓] Ссылки  [ ] Таблицы
  Аудио: сколько (•Авто)(1)(2)(3)   длительность (•Авто)(до 5 с)(до 30 с)
Похоже на     (•Как в колоде) ( Выбрать материал… )
▸ Настроить для каждой заметки отдельно   (2 настроены)
▸ Сначала показать план
```

- Входы: «На потом» (заметки чипами, поле необязательно), «Новый материал» (поле обязательно), «Упражнения с ИИ» (другой набор настроек). Переход в Мастерскую — View Transition (мгновенно при reduced-motion).
- Enter отправляет, Shift+Enter — перенос, **никогда во время IME-композиции** (`isComposing`, `keyCode 229`) — критично для JA/ZH/KO когорты; на телефоне Enter — перенос, отправка — кнопкой с `enterkeyhint="send"`.
- Многоуровневый тогл — native radio в `fieldset` + `legend`, внешне сегменты (как `mechanic-picker` #267); вложенные параметры аудио раскрываются только при отмеченном «Аудио» (disclosure), а не циклическая кнопка. «Авто» всегда первый и выбран.
- Пояснения «Авто сам определит…» — **живым текстом под группой**, а не hover-tooltip (недоступен с touch; WCAG 1.4.13); длинные — toggletip `popover="auto"`.
- Preflight «≈ 6% месячного лимита» (диапазон при неточности), сервер считает с debounce 400 мс; при нехватке кнопка не выключается, нажатие показывает «Не хватит лимита: выберите «Кратко», уберите видео или посмотрите тарифы».
- Когда AI недоступен (capability false / лимит / сеть) — страница сразу открывает обычный редактор со спокойной заметкой; «Или откройте пустой редактор» — равноправная ссылка.

### 5.2 Кнопка со «змейкой»

`@property` + анимированный `conic-gradient` на `border-box`-слое фона (padding-box — ink), голова `--mn-sheet`, хвост `--mn-hint`, без радуги; запускается только hover/focus и делает **3 круга** (бренд-контракт запрещает бесконечную анимацию); reduced-motion — статичный блик; forced-colors — системная рамка. Одна такая кнопка на экран («Создать» в composer, «Добавить упражнения» в билдере). Готовый сниппет — `research-ux.md` §E.2. Варианты `mask-composite` (нет `content-box` в Safari) и `offset-path` (хвост отдельно) отклонены.

### 5.3 Мастерская

```text
МАСТЕРСКАЯ · из 4 заметок «На потом»                 ИИ · проверьте факты
┌ ‹  3 из 10  › ───────────────────────────────────────────────────────┐
│ ● ● ◐ ○ ○ ○ ○ ○ ✕ ○   7 готово · 2 пишутся · 1 не удался   [Стоп]    │
├──────────────────────────────────────────────────────────────────────┤
│ «行く・来る・帰る»                       [Править самому] [Ещё вариант] │
│ ▌Абзац проявляется чернилами…                                         │
│ ┌──────────── 16:9 ────────────┐  ← клик: «Найти похожее / Создать»  │
│ │  Подбираем изображение…      │                                      │
│ └──────────────────────────────┘                                      │
│ ▶ аудио 0:04 · синтезированная речь                                   │
│ Из заметки: «行く vs 来る — когда что?»                                │
│  [Удалить (удержание)]           [Отклонить]    [Одобрить и далее →]   │
└──────────────────────────────────────────────────────────────────────┘
Одобрить все готовые (6)                               Выйти в колоду
```

- Пейджер — `nav` с кнопками «‹ ›» и `ol` точек-статусов (форма ●◐○✕✓ передаёт статус без цвета); `aria-current="step"`; URL отражает позицию (`?n=3`) для ссылки из уведомления; swipe — дополнительный способ.
- Не ждать партию: первый готовый материал открывается сразу; сбой одного не ломает партию, лимит за него не списан.
- «Одобрить и далее →» — primary и переход к следующему неразобранному; «Отклонить» обратимо до закрытия; «Удалить» — `app-hold-to-delete-button`.
- Уход без одобрения ничего не теряет: «Мастерская: 2 активные» на странице колоды; истечение через 30 дней с предупреждением.
- Медиа-placeholder — бумажная рамка с `aspect-ratio` (без CLS) и подписью «Подбираем изображение…»; медиа — 3–4 варианта на выбор, у найденных — источник и лицензия; «Вернуть» прежнюю.
- Маркировка «ИИ · проверьте факты» один раз на группу; после ручной правки — «С участием ИИ, проверено вами».

### 5.4 Правка выделенного

Переиспользуется существующая панель `native-editor` «Действия с выделенным текстом» («Сделать ссылкой», «Добавить чтение») — добавляется «Попросить Мнемозину…». Немодальный `role="dialog"` с пресетами «Проще / Короче / Пример / Подробнее» + поле + «≈ 0,3% лимита»; Esc и клик вне закрывают, возвращая фокус и выделение. Выделение остаётся видимым (inline-декорация в ProseMirror; в режиме чтения — CSS Custom Highlight API). Блок в состоянии `rewriting`: старый текст остаётся читаемым, штриховая рамка, «Мнемозина переписывает…», `aria-busy`; opacity текста не понижается (бренд-контракт). Результат — полоска «Переписано · Показать изменения · Оставить · Вернуть · Ещё раз»; diff `<del>/<ins>` с визуально скрытыми «удалено:/добавлено:». Mobile (`pointer: coarse`): системное меню выделения конфликтует → полоса внизу «Изменить с Мнемозиной» → `<dialog>` + `showModal()` (без `closedby`: нет в Safari). Микрофон — только при `speechToText.available`; запись — общий `AudioRecorder`, вынесенный из `native-media-upload`, без Web Speech API. Для картинки/аудио — существующий инспектор медиа + секция «Найти похожее / Создать» / «Найти запись / Озвучить».

### 5.5 Хаб колоды, статистика, multi-select

- Хаб «сначала читать»: описание отрисовано, форма и «Удалить колоду» — за «Изменить». Предложение перенести список материалов с `/decks/:id/materials` в хаб (greenfield: старый маршрут удаляется) — **решение владельца**, меняет IA.
- Статистика — 5 честных виджетов без vanity (по product-direction: не opens/streak/mastery %), каждый заканчивается действием:

| Виджет | Форма | Текст + действие | Чего нет в API |
|---|---|---|---|
| Покрытие упражнениями | donut «34 из 50» | «16 материалов без упражнений — они не попадут в занятия» → «Показать» / «Упражнения с ИИ» | агрегат `coverage` (сейчас N+1 по `GET /exercises?memberKey=`) |
| Где вы сейчас | waffle (клетка = материал; >200 — корзины) | «К повторению 12» → «Учить» | агрегат `states` по `study-progress` |
| Ближайшие повторения | 7 столбцов-дней | «В четверг 20 — можно начать сегодня с короткого занятия» | `dueByDay[7]` в timezone аккаунта |
| Разнообразие механик | точки по механикам | «Почти всё — выбор ответа. Ввод ответа проверяет память надёжнее» (evidence CHOICE=LOW, FREE_RESPONSE до HIGH) | `exercisesByMechanic` |
| Неразобранные заметки | число + возраст самой давней | «Здесь есть мысли, к которым вы давно не возвращались» → «Разобрать» / «Создать материалы с ИИ» | `oldestOpenCreatedAt` |

Новый `GET /api/decks/{id}/insights` (`private, no-store`) + серверная сортировка `items?sort=exerciseCount&include=exerciseCount` (cursor). Реализация — inline SVG (`<figure>`, `role="img"`, `aria-labelledby`, `<details>` с таблицей-альтернативой), штриховка «под гравюру» через `<pattern>` (различимо в ч/б и forced-colors), прорисовка дуги один раз при `no-preference`, `@defer (on viewport)`; на mobile — лента `scroll-snap` с тремя главными виджетами.

- Multi-select: строка перестаёт быть `<a>` (чекбокс внутри ссылки недопустим) → `<li>` с чекбоксом, растянутой `row-link` и числом упражнений; волна строк сохраняется (`:has(:focus-visible)`); tri-state «Выбрать все» с «Выбраны 20 загруженных. Выбрать все 50 в колоде?» (серверная команда принимает явные ID ≤100 или `{allInDeck, except, expectedDeckRevision}`); Shift+клик — диапазон; long-press не нужен. Действия — **отдельная прилипающая нижняя панель** `role="region"`, не подмена смысла кнопки «Удалить колоду» (ошибка режима). Массовое удаление — `app-hold-to-delete-button` с `label="Удалить выбранные · 7"` и новым input `consequence` («7 материалов и 15 упражнений исчезнут из колоды. История ответов сохранится»); частичный успех описывается честно.

### 5.6 Билдер упражнений и диалог материала

Тот же composer с другими настройками: механики — чипы из серверного registry (Auto по умолчанию); приоритет (•) сначала без упражнений / ( ) все выбранные; количество — (•) Авто / ( ) Точно (`range` + `output` c `aria-valuetext`) / ( ) В пределах бюджета «не больше 10% лимита» — взаимоисключающие радио. Опционально «Сначала показать план» → `PLAN_READY` с видимой стоимостью → правка плана → запуск. Результат — экран проверки партии: предпросмотры через `exercise-preview-host` (#267), все отмечены «Оставить», «Сохранить выбранные (14)»; сохранённые получают серверную метку «Новое» (снимается после открытия или через 7 дней); «Изменить» → редактор #267. Диалог в карточке материала — свёрнутый composer «Попросить Мнемозину…»: свободный текст → strict-JSON spec → редактируемые чипы → подтверждение; результат — карточка с «Оставить / Вернуть / Ещё раз»; история диалога не хранится дольше сессии.

### 5.7 Центр уведомлений

`NotificationCenter` (signals): `inbox`, `unreadCount`, `toastQueue`, `quiet` (computed от маршрута и Study-фокуса). Тосты — `<section class="toast-region" aria-label="Уведомления">` после `<main>`, `popover="manual"`, сами не live; озвучивает постоянный `role="status"` со своей очередью (CDK `LiveAnnouncer` стирает предыдущее). Сроки: 3 с — только эхо-подтверждения; **6 с — «Готово» со ссылкой**; ошибки висят до закрытия; пауза при hover/`:focus-within`/скрытой вкладке; свайп + обязательный «×» (WCAG 2.5.1); ≤3 видимых, «+2 ещё» → «Входящие»; reduced-motion — fade вместо сдвига; desktop сверху справа ≤22rem, mobile сверху с `safe-area-inset-top`. **В Study тост ждёт естественной паузы** (экран feedback, конец сессии), бейдж обновляется сразу; настройка в профиле «Во время занятия: в паузах / сразу / только значок». Ловушка: пока открыт модальный `<dialog>`, top-layer popover inert → откладывать. Колокольчик в `.session` шапки с бейджем «99+», панель — `<ul>` ссылок (`popover="auto"`), пустое состояние «Пока тихо. Когда Мнемозина закончит работу, сообщение появится здесь».

### 5.8 Имя и голос

Гибрид: **«Мнемозина» — имя собеседника** в диалоговых местах (приветствие, «Попросить Мнемозину…», «Мнемозина переписывает…», уведомления); **«ИИ» — функциональные подписи и раскрытие** (бейдж «ИИ», «Создано с ИИ», «синтезированная речь» — уже так в коде #266). Без аватара-лица; значок — звезда ✧ из `deck-constellation`. Первое лицо редко, ошибки вслух («Проверьте даты — я могла ошибиться»), тон спокойный и книжный: «Готово. Можно проверить и начать учить». Маркировка в данных с первого дня: `origin: MANUAL | AI_ASSISTED` у материала, `aiEdited` у блока; 243-ФЗ (ст. 9–10, с 01.03.2027) — применимость к Mnema как к вызывающему чужую модель — вопрос юристу.

---

## 6. Pushback: где я расхожусь с ТЗ и почему

| Идея из ТЗ | Вердикт | Почему | Что предлагаю |
|---|---|---|---|
| Хостинг в Финляндии на первое время | **стоп, юрист** | ч. 5 ст. 18 152-ФЗ с 01.07.2025 запрещает первичную запись ПД граждан РФ за рубежом (штраф 1–6 млн ₽, повторно 6–18 млн ₽; ИП отвечают как юрлица); противоречит вашему же решению «инфраструктура в РФ»; Hetzner расторгает договоры с клиентами с российскими адресами; финский сервер не делает OpenAI/Google/ElevenLabs разрешёнными (их terms запрещают *offering access* пользователям из РФ) | Identity, данные, медиа, платежи — в РФ (Timeweb/Selectel/VK Cloud ≈1–3 тыс. ₽/мес); AI-шлюз stateless; DeepSeek и GigaChat доступны из РФ напрямую — зарубежный узел почти ничего не даёт |
| OpenRouter как путь к западным моделям | условно | по сторонним (неподтверждённым) данным с мая–июня 2026 OpenRouter ограничивает RU-аккаунты/IP; terms запрещают обход через VPN/proxy и перепродажу; закрытые модели наследуют запрет своих вендоров; open-weight (DeepSeek, Qwen, GLM) — по terms модели | адаптер OpenRouter оставить как опцию (ZDR-хосты DeepSeek — лучший privacy-вариант), но primary — direct DeepSeek + GigaChat; проверить доступность аккаунта оператора |
| Google TTS «качественный, дешёвый, разнообразный» | **отклонить** | Google Cloud/Gemini API не берут RU-клиентов и исключают аудиторию до 18; Chirp 3 HD — #57/92 на арене; сильная Gemini-TTS дорожает ×2 с 2027 | TTS через порт с кэшем: Fish Audio / MiniMax после проверки контрагента; self-host Piper для слов; главный рычаг — кэш по (текст, язык, голос) |
| Видео в первом релизе | **отложить** | 5 с = 13–200 ₽ ≈ 20–200 материалов; Veo 2/3, Sora 2, Imagen 4 закрыты за месяцы; Google исключает РФ и <18; учебная ценность стокового клипа без сценария низкая | порт зарезервирован; `::video` только при включённой capability; если нужна витрина — ≤3–5 клипов low-res в Max с ручным подтверждением стоимости |
| Генерация изображений сразу | частично | 0,7–7 ₽ за штуку и legal-проверка контрагента | на старте «Проиллюстрировать» = поиск в Pexels/Pixabay/Openverse/Wikimedia (≈0,05 ₽, offline-совместимо, лицензионно чисто) с сохранением файла и атрибуции; генерация — Pro/Max позже |
| 1–10 reference-материалов | **не делать** | выбор десяти — отдельная задача сортировки; каждый эталон раздувает контекст; пользователь не увидит, какой на что повлиял | «Похоже на: (•) как в колоде (сервер берёт 1–2 недавних в кэшируемый бриф) / ( ) выбрать материал» — один закреплённый; до трёх — после интервью |
| Hover-tooltip над «Авто»/«Максимум» | заменить | недоступен с touch; WCAG 1.4.13 требует закрываемость и устойчивость | живое пояснение под группой + toggletip для длинного текста |
| «Печатает как человек» (typewriter) | заменить | генерация идёт в фоновом шаге, не в HTTP-запросе; сырые токены MBM/JSON нестабильны; данных о пользе посимвольной анимации для понимания нет | блоки проявляются «чернилами» ≤600 мс по мере готовности; внутри worker — streaming для checkpoint'ов |
| Тост 3 с | 6 с для «Готово» | 3 с мало для русской фразы с действием; WCAG 2.2.1 допускает автоисчезновение только при наличии «Входящих» | 3 с — эхо; 6 с — «Готово»; ошибки — до закрытия; пауза при hover; в Study — до естественной паузы |
| Кнопка «Удалить колоду» превращается в «Удалить выбранные» | заменить | одна кнопка с разным смыслом → ошибка режима | отдельная нижняя панель выбранных; удаление колоды — в «Изменить» |
| Микрофон в popover правки сразу | условно | STT — выключенная capability (#265/#266), скрытый browser speech recognition запрещён | показывать только при `speechToText.available` |
| Два usage-бара (текст / медиа) | один бар + fair-use + caps | два бара путают upsell; у каждого остаётся неиспользованный; STT/проверка в баре пугают учиться | вариант C |
| Веб-поиск до 15 на high | cap, не дефолт | 15 запросов ≈ 3,7–16 ₽ — дороже 40 материалов; latency +1–3 с на запрос | дефолты 0/2/6/3, 15 — настраиваемый максимум; и ограничивать, и списывать |
| Лимит колод как рычаг подписки | слабый | бэклэш Evernote/Quizlet; внутри колоды безлимит; бьёт по teacher→learner loop | не на старте; если да — свои колоды, Free ≥10, копии не считать |
| Free выбран по умолчанию на paywall | да, но | default effect d=0,68 снизит конверсию | Free выбран, рекомендуемый тир визуально выделен по цели онбординга; A/B позже |
| 449/990/1900 ₽ | проходят guardrail | конфликт с принятым Starter 299 ₽ в docs | обновить economics-док явным решением; Max — когда есть TTS/картинки |
| Токены за выделение абзаца внутри блока (span-level) | блок | span-правка ломает inline-структуру и node IDs | гранулярность — блок; выделение расширяется до блока |
| Spring AI | не брать сейчас | 2.0 только для Boot 4.x; 1.1 EOL; абстракции не нужны, провайдер-специфичные usage-поля нужны | JDK HttpClient + свои DTO за портами; пересмотреть после Boot 4.1 |

---

## 7. Риски и внешние gate

| Gate / риск | Что нужно | Блокирует |
|---|---|---|
| G1 Legal: юрисдикция хостинга и AI-обработчики | письменное решение: где ПД пользователей из РФ, какие провайдеры/страны, тексты согласия/раскрытия, уведомление РКН о трансграничной передаче, доступность аккаунтов DeepSeek/GigaChat/OpenRouter для оператора, применимость 243-ФЗ | любые реальные данные в AI (не блокирует разработку на stub) |
| G2 Platform: Spring Boot 3.5 → 4.1 | отдельный refinement (Framework 7, Jackson 3, Security 7); AI-код пишется переносимо | production; не блокирует stub-разработку |
| Оплата DeepSeek из РФ | проверить малым пополнением; держать минимальный баланс; параллельно GigaChat-аккаунт | production route |
| Качество на RU/KO/JA/ZH/STEM | golden eval ≥300 fixtures; метрика cost per accepted item | включение реальных пользователей |
| Стоимость Free-аудитории | fair-use, bucket, velocity-лимиты, глобальный дневной бюджет когорты | маржа |
| Факт-ошибки в учебном материале | «проверьте факты», источники, approve как осознанное действие; ничего не попадает в Study до одобрения | доверие |
| Prompt injection через заметки/веб | нет agency; untrusted framing; link allowlist; spec клампится сервером | безопасность |
| Авторские права на найденные медиа | только лицензированные источники; атрибуция хранится и показывается; без hotlink (Unsplash не подходит) | legal |
| Снижение собственной активности ученика (generation effect, d≈0,40) | совместный режим; «Править самому» равноправно; одобрение — явное | педагогика |
| #268 ещё не влит | генератор берёт механики с сервера; ORDER/CATEGORIZE lint — после merge | AI-13 |

---

## 8. Декомпозиция: эпик и задачи

Принципы: каждая задача — законченный пользовательский/операционный результат за 1–3 agent-days по `work-item-standard.md`, полный quality gate, rollback через protected revert + пересоздание disposable local DB; всё работает на stub-провайдере, реальные пользователи — только после G1 и AI-17. Issues создаются под реактивированным #77 (переписанный) только после решения владельца.

### Фаза 0 — решения и контракты

| № | Задача | Outcome |
|---|---|---|
| AI-00 | Docs/contracts реактивации #77 | `docs/architecture/ai-generation.md`, `docs/product/ai-layer-2026-10.md` (решения по вопросам §9), `contracts/generation/` (state machines, MBM v1 + fixtures, events, коды ошибок), `contracts/usage/`, `contracts/notifications/`; переписанный эпик #77; обновлённый economics-док |
| G1 | Human/legal | см. §7 |
| G2 | Boot 4.1 migration | отдельный эпик/issue вне AI |

### Фаза 1 — фундамент без AI (можно начинать сразу, параллельно)

| № | Задача | Outcome | Зависит |
|---|---|---|---|
| AI-01 | Usage ledger skeleton | бар credits в профиле; сервер отклоняет reservation сверх лимита; rate card v1 в конфиге; `EntitlementSource` (config Free); estimate/reserve/settle/release; `GET /api/usage` | AI-00 |
| AI-02 | Provider foundation | порты, OpenAI-compatible adapter (stream/non-stream), routing, timeouts/retry/breaker, `ai_provider_call`, Stub, расширение `/api/capabilities` + frontend parser, offline eval runner; оператор локально видит `aiGeneration: available`, метрики, журнал без ключей в логах | AI-00 |
| AI-03 | MBM v1 compiler | MBM ↔ native-v1 детерминированно, golden fixtures, ошибки с позицией, link allowlist hook, property-тесты лимитов; каждый тест прогоняет результат через `NativeDocumentReader` | AI-00 |
| AI-07 | Центр уведомлений | durable контракт, cursor, retention, колокольчик в shell, тосты (6/3 с, пауза, свайп, Study-quiet), первые producers — существующие длительные операции (media processing) | AI-00 |
| AI-12 | Хаб колоды, покрытие, multi-select | `insights` endpoint + 3–5 виджетов с таблицами-альтернативами; список с числом упражнений и сортировкой «пустые сначала»; multi-select; ручное bulk-удаление (hold с `consequence`) | — (IA-решение владельца) |
| AI-UI | UI-примитивы | `SegmentedChoice`, `Toggletip`, `UsageMeter`, `.generate-cta` — показываются на существующих настройках | — |

### Фаза 2 — первый вертикальный срез: материал из запроса

| № | Задача | Outcome | Зависит |
|---|---|---|---|
| AI-04 | Сессии и шаги: текстовые материалы (backend) | по API: создать сессию из prompt, события, предложенные материалы; миграции; dispatcher (lease/heartbeat/backoff/deadline/cancel/fairness); `TEXT_DRAFT` со streaming checkpoints; отмена; тест «нет открытой транзакции во время вызова» | AI-01..03 |
| AI-05 | Approve, отклонение, handoff, retention | `GeneratedItemPublisher`, single + bulk approve, reject/undo, handoff в `EditingDraft`, удаление сессии, retention worker, provenance; тесты 412/replay receipt | AI-04 |
| AI-06 | Composer и Мастерская (UI) | от «Что будем учить сегодня?» до одобренного материала: composer, preflight, пейджер «1 из N», проявление блоков, approve/reject/approve-all/hold-delete, список активных сессий; a11y/mobile/reduced motion; screenshots 320/390/1440 | AI-05, AI-UI |
| AI-08 | Генерация из «На потом» | выбор N заметок → материалы с источниками; группировка «материал на заметку / объединить»; per-note overrides; pinned версии заметок; «Архивировать использованные (N)» | AI-06 |

### Фаза 3 — упражнения

| № | Задача | Outcome | Зависит |
|---|---|---|---|
| AI-13 | Генерация упражнений | билдер (механики из registry, приоритет, Авто/точно/% бюджета), strict JSON per mechanic, lint, self-evaluation, reuse objectives, `GeneratedExercisePublisher`, `STALE` re-pin, экран проверки партии, метка «Новое» | AI-05, AI-12; #268 для ORDER/CATEGORIZE |
| AI-14 | Планировщик | «Предложить план» по статистике колоды → `PLAN_READY` → правка → запуск; стоимость видна | AI-13 |
| AI-16 | Диалог материала и AI-правка существующего | intent → spec-чипы → подтверждение; сессии `REVISE_ITEM`/`REVISE_EXERCISE`; revise через `PUT` + `If-Match` | AI-11, AI-13 |

### Фаза 4 — правки и медиа

| № | Задача | Outcome | Зависит |
|---|---|---|---|
| AI-11 | Inline-правки по выделению | `data-node-id` в renderer, popover в панели выделения, `EDIT`, порядок контекста под cache, сохранение IDs, diff, история/undo/redo, mobile bottom sheet | AI-06 |
| AI-10 | Изображения: лицензированный поиск | `::image mode=search`, Pexels/Pixabay/Openverse/Wikimedia, сохранение файла, атрибуция/provenance, host allowlist + SSRF-тесты, 3–4 варианта в Мастерской | AI-06 |
| AI-09 | TTS media slots с кэшем | `::audio`, staging в #76 pipeline (origin `generated`), `generation_media_ref`, состояния слотов, кэш по хэшу, sub-caps, «Озвучить/заменить голос» | AI-06, выбор провайдера |
| AI-10b | Генерация изображений | `mode=generate` в Pro/Max после legal-проверки провайдера | AI-10 |
| AI-18 | Веб-исследование | `RESEARCH`, `WebSearch` adapter по итогам eval, бюджеты по effort, allowlist ссылок, раздел «Источники» | AI-04, G1 |

### Фаза 5 — голос, эксплуатация, деньги

| № | Задача | Outcome | Зависит |
|---|---|---|---|
| AI-15 | Speech-to-text | `speech-inputs` (≤60 с, эфемерно), `TRANSCRIBE`, реализация `SpeechToTextProvider` (self-host/Groq), fair-use bucket, VAD, rate limits, глобальный бюджет, согласие на голос; микрофон в composer/popover/«На потом» | AI-01, AI-02, G1 |
| AI-17 | Эксплуатация и eval | роли `api`/`worker`, `NOTIFY`, глобальные дневные бюджеты, дашборд метрик, runbook, golden eval отчёт O-06 как gate | AI-02, AI-04 |
| AI-19 | Paywall, онбординг-цель, entitlement | `/plans` (Free selected + рекомендуемый), `GoalOnboarding`, usage в профиле, `entitlement_inbox` контракт, trial по AI-intent, upsell-состояния; права из return URL — запрещено тестом | #78/#79, AI-01 |

Отложено отдельными эпиками: генерация видео; AI-оценка `ai-semantic` с dispute flow (seam есть); голосовой ответ в Study (после eval STT).

Критический путь: `AI-00 → {AI-01, AI-02, AI-03} → AI-04 → AI-05 → AI-06 → {AI-08, AI-11, AI-10, AI-09, AI-13}`. Первый пользовательский результат — после AI-06 (материал из prompt, одобренный в колоду, на stub/synthetic). AI-07, AI-12, AI-UI — параллельно с фазой 1.

---

## 9. Вопросы владельцу (с рекомендацией)

**Блокирующие (нужны до AI-00):**

1. **Хостинг и ПД.** Где primary БД с аккаунтами и учебной историей российских пользователей? Рекомендация: РФ-хостинг (Timeweb/Selectel/VK Cloud), stateless AI-шлюз, зарубежный узел — только по ответу юриста. Финляндия — только после юридического заключения.
2. **Production-маршрут текста.** Direct DeepSeek (обучается на данных, КНР «адекватная» → уведомление РКН) + GigaChat fallback? Или OpenRouter ZDR (если аккаунт доступен)? Рекомендация: direct DeepSeek V4.1 Flash + GigaChat; OpenRouter — опциональный адаптер.
3. **Boot 4.1.** Миграция до AI-работ или параллельно? Рекомендация: параллельно (AI-код переносим), но до production.
4. **Реактивация #77.** Подтверждаете переписать #77 как активный эпик с этой декомпозицией и обновить `product-direction` (D-04) и economics-док (299 → 449/990/1900)?

**Продуктовые:**

5. **Имя.** «Мнемозина» в диалогах + «ИИ» в подписях? (рекомендация — да, без аватара)
6. **Один бар + fair-use + caps** (вариант C)? (рекомендация — да)
7. **STT бесплатно** как fair-use 30 мин/мес на Free, self-host в РФ? (рекомендация — да; «безлимит» — нет)
8. **Тиры на старте.** Plus 449 + Pro 990 сразу; Max 1 900/1 990 — когда готовы TTS и картинки? Trial 14 дней без карты по AI-intent?
9. **Видео** — отложить полностью? **Генерация картинок** — только Pro/Max после поиска по стокам?
10. **Yandex Search API** для фактчека — распространяется ли ваше исключение Yandex на поиск (не генеративный)? Рекомендация: разрешить только Search API.
11. **Лимит колод** — не вводить на старте AI-монетизации? Если вводить — свои колоды, Free ≥10, копии не считать.
12. **Хаб колоды.** Перенести список материалов на страницу колоды (удалив `/materials` как отдельный экран) и спрятать форму метаданных за «Изменить»?
13. **«Похоже на»** — по умолчанию «как в колоде»; один закреплённый образец в расширенных?
14. **Toast 6 с** для «Готово», в Study — до естественной паузы?
15. **Заметки по умолчанию «материал на заметку»**, с опцией «объединить в один»?
16. **Approve требует все медиа `READY` или удалены** (рекомендация), или допускается публикация с обрабатываемыми, как в #76?
17. **Маркировка «Создано с ИИ»** в интерфейсе с первого дня? (рекомендация — да; origin в данных)
18. **Сессия Мастерской живёт 30 дней** с последней активности?
19. **Free selected на paywall** + визуально выделенный рекомендуемый тир? A/B позже?

---

## 10. Глоссарий

| Термин | Значение |
|---|---|
| Capability gate | серверный fail-closed флаг доступности функции: `flag && provider bean`; отдаётся `GET /api/capabilities`; не квота |
| Provider port / adapter | интерфейс одной возможности (`TextGeneration`, `Transcription`, …) и его реализация под конкретный API |
| Stub provider | детерминированная заглушка для local/CI; CI никогда не ходит к реальным провайдерам |
| Мастерская / `GenerationSession` | один запуск генерации в колоде: настройки, источники, бюджет, набор артефактов |
| `GenerationArtifact` | нить одного будущего материала или упражнения с собственными ревизиями и инструкциями («сессия на материал») |
| `ArtifactRevision` / `ArtifactTurn` | неизменяемый снимок предложения / запись одной инструкции пользователя и её результата |
| `GenerationStep` | durable job: lease, heartbeat, retry, deadline, cancel |
| Lease / fencing token | аренда шага worker'ом с токеном; опоздавший worker не перезапишет результат |
| Fan-out / assemble | параллельный запуск медиа-шагов после текста; «сборка» — производное условие готовности всех слотов |
| Media slot | место медиа в AST с заранее выделенным `assetId` и спецификацией генерации |
| MBM (Mnema Block Markup) | подмножество Markdown + директивы, которое пишет модель и детерминированно компилирует сервер в native-v1 |
| Block handle | короткая метка блока (`b3`, `m2:b3`), видимая модели вместо UUID |
| Repair | повторный запрос с перечнем ошибок валидации; не больше одного, затем strong route |
| Self-evaluation | прогон сгенерированного ключа упражнения через тот же `AttemptEvaluation`, что в Study |
| Handoff | передача текущей ревизии артефакта в обычный `EditingDraft` («Править самому») |
| Re-pin | серверная перепривязка упражнения к новой ревизии материала по стабильным node IDs без LLM |
| Process role | режим запуска одного jar: `api`, `worker` или `all` |
| Model route | server-owned упорядоченный список provider+model для класса задач с fallback |
| Prompt caching / prefix cache | скидка провайдера за совпадающий префикс промпта (DeepSeek cache-hit $0.003–0.006 за 1M) |
| Thinking mode | режим рассуждений модели; у DeepSeek включён по умолчанию, для генерации выключается |
| Structured output / strict JSON | режим, где модель обязана вернуть JSON по схеме; у DeepSeek `json_object` без enforced-схемы, strict tools — beta |
| Credit | внутренняя cost-weighted единица usage: 1 credit = 0,10 ₽ p95-себестоимости |
| Rate card | версионированная таблица перевода стоимости провайдера в credits |
| Preflight estimate | оценка стоимости операции до запуска (p50/p95) |
| Reserve → settle → release | удержание бюджета при старте, списание по факту, возврат остатка |
| Fair-use | лимит вне основного бара (STT, AI-проверка), не блокирующий учёбу |
| Count-cap / hard cap | штучный жёсткий потолок на дорогую capability внутри тарифа |
| Burst-cap | дневной предел расхода (≤35% месячного бара) против скриптов и шаринга |
| Entitlement snapshot | права аккаунта (план, период, allowances), пришедшие из billing; никогда из браузера |
| Event cursor | монотонный `seq` событий сессии для polling без дублей |
| Circuit breaker / kill-switch | автоматическое отключение провайдера после серии сбоев / ручное отключение конфигом без релиза |
| ZDR | zero data retention: endpoint провайдера не хранит и не обучается на данных |
| Golden eval | offline набор fixtures для выбора модели и gate включения; метрика — cost per accepted item |
| Link allowlist | список URL, разрешённых в сгенерированном материале (источники RESEARCH + ссылки из заметок) |
| SSRF | подмена URL для доступа к внутренним адресам; защита — host allowlist и DNS-фильтр |
| Toggletip | кнопка «ⓘ», открывающая пояснение по нажатию (доступная замена hover-tooltip) |
| Segmented control | группа взаимоисключающих вариантов, внешне сегменты, семантически native radio |
| Hold-to-delete | существующий компонент: клик → удержание 3 с → удаление |
| Generation effect | эффект: самостоятельно созданное запоминается лучше прочитанного (d≈0,40) |
| Default effect | эффект выбранного по умолчанию варианта (d=0,68) |
| НПД / УСН | налог на профессиональный доход (4%, потолок 2,4 млн ₽/год) / упрощённая система |
| 152-ФЗ ст. 18 ч. 5 | запрет первичной записи ПД граждан РФ в зарубежных БД (с 01.07.2025) |
| Трансграничная передача | передача ПД за рубеж; уведомление РКН до начала; «адекватные» страны по Приказу № 128 (есть Китай, нет США) |
