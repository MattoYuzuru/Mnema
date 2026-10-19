---
artifact:
  id: media-platform
  type: architecture
  title: "Media platform: assets, formats, processing and reader experience"
  status: current
  created_at: "2026-09-28"
  updated_at: "2026-10-19"
  owners: ["project-owner"]
  decision_scope: [media, upload, processing, accessibility, retention]
---

# Медиа в материалах и упражнениях

Автор загружает или записывает медиа, вставляет его в versioned `LearningItem`, публикует
материал и использует его в Browse и Study. Байты лежат в приватном S3-compatible Object
Storage; доступ определяется правами на логический asset и материал. Документ описывает
действующую модель и причины решений. Детали рантайма: [upload transport](../engineering/media-upload-transport.md),
[playback](../engineering/media-playback.md), [offline manifest](../engineering/media-offline-manifest.md),
[reachability и GC](../engineering/media-gc.md), [editor workflow](../engineering/editor-media-workflow.md),
[media worker](../../backend/media-worker/README.md). Формат узлов —
[native-v1](../../contracts/content/native-v1/README.md#rich-block-nodes-epic-76); Study использует media как
содержимое слота, а не как отдельную механику (аудирование строится из `CHOICE`, `MATCH` и
`FREE_RESPONSE`, см. [контракт Study](../../contracts/study/README.md#exercise-mechanics-266)).
Нового v1-пути нет: Learning (`/api/media-assets`) не зависит от удалённых media/core
сервисов и их данных.

## Решения

1. **Входные форматы.** Изображения JPEG, PNG, WebP, GIF; аудио MP3, M4A и WebM/Opus (вход
   браузерной записи); видео MP4, а также MOV/HEVC и WebM как **вход** (телефон и браузерная
   запись). Для каждого видео создаётся MP4 H.264/AAC variant, оригинал сохраняется.
   Обязательная fixture-матрица: HEVC Main/Main10 (с orientation и HDR metadata), WebM
   VP8/Opus и VP9/Opus, MP4 H.264/AAC. Произвольный SVG не принимается: активное содержимое
   требует отдельного безопасного контракта. Тип и размер, известные клиенту, проверяются
   до загрузки; фактический codec отклоняется только после серверной проверки, даже если
   материал с pending asset уже опубликован.
2. **Лимиты.** Видео не длиннее 300 секунд. Начальный лимит размера (по умолчанию 4 GiB) —
   явная политика, а не оценка типичного размера (100 Mb/s × 300 s ≈ 3.49 GiB); production
   задаёт свои пределы ([transport](../engineering/media-upload-transport.md#production-object-storage)).
   Квоты владельца, число параллельных загрузок, resource/time limits обработчика защищают
   от злоупотребления. Значения типизированы в `MediaUploadSettings` и других scoped
   настройках; менять лимит размера не значит менять длительность аудио, Study или GC
   ([индекс политик](../engineering/runtime-policy-index.md)).
3. **Rich-узлы.** Вставляются готовая картинка схемы, редактируемый Mermaid source с
   безопасным preview и нативная таблица; у узлов собственные schemas, renderers и текстовые
   альтернативы. Mermaid никогда не молча превращается в картинку; ошибка диаграммы не
   удаляет исходник.
4. **Публикация во время обработки.** Автор может вставить зарезервированный `assetId` и
   опубликовать материал, пока файл загружается или обрабатывается. Читатель видит состояние,
   открытая вкладка обновляет его сама. Упражнение, для которого медиа — обязательный
   cue/answer, недоступно в Study до `READY`; отсутствие файла не оценивается как ошибка
   ученика. Отклонённый файл даёт автору retry/replace.
5. **Свои controls.** Локальные audio/video имеют доступный интерфейс Mnema поверх
   стандартных `<audio>`/`<video>`. У внешнего YouTube embed остаются свои controls и
   branding; Mnema даёт явный переход на источник и не скрывает запрос к третьей стороне.
6. **Обновление без push.** Статусы незавершённых media на видимой вкладке опрашиваются с
   bounded backoff и сразу после возврата фокуса; список колод — лёгким условным запросом
   раз в 30–60 секунд и при фокусе. WebSocket/SSE для этого не нужны.

## Модель и состояния

```text
media_blob    = immutable source/derived bytes + verified SHA-256, size, MIME, object key
media_asset   = stable assetId + owner, provenance, generation, state, source blob
media_variant = source asset + purpose/format/dimensions + derived immutable blob
content_ref   = item revision + stable nodeId + authorized assetId
```

- `assetId` выдаётся при `upload-intent` до получения байтов. `generation` ограждает asset
  от запоздалого завершения прежней попытки; новая попытка использует новый staging key,
  опубликованные bytes не перезаписываются. `finalize` идемпотентен по
  owner/intent/generation/command. Повтор `upload-intent` с тем же `(owner, intentId)`
  возвращает прежний `assetId` при том же происхождении, иначе conflict.
- S3 ETag не равен SHA-256 (особенно при multipart). Сервер потоково проверяет реальные
  байты, signature/container/codec, размер, длительность, опасные decoder dimensions и
  полный SHA-256, затем переводит source в неизменяемый blob. Dedup происходит только за
  логическими ACL-ссылками. Variant имеет стабильный `profile` в пределах generation
  (`thumbnail`, `playback` и т. п.).
- Состояния: `PENDING_UPLOAD → VERIFYING → PROCESSING → READY`, а также `FAILED_RETRYABLE`,
  `REJECTED`, `DELETED`. Операции Object Storage и encoder не выполняются внутри транзакции
  БД; durable jobs имеют lease, bounded retry, backoff и terminal error. Если хранилище
  недоступно, сервер сохраняет `assetId` и позицию узла, но не обещает отсутствующие байты.
- `content_media_ref` и `draft_media_ref` хранят `owner_id` вместе с составными FK на
  revision/draft и asset: чужой asset не привязать даже ошибочным вызовом сервиса.
  Черновики, опубликованные revisions, offline-manifest и jobs удерживают asset разными
  ссылками. Готовый, но не вставленный asset держится owner-scoped hold
  `learning.media.unattached-ready-hold` (по умолчанию `P7D`, 1–30 дней), затем получает
  tombstone, а bytes ждут физического GC; это не сокращает удержание опубликованных
  revisions и активных drafts.
- Физическое удаление: blob стирается лишь после grace period, двух успешных полных scans и
  отсутствия revision/draft/owner/upload/job/manifest holds; повтор идемпотентен и
  аудируется ([GC](../engineering/media-gc.md)). Staging и незавершённые multipart чистит
  отдельная TTL-очистка.
- `resolve`/`playback` проверяет owner и reachability до выдачи короткоживущей ссылки;
  знание hash, `assetId` или object key чтение не авторизует. Видео поддерживает HTTP Range.
- Offline manifest — отдельный immutable snapshot (свой ID, version и ETag) поверх pinned
  content revision без presigned URL; смена generation/status/variant создаёт новую
  версию, клиент проверяет size и SHA-256 и устанавливает атомарно
  ([manifest](../engineering/media-offline-manifest.md)). Native-клиентов нет.

## UX и доступность

- Файловый picker доступен с клавиатуры, drag-and-drop и multiple-file queue — дополнение;
  выбор, запись и камера ведут к одним статусам (selected, hashing, uploading, verifying,
  processing, ready, retryable failure, rejected, cancelled). Запись аудио — через
  permission-aware browser capture с preview, retake и явной отправкой; камера — отдельная
  кнопка mobile file input `capture` с обычным picker как fallback.
- Изображение — `figure` с подписью, `alt`, размерами и lazy variants; для сложных схем
  есть long description. Увеличение открывает modal с корректным фокусом и keyboard
  zoom/pan. GIF показывает неподвижный poster и играет по явному действию.
- Аудио/видео: play/pause, позиция, громкость, скорость по уместности, poster,
  captions/transcript, понятная ошибка истёкшего доступа; без autoplay; media control не
  перехватывает клавиши ответа Study; при reduced motion нет принудительной анимации.
- Transcript до ответа — осознанная accessibility accommodation: он не раскрывает эталон в
  обычном аудировании, а использование подсказки ограничивает evidence по versioned policy.
  Browse даёт полную альтернативу без оценки.
- Mermaid и таблицы имеют доступное текстовое представление; таблица остаётся
  семантической HTML-таблицей.

## Политика и конфигурация

Изменяемые значения (размер, длительность, число и параллелизм загрузок, retry/TTL/poll
intervals, квоты) названы по владельцу и области, имеют диапазон и default и перечислены в
одном индексе ([runtime policy index](../engineering/runtime-policy-index.md)). Числа,
которые являются versioned wire/storage invariants, живут в общих contract constants/tests
и меняются только с версией контракта. Алгоритмические числа и CSS tokens не становятся
runtime settings автоматически.

## Источники

[Yandex multipart](https://yandex.cloud/en/docs/storage/s3/api-ref/multipart),
[GetObject/Range](https://yandex.cloud/en/docs/storage/s3/api-ref/object/get) (upload и seek);
[OWASP File Upload](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)
(allowlist, не доверять MIME/имени, ограничивать ресурсы);
[MDN video codecs](https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Formats/Video_codecs),
[MediaRecorder](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder),
[capture](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Attributes/capture);
[W3C complex images](https://www.w3.org/WAI/tutorials/images/complex/),
[media accessibility](https://www.w3.org/WAI/media/av/planning/);
[YouTube minimum functionality](https://developers.google.com/youtube/terms/required-minimum-functionality);
[PostgreSQL constraints](https://www.postgresql.org/docs/18/ddl-constraints.html),
[row locking](https://www.postgresql.org/docs/18/explicit-locking.html).
