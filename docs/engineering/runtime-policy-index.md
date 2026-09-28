---
artifact:
  id: runtime-policy-index
  type: reference
  title: "Learning runtime policy index"
  status: proposed
  created_at: "2026-09-28"
  updated_at: "2026-09-28"
  owners: ["learning-api", "web"]
  source_tasks: ["GitHub Issue #241", "GitHub Epic #76"]
---

# Изменяемые политики Learning

Эта страница отвечает на вопрос «какую настройку менять?» по **владельцу и
области действия**. Значения по умолчанию сохраняют уже принятое поведение.
Проверка границ происходит при старте backend или при создании bounded client
policy. Изменение policy влияет только на новые операции; опубликованные
ревизии и сохранённые evidence не переписываются. Вводимый отдельно media
transport (#235), processor (#236) и playback (#239) имеют отдельные namespaces.

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

S3 endpoint, region, bucket и credentials находятся в том же namespace
`learning.media.upload`, но относятся к подключению, а не к пользовательским
лимитам. `allow-loopback-http` разрешает HTTP только для локального MinIO.
Медиа transport принимает файл как входные байты: фактический codec и
длительность проверяет processor. Изменение `max-video-bytes` не меняет
допустимые пять минут; это отдельная настройка обработки. Processor включается
отдельным `learning.media.processing.enabled` (default `false`), после настройки
локальных Docker image/binary и work-root. Эти параметры задают способ запуска,
а не продуктовый лимит. Browser media polling: очередь upload 2 одновременных
transfer, 2–15 s backoff; reader 2–15 s для pending и renewal до истечения URL;
список колод — 45 s на видимой вкладке. Все три клиента имеют разные owners.

`max-active-per-account` draft допускает рост до 1000. Web list aggregation
запрашивает страницы по 20 и обнаруживает превышение 50 страниц,
вместо успешного ответа с неполным списком. При изменении верхней
границы draft count нужно одновременно обновить этот клиентский контракт.

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
