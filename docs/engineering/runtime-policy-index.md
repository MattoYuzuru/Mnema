---
artifact:
  id: runtime-policy-index
  type: reference
  title: "Learning runtime policy index"
  status: current
  created_at: "2026-09-28"
  updated_at: "2026-10-02"
  owners: ["learning-api", "web"]
  source_tasks: ["GitHub Issue #241", "GitHub Epic #76", "GitHub Issue #284"]
---

# Изменяемые политики Learning

Эта страница отвечает на вопрос «какую настройку менять?» по **владельцу и
области действия**. Значения по умолчанию сохраняют уже принятое поведение.
Проверка границ происходит при старте backend или при создании bounded client
policy. Изменение policy влияет только на новые операции; опубликованные
ревизии и сохранённые evidence не переписываются. Media transport (#235),
processor (#236), playback (#239) и offline manifest (#242) имеют отдельные
namespaces.

| Ключ / место | Владелец и влияние | Единица; default; допустимый диапазон |
|---|---|---|
| `learning.authoring.draft.recovery-window` | Editing draft: expiry после create/save | Duration; `P30D`; 1–90 дней |
| `learning.authoring.draft.max-active-per-account` | Editing draft: число активных черновиков владельца | Count; `200`; 1–1000 |
| `learning.authoring.draft.max-total-bytes-per-account` | Editing draft: суммарные JSON bytes владельца | Bytes; `20971520` (20 MiB); 1 MiB–1 GiB |
| `learning.authoring.capture.max-active-per-account` | Capture notes: число заметок владельца | Count; `10000`; 1–100000 |
| `learning.authoring.capture.max-total-bytes-per-account` | Capture notes: суммарные bytes владельца | Bytes; `67108864` (64 MiB); 1 MiB–1 GiB |
| `learning.media.unattached-ready-hold` | Media catalog: READY asset без revision/draft ref | Duration; `P7D`; 1–30 дней |
| `learning.storage.max-staging-lease` | Immutable native storage: максимальная временная pin lease | Duration; `PT1H`; 1 ms–1 день |
| `learning.storage.orphan-grace` | Immutable native storage: grace до sweep | Duration; `PT10M`; 1 ms–7 дней |
| `learning.storage.lock-timeout` | Immutable native storage: ожидание lock | Duration; `PT1S`; 1 ms–5 секунд |
| `learning.media.upload.max-image-bytes` | Загрузка изображения; не влияет на аудио или видео | Bytes; `67108864` (64 MiB); 1 B–5 TiB, с ограничением owner quota |
| `learning.media.upload.max-audio-bytes` | Загрузка аудио; не влияет на изображение или видео | Bytes; `536870912` (512 MiB); 1 B–5 TiB, с ограничением owner quota |
| `learning.media.upload.max-video-bytes` | Загрузка видео, включая MOV с телефона; длительность проверяет processor отдельно | Bytes; `4294967296` (4 GiB); 1 B–5 TiB, с ограничением owner quota |
| `learning.media.upload.max-reserved-bytes` | Сумма незавершённых загрузок владельца | Bytes; `8589934592` (8 GiB); не меньше каждого per-kind лимита |
| `learning.media.upload.max-active-uploads` | Число одновременных сессий владельца | Count; `3`; 1–100 |
| `learning.media.upload.multipart-threshold` | Порог S3 multipart | Bytes; `104857600` (100 MiB); 5 MiB–5 GB, не выше max-video-bytes |
| `learning.media.upload.part-size` | Размер multipart части | Bytes; `16777216` (16 MiB); 5 MiB–5 GB, ≤10000 частей на asset |
| `learning.media.upload.url-ttl` | Срок signed PUT URL | Duration; `PT15M`; >0–1 h |
| `learning.media.upload.session-ttl` | Срок незавершённой upload session | Duration; `PT24H`; >url-ttl–7 d |
| `learning.media.upload.storage-timeout` | Таймаут S3 transport | Duration; `PT10M`; 1–30 min |
| `learning.media.upload.finalize-lease` | Время claim при sealing source | Duration; `PT15M`; >storage-timeout–1 h |
| `learning.media.upload.cleanup-grace` | Ожидание после истечения URL до очистки staging | Duration; `PT15M`; 0–2 h |
| `learning.media.upload.cleanup-initial-delay` / `cleanup-interval` | Частота фоновой очистки незавершённых transfer | Duration; `PT1M` / `PT5M`; Spring scheduler duration, положительное значение |
| `learning.media.processing.max-video-duration` | Проверка длительности загруженного видео после sealing; не меняет byte cap | Duration; `PT5M`; >0–5 min |
| `learning.media.processing.max-audio-duration` | Проверка длительности аудио после sealing; не меняет видео | Duration; `PT1H`; >0–1 h |
| `learning.media.processing.max-parallel` | Одновременные изолированные worker jobs на инстанс | Count; `2`; 1–4 |
| `learning.media.processing.max-attempts` | Автоматические обработки одного поколения до FAILED_RETRYABLE | Count; `5`; 1–10 |
| `learning.media.processing.worker-timeout` | Максимум одного запуска worker | Duration; `PT30M`; 1–30 min |
| `learning.media.processing.lease` | Claim обработчика в БД | Duration; `PT2M`; 1–15 min |
| `learning.media.processing.heartbeat` | Обновление claim | Duration; `PT30S`; 5 s и 2×heartbeat < lease |
| `learning.media.processing.retry-base` / `retry-maximum` | Exponential retry delay | Duration; `PT1M` / `PT30M`; base ≥10 s, max ≥base и ≤2 h |
| `learning.media.playback.url-ttl` | Срок signed GET для READY playback/poster/download | Duration; `PT1H`; 5 min–2 h |
| `learning.media.manifest.retention` | Offline manifest: удержание immutable snapshot и связанных bytes | Duration; `P90D`; 1–365 дней |
| `learning.media.manifest.max-references` | Offline manifest: максимум ссылок на revisions/variants в snapshot | Count; `50000`; 1–50000 |
| `learning.media.manifest.max-assets` | Offline manifest: максимум уникальных assets в snapshot | Count; `10000`; 1–`max-references` |
| `learning.media.manifest.max-document-bytes` | Offline manifest: размер сериализованного документа | Bytes; `8388608` (8 MiB); 1 KiB–8 MiB |
| `learning.media.gc.enabled` | Физическая очистка недостижимых S3 объектов; включать после локальной настройки bucket | Boolean; `false` |
| `learning.media.gc.grace` | Время после первого безудержного сканирования до удаления | Duration; `P1D`; 1–30 дней |
| `learning.media.gc.scan-gap` | Минимальный промежуток между двумя успешными сканами | Duration; `PT1H`; ≥1 час и меньше grace |
| `learning.media.gc.scan-batch` / `delete-batch` | Число ledger keys на скан и удалений за проход | Count; `32` (1–100) / `4` (1–20) |
| `learning.media.gc.delete-lease` | Время владения claim физического удаления | Duration; `PT15M`; 15–60 минут |
| `learning.media.gc.retry-delay` | Повтор после неопределённого результата S3 DELETE | Duration; `PT1H`; 15 минут–1 день |
| `learning.notifications.retention` | Центр уведомлений: срок жизни уведомления; фиксируется в `expires_at` при публикации | Duration; `P30D`; 1–90 дней, целое число секунд |
| `learning.notifications.max-per-account` | Центр уведомлений: лимит на аккаунт; публикация N+1 вытесняет самое старое | Count; `200`; 10–1000 |
| `learning.notifications.params-max-bytes` | Центр уведомлений: размер `params` одного уведомления (таблица дополнительно держит 4096) | Bytes; `4096`; 256–4096 |
| `learning.notifications.cleanup-initial-delay` / `cleanup-interval` | Центр уведомлений: частота удаления истёкших строк (до 20 пачек по 500 за запуск) | Duration; `PT5M` / `PT1H`; положительное значение Spring scheduler |

S3 endpoint, region, bucket и credentials находятся в том же namespace
`learning.media.upload`, но относятся к подключению, а не к пользовательским
лимитам. `allow-loopback-http` разрешает HTTP только для локального MinIO.
`GET /api/media-assets/upload-policy` возвращает авторизованному browser
актуальные per-kind byte caps для проверки перед резервированием asset;
`MediaUploadSettings.validate` остаётся обязательной серверной проверкой.
Медиа transport принимает файл как входные байты: фактический codec и
длительность проверяет processor. Изменение `max-video-bytes` не меняет
допустимые пять минут; это отдельная настройка обработки. Processor включается
отдельным `learning.media.processing.enabled` (default `false`), после настройки
локальных Docker image/binary и work-root. Эти параметры задают способ запуска,
а не продуктовый лимит. Browser media polling: очередь upload 2 одновременных
transfer, 2–15 s backoff; reader 2–15 s для pending и renewal до истечения URL;
список колод — 45 s на видимой вкладке. Все три клиента имеют разные owners.
Manifest и GC contracts описаны в
[`media-offline-manifest.md`](media-offline-manifest.md) и
[`media-gc.md`](media-gc.md). GC scan interval задаётся отдельно
`learning.media.gc.scan-interval` (`PT10M`) и влияет только на частоту scheduler.

`max-active-per-account` draft допускает рост до 1000. Web list aggregation
запрашивает страницы по 20 и обнаруживает превышение 50 страниц,
вместо успешного ответа с неполным списком. При изменении верхней
границы draft count нужно одновременно обновить этот клиентский контракт.

## AI-слой: ключи, которых ещё нет (contract only)

Ключи этого раздела — **contract only**: в Learning их пока нет, ни одно поведение не заявлено.
Значения взяты из [AI generation platform](../architecture/ai-generation-platform.md) и
[контрактов](../../contracts/generation/README.md); диапазоны и проверку при старте определит
указанная задача. До реализации ключ нельзя считать настройкой, которую можно менять.

| Ключ / место | Владелец и влияние | Единица; default; допустимый диапазон |
|---|---|---|
| `learning.generation.session-retention` | Мастерская: жизнь сессии от последней активности; **contract only — implemented by AI-05 (#288)** | Duration; `P30D`; диапазон — AI-05 |
| `learning.generation.session-expiry-warning` | Уведомление `GENERATION_SESSION_EXPIRING` до `expires_at`; **contract only — AI-05 (#288)** | Duration; `P3D`; диапазон — AI-05 |
| `learning.generation.event-retention-after-close` | Хранение `generation_event` после закрытия сессии; **contract only — AI-04 (#287)** | Duration; `P1D`; диапазон — AI-04 |
| `learning.generation.max-active-sessions-per-account` | Admission: активные сессии владельца (PLANNING, PLAN_READY, RUNNING и REVIEW с PROPOSED/REVISING/STALE; `RESOURCE_LIMIT_EXCEEDED`); **contract only — AI-04 (#287)** | Count; `3`; диапазон — AI-04 |
| `learning.generation.max-artifacts-per-session` | Admission: артефакты сессии `MATERIALS`; **contract only — AI-04 (#287)** | Count; `20`; диапазон — AI-04 |
| `learning.generation.max-exercise-targets` / `max-exercises-per-target` / `max-exercises-per-session` | Сессия `EXERCISES`: материалы, упражнения на материал и на сессию; выше — `422` без усечения; решение владельца 2026-10-02; **contract only — AI-13 (#291)** | Count; `20` / `10` / `60`; диапазон — AI-13 |
| `learning.generation.max-bulk-approval` | Артефактов в одной bulk-публикации; **contract only — AI-05 (#288)** | Count; `20`; диапазон — AI-05 |
| `learning.generation.max-revisions-per-artifact` / `max-turns-per-artifact` | Ревизии и инструкции на артефакт; **contract only — AI-04/AI-11 (#287, #293)** | Count; `30` / `50`; диапазон — AI-04 |
| `learning.generation.max-instruction-chars` | Длина инструкции правки или запроса; **contract only — AI-04 (#287)** | UTF-16 units; `2000`; диапазон — AI-04 |
| `learning.generation.step.text-draft-timeout` / `edit-timeout` / `tts-timeout` / `image-timeout` | Таймауты шагов; короче провайдерских; **contract only — AI-04 (#287)** | Duration; `PT6M` / `PT2M` / `PT2M` / `PT3M`; диапазон — AI-04 |
| `learning.generation.step.assess-deadline` | Deadline шага `ASSESS`, затем `UNAVAILABLE`; **contract only — AI-20 (#292)** | Duration; `PT20S`; диапазон — AI-20 |
| `learning.generation.step.max-run` | Максимум одного запуска шагов; **contract only — AI-04 (#287)** | Duration; `PT1H`; диапазон — AI-04 |
| `learning.generation.concurrency.<capability>` | Семафор вызовов на инстанс: text, tts, image, video, search, assess; **contract only — AI-04 (#287)** | Count; `16`, `4`, `2`, `1`, `4`, `16`; диапазон — AI-04 |
| `learning.generation.progress.checkpoint-interval` | Минимальный интервал `BLOCKS_APPENDED`; **contract only — AI-04 (#287)** | Duration; `PT0.75S`; диапазон — AI-04 |
| `learning.runtime.roles` | Роль процесса: `api`, `worker` или `all`; **contract only — AI-17 (#300)** | Enum; `all`; значения из архитектуры |
| `learning.ai.routes.<route>` | Server-owned маршрут capability к провайдеру и модели (например `text-fast`); fallback только на 429/5xx/timeout/invalid-after-repair; **contract only — AI-02 (#282)** | Строка provider/model; default — Stub; без секретов |
| `learning.ai.providers.<id>.enabled` | Kill-switch провайдера без релиза; **contract only — AI-02 (#282)** | Boolean; диапазон — AI-02 |
| `learning.ai.timeout.connect` / `idle-stream` | Таймауты HTTP-адаптеров; **contract only — AI-02 (#282)** | Duration; `PT5S` / `PT60S`; диапазон — AI-02 |
| `learning.ai.circuit.failure-threshold` / `window` / `open-duration` | Circuit breaker на `(provider, capability)`; **contract only — AI-02 (#282)** | Count / Duration; `5` / `PT60S`, открыт `PT30S`; диапазон — AI-02 |
| `learning.ai.retry.max-rate-limit-retries` / `max-transient-retries` | Повторы шага: 429 с `Retry-After`, затем transient; **contract only — AI-02 (#282)** | Count; `6` / `3`; диапазон — AI-02 |
| `learning.ai.context.max-input-tokens` | Жёсткий потолок входа генерации Flash non-thinking (рабочая зона 12–25k); **contract only — AI-04 (#287)** | Tokens; `32000`; диапазон — AI-04 |
| `learning.usage.rate-card-version` | Активный rate card; новая версия не действует ретроактивно; **contract only — AI-01 (#281)** | Строка; `rc-v1`; версии — [rate-card](../../contracts/usage/README.md) |
| `learning.usage.daily-burst-fraction` | Доля месячного бара на платных планах, которую можно **списать** за календарный день (не зарезервировать); решение владельца 2026-10-02; **contract only — AI-01 (#281)** | Fraction; `0.35`; диапазон — AI-01 |
| `learning.usage.free-weekly-portions` | Порции бара Free: накапливаются в месяце, первая — 1-го числа, далее по понедельникам; решение владельца 2026-10-02; **contract only — AI-01 (#281)** | Credits list; `13,13,12,12` (сумма = бар 50) |
| `learning.usage.calendar-zone` | Часовой пояс границ дня, недели и месяца всех окон usage; решение владельца 2026-10-02; **contract only — AI-01 (#281)** | IANA zone; `Europe/Moscow` |
| `learning.usage.low-threshold-percent` | Порог `USAGE_LOW` и подсказки fair-use; **contract only — AI-01 (#281)** | Percent; `80`; диапазон — AI-01 |
| `learning.usage.reservation-ttl` | TTL осиротевшей reservation; фактический срок = min(TTL, конец периода); **contract only — AI-01 (#281)** | Duration; значение определит AI-01 |
| `learning.usage.entitlement.source` | `EntitlementSource`: конфигурация до billing (#79); **contract only — AI-01 (#281)** | Enum; `config`; диапазон — AI-01 |

Секреты провайдеров не являются policy: только имена env из архитектуры (§9), значения — в окружении `worker`.
Клиентские интервалы опроса (события сессии 1 с / 5–15 с, уведомления 30–60 с / 10 с) — client policy
контрактов [events](../../contracts/generation/events.json) и
[notifications](../../contracts/notifications/README.md), а не Spring-ключи.

## Аудит ещё не вынесенных значений

Следующие числа являются кандидатами с узкой областью. Их нельзя объединять
в одну глобальную `MAX_DURATION`/`MAX_BYTES` настройку.

| Кандидат сейчас | Область и зависимость | Следующее действие |
|---|---|---|
| Study session lifetime 24 h; compact receipt retention 24 h; browser recovery TTL 24 h | Session/attempt/recovery должны оставаться согласованы | Ввести пару session/receipt policy с проверкой retention ≥ retry window, затем client expiry contract test |
| Study raw response retention 30 d | Точно 30 дней обещаны в `contracts/study/README.md` | Менять только через новую версию evidence/retention contract, не как свободный operator knob |
| Study presets Quick 10/2, Standard 20/5, Replay/Practice 20 | Client policy и видимый текст, backend принимает диапазон 1–100 | Назвать пресеты по intent и проверить `maxNewObjectives ≤ maxPresentations`; обновлять UI copy вместе с policy |
| Study PREPARING poll 20 × 500 ms | Только экран сессии; ≈10 s до сообщения о задержке | Назвать отдельно interval и max wait; bounded attempts выводить из них |
| Каталог: preparation lease 1 min | Item/Exercise/Deck preparation; не длиннее `learning.storage.max-staging-lease` | Вынести scoped lease после проверки latency и rollback конкурирующих publish |
| Retention worker batch 500 | DB lock/transaction duration; cadence уже configurable | Измерить latency, затем назвать scoped batch-size |
| Browser own-deck recovery 24 h/5 entries/64 KiB/16 KiB | Local storage safeguards | Документировать/именовать в browser recovery policy отдельно от Study |

## Не runtime policy

`NativeDocumentReader` и Angular renderer limits (1 MiB, 10 000 nodes,
depth 32, scalar 32 KiB), storage object layout, counted-page dimensions,
exercise response bounds и pagination maximum являются версионированными
wire/storage invariants. Их меняют вместе с контрактом, fixtures и обеими
сторонами API. `BaselineReducer` interval ladder принадлежит versioned learning
algorithm; изменение требует нового policy version/hash и migration evidence.
Planner query/memory bounds и CSS tokens имеют другие причины изменения.
Повторяющийся `@Transactional(timeout=10)` не превращается в один глобальный
таймаут без измерений блокировок.

## Проверка и rollback

Неверное значение backend policy приводит к ошибке старта. Тесты должны
проверять default, края диапазона и независимость соседних областей. Если
новый предел вызывает нагрузку или ломает UX, вернуть предыдущий default для
новых операций; существующие revision/attempt IDs и format versions не менять.
Среда только локальная, rollout и восстановление серверных данных здесь не
заявлены.
