---
artifact:
  id: epic-76-refinement
  type: implementation-plan
  title: "Epic #76 media, rich content and listening refinement"
  status: accepted
  created_at: "2026-09-28"
  updated_at: "2026-09-28"
  owners: ["project-owner"]
  source_tasks: ["GitHub Epic #76"]
---

# Epic #76: медиа в материалах и упражнениях

## Результат и граница

Автор загружает или записывает медиа, вставляет его в versioned `LearningItem`,
публикует материал и использует его в Browse и Study. Схему можно вставить как
картинку или редактируемый Mermaid source. Медиа красиво и доступно отображается
в принятом бумажно-индиговом интерфейсе. Ошибка загрузки не теряет место вставки.
Байты хранятся в приватном S3-compatible Object Storage, а доступ определяется
правами на логический asset и материал. Первый web-выпуск включает три варианта
аудирования.

Это расширение [greenfield content model](../architecture/learning-content-format-v2.md),
[Study contract](../../contracts/study/README.md) и
[visual direction](../frontend/design-and-experience-2026-09.md). Legacy `media`
service, card/template APIs и старые S3 objects не становятся зависимостями нового
Learning runtime. Production deployment и native offline apps здесь отсутствуют;
доставка идёт по [local development mode](../operations/local-development-delivery.md).

## Принятые продуктовые решения

1. Первый upload-набор: JPEG, PNG, WebP, GIF; MP3, M4A; MP4. Для короткого
   телефонного видео и браузерной записи дополнительно принимаются MOV/HEVC и
   WebM как **вход**. Обязательные fixture-профили: MOV/MP4 с HEVC Main и Main10
   (включая orientation/HDR metadata), WebM с VP8/Opus и VP9/Opus, MP4 с
   H.264/AAC. Для каждого создаётся MP4 H.264/AAC variant; оригинал сохраняется.
   Безопасный декодер, его упаковка и лицензия — gate задачи #236. Произвольный
   SVG не входит в upload-набор: активное содержимое требует отдельного
   безопасного контракта. Известные клиенту тип и размер проверяются до upload;
   фактический codec может быть отклонён только после серверной проверки, даже
   если материал с pending asset уже опубликован.
2. Длительность загруженного видео — не более 300 секунд. Исходный лимит
   4 GiB — явная начальная policy, а не утверждение о типичном размере телефона:
   100 Mb/s × 300 s ≈ 3.49 GiB. Квоты аккаунта, число одновременных загрузок,
   resource/time limits обработчика и стоимость трафика защищают от злоупотребления.
   Политика централизована и документирована; изменение значения не затрагивает
   длительность аудио, Study session или storage GC.
3. Поддерживаются готовая PNG/иная разрешённая картинка схемы, редактируемый
   Mermaid source с безопасным preview и нативная таблица. Их узлы получают
   собственные versioned schemas/renderers и текстовые альтернативы. Сейчас
   [native-v1](../../contracts/content/native-v1/README.md) сохраняет их только
   как opaque placeholders; готовыми их считать нельзя.
4. `LISTEN_CHOICE`: одно аудио и один верный ответ из 2–6 вариантов.
   `AUDIO_TEXT_MATCH`: 2–6 закреплённых пар «аудио ↔ короткий текст» с выбором
   кнопками, без обязательного drag. Ответ отправляется атомарной картой пар.
   `LISTEN_TYPE`: аудио как условие ручной расшифровки/короткого ответа, без STT;
   здесь переиспользуется проверенная текстовая нормализация, но аудио cue и
   presentation имеют свою версию. Все три варианта имеют pinned references,
   idempotent attempt и явный evidence policy. Больше упражнений добавляется
   после проверки учебной пользы, а не ради числа типов.
