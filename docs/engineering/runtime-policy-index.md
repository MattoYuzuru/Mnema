---
artifact:
  id: runtime-policy-index
  type: reference
  title: "Learning runtime policy index"
  status: current
  created_at: "2026-09-28"
  updated_at: "2026-10-02"
  owners: ["learning-api", "web"]
  source_tasks: ["GitHub Issue #241", "GitHub Epic #76", "GitHub Issue #284", "GitHub Issue #281"]
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
| `learning.usage.rate-card-version` | Usage: активный rate card; новая версия не действует ретроактивно на ledger и reservations с прежней версией. Класспат-копия [контракта](../../contracts/usage/rate-card-v1.json) — `usage/rate-card-v<N>.json` | Строка `rc-v<N>`; `rc-v1`; версия без ресурса — отказ при старте |
| `learning.usage.calendar-zone` | Usage: часовой пояс границ дня, недели (понедельник 00:00) и месяца всех окон; решение владельца 2026-10-02 | IANA zone; `Europe/Moscow` |
| `learning.usage.daily-burst-fraction` | Usage: доля месячного бара платных планов, которую можно **списать** за календарный день; reserve и settle не отказывают из-за него, это сигнал планировщику шагов (`UsageLedger.dailyDebitRoom`); решение владельца 2026-10-02 | Fraction; `0.35`; (0, 1] |
| `learning.usage.free-weekly-portions` | Usage: порции бара Free; накапливаются в месяце, первая открывается 1-го числа, далее по понедельникам; сумма обязана равняться бару Free (50), иначе отказ при старте; решение владельца 2026-10-02 | Credits list; `13,13,12,12`; 1–5 порций, каждая ≥ 1 |
| `learning.usage.low-threshold-percent` | Usage: первый порог `USAGE_LOW` и подсветки fair-use (`warn` — строго выше порога); 90 и 100 фиксированы | Percent; `80`; 50–89 |
| `learning.usage.reservation-ttl` | Usage: TTL осиротевшей reservation; фактический срок = min(TTL, конец периода). Решение AI-01: с запасом больше границы запуска шагов `PT1H`; живую reservation продлевает `UsageLedger.renew` (тот же TTL) | Duration; `PT2H`; `PT1M`–`P1D`, целое число секунд |
| `learning.usage.expiry-interval` / `expiry-initial-delay` | Usage: частота воркера, который возвращает осиротевшие reservations и удаляет счётчики окон старше 90 дней (до 20 пачек по 200 за запуск) | Duration; `PT1M` / `PT1M`; положительное значение Spring scheduler |
| `learning.usage.entitlements.default-plan` | Usage: `EntitlementSource` до billing (#79) — план всех аккаунтов; на локальном стенде с одним владельцем это способ получить платный план | Enum `FREE`/`PLUS`/`PRO`/`MAX`; `FREE` |
| `learning.usage.entitlements.overrides.<accountUuid>` | Usage: план конкретного аккаунта (UUID субъекта токена); не меняет остальных | Enum `FREE`/`PLUS`/`PRO`/`MAX`; не задан; UUID в каноническом виде |
| `learning.generation.max-exercise-targets` / `max-exercises-per-target` / `max-exercises-per-session` | Estimate (#281) и далее admission AI-13 (#291): материалы, упражнения на материал и на сессию; выше — `422 RESOURCE_LIMIT_EXCEEDED` без усечения; решение владельца 2026-10-02 | Count; `20` / `10` / `60`; 1–1000 |
| `learning.generation.max-artifacts-per-session` / `max-sources` | Estimate (#281) и далее admission AI-04 (#287): артефакты сессии `MATERIALS` и pinned источники спеки; выше — `422 RESOURCE_LIMIT_EXCEEDED` (`ARTIFACTS_PER_SESSION` / `SOURCES`) | Count; `20` / `20`; 1–1000 |
| `learning.features.<capability>.enabled` | Переключатель capability: `ai-assessment`, `speech-to-text`, `ai-generation`, `text-to-speech`, `image-search`, `image-generation`, `video-generation`, `web-search`. Флаг сам ничего не включает: нужен адаптер (`flag && adapter configured`) | Boolean; `false` |
| `learning.ai.provider` | `stub` выбирает детерминированный Stub на всех текстовых маршрутах (local/CI) и пишет WARN при старте; только так Stub регистрируется — запись `stub:...` в маршруте — ошибка конфигурации. Пусто — маршруты ниже | `` / `stub`; пусто |
| `learning.ai.routes.text-fast` / `text-strong` / `assess` | Упорядоченные `provider:model`; первая пригодная запись основная, остальные — fallback (429/5xx/timeout/invalid после repair). Запись без ключа пропускается; неизвестная модель без цены — ошибка старта | List; `deepseek:deepseek-flash,gigachat:GigaChat-2` / `deepseek:deepseek-v4-pro` / `deepseek:deepseek-flash,gigachat:GigaChat-2` |
| `learning.ai.providers.<id>.enabled` | Kill-switch провайдера (`deepseek`, `gigachat`, `openrouter`), без релиза | Boolean; `true` |
| `learning.ai.providers.<id>.base-url` | Базовый URL OpenAI-compatible API; только `https` (`http` — только loopback), без учётных данных в URL | URL; DeepSeek `https://api.deepseek.com`, GigaChat `https://gigachat.devices.sberbank.ru/api/v1`, OpenRouter `https://openrouter.ai/api/v1` |
| `learning.ai.providers.deepseek.api-key` / `openrouter.api-key` / `gigachat.auth-key` | Секреты только из окружения: `MNEMA_AI_DEEPSEEK_API_KEY`, `MNEMA_AI_OPENROUTER_API_KEY`, `MNEMA_AI_GIGACHAT_AUTH_KEY`; значения не логируются | String; пусто |
| `learning.ai.providers.gigachat.auth-url` / `scope` | OAuth-обмен ключа GigaChat на токен (~30 мин, кэшируется) | URL / String; `https://ngw.devices.sberbank.ru:9443/api/v2/oauth` / `GIGACHAT_API_PERS` |
| `learning.ai.models[n].{provider,id,hit-micros-per-million,miss-micros-per-million,output-micros-per-million}` | Таблица цен: микродоллары США за 1M токенов; стоимость вызова считается по usage провайдера | Long ≥0; DeepSeek peak-цены (flash 6000/300000/1200000, v4-pro 44000/1320000/3960000), GigaChat-2 ≈765000 |
| `learning.ai.transport.connect-timeout` / `idle-stream` / `max-body-bytes` / `first-byte` | Connect, молчание SSE-потока, потолок тела ответа и ожидание заголовков ответа (молчащий провайдер даёт `TIMEOUT` с остатком deadline, и router идёт на fallback; для нестримовых ответов дольше `first-byte` значение нужно поднять) | Duration `5s` / `60s`; Bytes `4194304`; 1 KiB–64 MiB; Duration `60s` |
| `learning.ai.retry.transient-attempts` / `rate-limit-retries` | Попытки на один маршрут: 5xx/сеть/timeout (до fallback) и повторы после 429 | Count; `3` (1–10) / `6` (0–10) |
| `learning.ai.retry.backoff-base` / `backoff-cap` / `rate-limit-max-wait` | Full jitter: ожидание в `[0, min(cap, base·2^n)]`, не меньше `Retry-After`; `Retry-After` больше `rate-limit-max-wait` или остатка deadline возвращается вызывающему без ожидания | Duration; `500ms` / `8s` / `30s` |
| `learning.ai.breaker.failure-threshold` / `window` / `open-for` | Circuit breaker на `(provider, capability)`: подряд неуспехов за окно открывают его, затем один пробный вызов | `5` / `60s` / `30s` |
| `learning.ai.permits.{text,tts,image,image-search,video,search,assess}` / `queue-wait` | Семафоры capability на инстанс и ожидание свободного места; сверх — `RATE_LIMITED` | Count `16/4/2/4/1/4/16`; Duration `2s` |
| `learning.ai.budget.{text,assess,tts,image,image-search,video,search}-micros` / `zone` / `cache-ttl` | Глобальный дневной бюджет capability (сумма журнала по всем инстансам, микродоллары; 0 = без лимита); исчерпан — `TEMPORARILY_UNAVAILABLE`, вызова нет. День начинается в `zone`; значение кэшируется на `cache-ttl` | Long; `10000000` (text) / `5000000` (остальные); `Europe/Moscow`; `10s` |
| `learning.ai.user-key.secret` / `key-id` | Секрет HMAC opaque user key (`MNEMA_AI_USER_KEY_SECRET`, ≥16 символов; без дефолта) и его идентификатор для ротации | String; пусто / `k1` |
| `learning.ai.prompt.version` / `max-input-tokens` / `working-input-tokens` | Активная версия prompt library; жёсткий потолок входа (оценка) и рабочий размер, выше которого сборщик только помечает | `v1` / `32000` / `25000` |
| `learning.ai.call-retention` / `cleanup-initial-delay` / `cleanup-interval` | Хранение строк `ai_provider_call` и частота очистки (до 20 пачек по 500 за запуск) | Duration; `P90D` (1–365 дней) / `PT10M` / `PT6H` |
| `learning.runtime.roles` | Роль процесса AI-слоя (архитектура A1): `api` обслуживает HTTP и создаёт работу, `worker` исполняет шаги и держит ключи провайдеров, `all` — оба (локально и в первой поставке). Диспетчер шагов (`StepDispatcher`) существует только для `worker`/`all`; неизвестное значение — отказ при старте. Окружение: `MNEMA_RUNTIME_ROLES` | `api` / `worker` / `all`; `all` |
| `learning.generation.session-retention` | Generation: `expires_at` сессии = последняя активность + срок; он же граница media-hold артефактов | Duration; `P30D` |
| `learning.generation.max-active-sessions` | Generation: активные сессии на аккаунт (PLANNING, PLAN_READY, RUNNING, REVIEW с PROPOSED/REVISING/STALE); сверх — `422 RESOURCE_LIMIT_EXCEEDED` (`ACTIVE_SESSIONS`) | Count; `3` (1–100) |
| `learning.generation.max-sources` / `max-artifacts-per-session` / `max-exercise-targets` / `max-exercises-per-target` / `max-exercises-per-session` | Generation (читает usage): лимиты спецификации; сверх — `422 RESOURCE_LIMIT_EXCEEDED` без молчаливого клампа; MATERIALS — до 20 артефактов | Count `20/20/20/10/60` (1–1000) |
| `learning.generation.planner.enabled` | Generation: планировщик (AI-14) не поставляется; `planFirst: true` — `422 SPEC_NOT_SUPPORTED`, пока не включён | Boolean; `false` |
| `learning.generation.usd-rub-rate` | Generation: рублей за доллар; стоимость провайдера (микродоллары) переводится в микрорубли `cost_micros` ledger | Decimal > 0; `85` (приблизительно) |
| `learning.generation.similar-title` | Generation: порог `similarity` (pg_trgm), с которого новый заголовок помечается «Похоже на «…»» в `validation` ревизии | Fraction; `0.55` (0, 1] |
| `learning.generation.worker.lease` / `heartbeat` / `sweep-interval` / `account-cap` / `renew-interval` | Диспетчер: срок lease без heartbeat; период heartbeat (продлевает lease и видит отмену; обязан быть короче lease); sweeper (восстановление lease, claim, 2 с); мягкий лимит RUNNING-шагов на аккаунт; продление reservation работающих сессий | Duration `PT30S` / `PT3S` / `PT2S`; Count `4`; Duration `PT10M` |
| `learning.generation.retention.interval` / `expired-readable` / `warn-before` / `events-after-end` / `batch` | Generation retention (#288): период прохода worker; сколько EXPIRED-сессия остаётся читаемой до purge; за сколько до `expires_at` приходит `GENERATION_SESSION_EXPIRING`; сколько хранятся события завершённой сессии; размер пачки сессий за шаг | Duration `PT10M` / `P1D` / `P3D` / `P1D`; Count `50` (1–1000) |
| `learning.generation.step.max-attempts` / `backoff-base` / `backoff-cap` / `text-draft-deadline` / `max-lifetime` | Повторы шага: claim'ов до отказа (потерянный lease и 429/5xx после исчерпания повторов роутера); backoff с jitter; бюджет одного запуска `TEXT_DRAFT` (≤ PT1H); жизнь шага целиком от первого claim (дальше `FAILED(DEADLINE_EXCEEDED)`); любая задержка requeue ограничена `backoff-cap` | Count `3` (1–20); Duration `PT5S` / `PT2M` / `PT6M` / `PT1H` (≥ `text-draft-deadline`, ≤ 24 ч) |
| `learning.generation.stream.checkpoint-interval` / `max-event-bytes` | Чекпоинты `BLOCKS_APPENDED`: не чаще интервала (0 — на каждой границе блока) и размер блоков одного события (строка журнала держит 32 KiB) | Duration `PT0.75S`; Bytes `24576` (1024–30000) |
| `learning.generation.context.outline-lines` / `latest-materials` / `top-k` / `exemplar-tokens` / `exemplars-total-tokens` / `notes-tokens` / `outline-tokens` | Бюджеты контекста текстового шага (context-and-quality §2.3), оценка токенов: колоды ≤ `outline-lines` материалов — outline целиком, иначе все ★ + последние + top-K по `word_similarity` заголовков (нужен pg_trgm); эталон целиком до `exemplar-tokens`, длиннее — скелет | Count/tokens `200/40/40/2500/6000/12000/5000` |

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
Лимиты хаба колоды (#285) — те же версионированные wire-инварианты, а не Spring-ключи: не более 10
«Эталонов» на колоду, bulk-удаление до 500 материалов за запрос порциями по 100 (одна атомарная публикация на порцию),
семь дней в `dueByDay`. Они зафиксированы в [`contracts/decks/hub.json`](../../contracts/decks/hub.json), а
`EXEMPLAR_LIMIT_REACHED` и `BULK_SELECTION_TOO_LARGE` возвращают значение в поле `limit`.
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