5. Автор может вставить зарезервированный `assetId` и опубликовать материал во
   время загрузки/обработки. Читатель видит состояние, а открытая вкладка сама
   обновляет его. Упражнение, где это медиа является необходимым cue/answer,
   недоступно для Study до `READY`; отсутствие файла никогда не оценивается как
   ошибка ученика. Если объект не принят, автор видит retry/replace action.
6. Локальные audio/video controls имеют собственный доступный интерфейс в стиле
   Mnema поверх стандартных `<audio>`/`<video>` и browser media APIs. У внешнего
   YouTube embed остаются собственные controls/branding; Mnema даёт явный
   переход на источник и не скрывает факт запроса к третьей стороне.
7. На видимой вкладке статусы незавершённых media обновляются bounded polling
   с backoff и immediate refresh после возврата фокуса. Список колод другого
   устройства проверяется лёгким условным запросом каждые 30–60 секунд и при
   фокусе. Открытых WebSocket/SSE-соединений для этого требования не нужно.

## Архитектурный контракт

```text
media_blob    = immutable source/derived bytes + verified SHA-256, size, MIME, object key
media_asset   = stable assetId + owner, provenance, generation, state, source blob
media_variant = source asset + purpose/format/dimensions + derived immutable blob
content_ref   = item revision + stable nodeId + authorized assetId
```

`assetId` выдаётся при `upload-intent` до получения байтов. Номер `generation`
ограждает ассет от запоздалого завершения прежней попытки. Новая попытка
использует новый staging key; опубликованные bytes не перезаписываются. `finalize`
идемпотентен по owner/intent/generation/command identity. S3 ETag не равен
SHA-256, особенно при multipart. Сервер потоково проверяет реальные байты,
signature/container/codec, размер, длительность, опасные decoder dimensions и
полный SHA-256, затем переводит validated source в неизменяемый blob. Dedup
происходит только за логическими ACL-ссылками, с защитой от конкурентной записи.

Состояния: `PENDING_UPLOAD → VERIFYING → PROCESSING → READY`, а также
`FAILED_RETRYABLE`, `REJECTED`, `DELETED`. Операции Object Storage и media encoder
не выполняются внутри транзакции БД. Durable jobs имеют lease, bounded retry,
backoff и terminal error. Черновики, опубликованные revisions и jobs удерживают
asset через разные ссылки. Готовый, но ещё не вставленный asset имеет отдельное
owner-scoped удержание на семь суток после успешной проверки; до истечения срока
он доступен для вставки. По истечении срока неиспользованный asset переводится
в tombstone, UI предлагает повторную загрузку, а bytes ждут обычного GC. Эта
политика не сокращает удержание опубликованных revisions или активных drafts.
Если S3 недоступен или заполнен, сервер сохраняет
`assetId` и позицию узла, но **не обещает отсутствующие байты**. Клиентский hash
может помочь узнать повторно выбранный файл; источник истины — серверная проверка.

`resolve` проверяет owner/content reachability до выдачи короткоживущей ссылки
на source/variant. Знание hash, assetId или object key не авторизует чтение.
Видео поддерживает HTTP Range. Offline media manifest — отдельный immutable
snapshot с собственным ID/version/ETag поверх pinned content revision. Каждая
смена asset generation/status или выбранного variant создаёт новую версию
manifest; старый snapshot не меняется. Он содержит revision IDs, asset/variant
IDs, hash, size и явное состояние `unavailable`, без presigned URL. Клиент
скачивает и проверяет конкретную версию, устанавливает её атомарно; переход
`PENDING → READY` требует нового snapshot/ETag. Контракт проверяется синтетическим
скачиванием и установкой, без создания native клиента.

Удаление сначала снимает content reference/tombstone. Staging и незавершённые
multipart имеют отдельную TTL-очистку. Source/variant blob удаляется лишь после
grace period, двух успешных полных reachability scans и отсутствия revision,
draft, owner-scoped asset, upload, job и retention holds. Повторная очистка
идемпотентна и аудируется.

## UX и доступность

- Desktop сохраняет split editor/live preview; mobile — последовательные вкладки
  «Материал / Вид / Упражнения». Выбор медиа, drag-and-drop и multiple-file queue
  ведут к одним статусам: selected, hashing, uploading, verifying, processing,
  ready, retryable failure, rejected, cancelled. Основная кнопка выбора файла
  доступна с клавиатуры; drop — дополнительный способ.
- Запись аудио использует permission-aware browser capture с preview, retake и
  явной отправкой. Камера запускается отдельной кнопкой через mobile file input
  `capture` с обычным picker fallback; выбор браузера/устройства остаётся за ним.
- Изображение — `figure` с подписью, `alt`, размерами и lazy variants; подробная
  архитектурная схема имеет также long description. Увеличение открывает modal
  с корректным фокусом, zoom/pan keyboard controls и download, если авторизация
  позволяет. GIF имеет неподвижный poster и явное воспроизведение/паузу.
- Аудио/видео: play/pause, позиция, громкость или системное управление, скорость
  по уместности, poster, captions/transcript и ошибки истёкшего доступа. Нет
  autoplay. Media control не перехватывает клавиши ответа Study. При reduced
  motion нет принудительной анимации.
- Mermaid сохраняет editable source и показывает strict/sandboxed preview без
  исполняемых ссылок/callbacks; таблица остаётся семантической HTML-таблицей.
  Обе поверхности имеют доступное текстовое представление. Ошибка диаграммы
  не удаляет исходник и не превращает его молча в картинку.
- Transcript до ответа — осознанная accessibility accommodation. Он не должен
  раскрывать эталон в обычном аудировании; если учащийся использует подсказку,
  evidence помечается/ограничивается по versioned policy. Browse даёт полную
  альтернативу без оценки.

## Policy и конфигурация

Провести отдельный inventory по `backend/services/learning` и canonical Angular
flows: выбрать значения, которые владелец или оператор реально меняет (размер,
длительность, число/параллелизм upload, retry/TTL/poll intervals, quotas), назвать
их по **владельцу и области**, задать допустимый диапазон и defaults, задокументировать
в одном index. Числа, являющиеся versioned wire/storage invariants, вынести в
общие contract constants/tests и менять только с версией контракта. Алгоритмические
числа и CSS tokens не превращать автоматически в runtime settings. Текущий
[StorageSettings](../../backend/services/learning/src/main/java/app/mnema/learning/storage/StorageSettings.java)
показывает существующий локальный паттерн, но не заменяет media policy.

## Delivery slices (не больше десяти)

Каждая строка становится отдельным work item/PR с собственным acceptance и
проверкой, согласно [стандарту задач](work-item-standard.md). Порядок допускает
частичную готовность продукта в greenfield rewrite; Epic остаётся Backlog, пока
refinement не принят, и затем In progress до integrated acceptance.

| № | Issue | Outcome | Основная граница |
|---:|---|---|---|
| 1 | [#233](https://github.com/MattoYuzuru/Mnema/issues/233) | Зафиксировать контракты, UX states, format/size policies и acceptance fixtures | Docs/contracts, без production behavior |
| 2 | [#234](https://github.com/MattoYuzuru/Mnema/issues/234) | Создать schema/ACL для `blob/asset/variant/ref` и owner hold, привязку к pinned revisions | PostgreSQL и owner authorization |
| 3 | [#235](https://github.com/MattoYuzuru/Mnema/issues/235) | Реализовать single/multipart presigned upload, finalize/retry, staging cleanup и MinIO tests | Transfer API, staging, idempotency |
| 4 | [#236](https://github.com/MattoYuzuru/Mnema/issues/236) | Проверять оригиналы и создавать совместимые bounded image/audio/video variants | Jobs, decoder/codec limits и fixture matrix |
| 5 | [#237](https://github.com/MattoYuzuru/Mnema/issues/237) | Добавить versioned native image/audio/video/Mermaid/table nodes и renderer contracts | Content reader/editor adapter, opaque preservation |
| 6 | [#238](https://github.com/MattoYuzuru/Mnema/issues/238) | Дать автору batch/drop/file/record/camera workflow с автообновлением asset status | Editor UX и recovery |
| 7 | [#239](https://github.com/MattoYuzuru/Mnema/issues/239) | Показать готовые media в Browse/Study с доступными плеерами, zoom, YouTube и обновлением списка колод | Viewer UX, permission/error states |
| 8 | [#240](https://github.com/MattoYuzuru/Mnema/issues/240) | Ввести `LISTEN_CHOICE`, `AUDIO_TEXT_MATCH`, `LISTEN_TYPE` с authoring, Study и evidence semantics | Exercise/session/attempt contract |
| 9 | [#241](https://github.com/MattoYuzuru/Mnema/issues/241) | Инвентаризировать и централизовать изменяемые policies, описать scope каждого параметра | Config/doc audit, без смены learning policy |
| 10 | [#242](https://github.com/MattoYuzuru/Mnema/issues/242) | Реализовать delayed two-scan GC, offline manifest/install contract и integrated acceptance | PostgreSQL/MinIO/Chrome, a11y, legacy targets |

## Общие acceptance gates

- Пользователь проходит весь путь upload/record → draft → publish → Browse →
  media Study → retry/replay без ручного refresh и без потери текста/asset ref.
- Недоступный файл не оценивается как неверный ответ. Истёкшая ссылка обновляется
  после повторной авторизации; чужой asset/hash/key не раскрывает bytes.
- Concurrent finalize, duplicate command, multipart interruption, upload failure,
  failed transform, publish-during-processing и GC с удерживаемым blob проверены
  реальным PostgreSQL + MinIO; Range работает на видео.
- JPEG/PNG/WebP/GIF и MP3/M4A рендерятся; каждый обязательный video fixture
  MOV/MP4 HEVC Main/Main10, WebM VP8/Opus и VP9/Opus, MP4 H.264/AAC создаёт
  совместимый MP4 H.264/AAC variant. Неподдерживаемый профиль вне матрицы и
  повреждённые bytes получают объяснимый отказ. SVG/враждебные payloads не
  выполняются. Большой 5-минутный телефонный fixture проходит при пределах
  policy без загрузки всего файла в память API.
- Mermaid и таблицы проходят source/save/render/plain-text/unsupported-version
  round trip; схемы имеют содержательные текстовые альтернативы.
- Keyboard, screen reader, touch, 320/390/768/1440 CSS px, 200% zoom, reduced
  motion, forced colors и YouTube fallback имеют наблюдаемое evidence. Реальные
  устройства отмечаются как проверенные только после запуска на них.
- Backend `quality`, frontend `lint`, `test`, `build` и hosted quality checks
  проходят на точном PR head до merge. Protected squash создаёт новый SHA;
  integrated main checks повторяются после merge. Нет требования деплоя.

## Источники для реализации

- [Yandex multipart](https://yandex.cloud/en/docs/storage/s3/api-ref/multipart),
  [GetObject/Range](https://yandex.cloud/en/docs/storage/s3/api-ref/object/get),
  [storage pricing](https://yandex.cloud/en/docs/storage/pricing): upload, seek и cost boundary.
- [OWASP File Upload](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html):
  allowlist, не доверять MIME/имени, ограничивать ресурсы и изолировать файлы.
- [MDN video codecs](https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Formats/Video_codecs),
  [MediaRecorder](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder),
  [capture](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Attributes/capture):
  input/output formats и browser capture fallback.
- [W3C complex images](https://www.w3.org/WAI/tutorials/images/complex/),
  [media accessibility](https://www.w3.org/WAI/media/av/planning/):
  descriptions, captions and transcripts.
- [YouTube minimum functionality](https://developers.google.com/youtube/terms/required-minimum-functionality):
  embed size/controls/branding boundary.
