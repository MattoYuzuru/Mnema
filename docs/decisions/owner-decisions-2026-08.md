---
artifact:
  id: owner-decisions-2026-08
  type: decision-log
  title: "Mnema v2 owner decisions"
  status: accepted-input
  created_at: "2026-08-15"
  updated_at: "2026-09-06"
  owners: ["project-owner"]
---

# Решения владельца для Mnema v2

Это каноническая фиксация ответов владельца после первичного аудита. `Accepted` означает продуктовый input для проектирования, а не то, что функция уже реализована. Оставшиеся вопросы не должны возвращать обсуждение к уже отвергнутым шаблонам/полям.

Уточнение 2026-09-06: первый релиз — собственные колоды и web-only. Подробные
сценарии и выбранные инженерные defaults находятся в
[authoring and Study workflows](../product/authoring-and-study-workflows.md).
Новые ответы заменяют прежние рекомендации по revalidation, launch sharing и
постоянному запрету upstream contributions; история решений остаётся в Git.

## Accepted

### Контент и редактор

- Нативная модель больше не строится вокруг пользовательских templates, fields или обязательной пары языков.
- В одной колоде свободно сосуществуют разные виды материала. Пользователь сам выбирает объём и смысл LearningItem: слово, грамматика, конспект, длинный разбор; обязательной типологии и ограничения одной темой нет.
- Требуются текст, ruby/furigana, RTL и смешанные языки, math/chemistry, code, Mermaid/схемы, изображения, GIF, audio, video и в будущем drawings.
- Длинный материал не обрезается: карточка/материал свободно скроллится.
- Интерактивный ответ относится к exercise, а не встраивается исполняемым элементом в rich content.
- На desktop нужен split-screen: editor с toolbar/hotkeys слева и live preview справа; на mobile — две понятные поверхности editor/preview без потери draft/selection.
- Full editor и quick text capture входят в own-deck loop; batch/voice capture, transcription и AI enhancement развиваются отдельно. AI не становится зависимостью ручного добавления.
- Незавершённые правки нескольких материалов/колод/упражнений сохраняются на сервере и восстанавливаются после ухода со страницы/logout; только явное сохранение публикует редакцию. TTL и защитные лимиты EditingDraft можно выбрать инженерно без дополнительного approval.
- Быстрые заметки во время урока — отдельные CaptureNotes с createdAt: без TTL по бездействию, до явного удаления, вне Study и spaced practice. Рабочее UI-название — «На потом», а не обязательный Inbox.
- Immutable revisions нужны и для item, и для deck, но не должны копировать все неизменённые bytes/items при каждой правке. Нужны structural sharing/ограниченное восстановление и быстрый common read; конкретное хранение остаётся инженерным предложением.
- На первом этапе достаточно системной темы; произвольная стилизация карточек отложена.
- Текущий UI и Liquid Glass не сохраняются. Design input получен 2026-09-06: третье изображение Mnemosyne, бумажная фактура, чернильный индиго, античная типографика с кириллицей, гравюра, пропечатанные облака, звёзды и пунктиры. Направление распространяется на весь новый макет; прежний макет не является основой для косметической доработки. См. [design and experience](../frontend/design-and-experience-2026-09.md).
- Anki import вторичен относительно native launch. Legacy HTML/CSS renderer не строится: будущий importer компилирует поддерживаемый смысл в native AST/exercises, а непереносимое показывает в conversion report. APKG round-trip не обещается.

Архитектурное следствие принято как рекомендация: канонический формат — typed versioned document AST; Markdown — authoring/interchange view; front/reveal — exercise projection. Детали: [learning-content-format-v2](../architecture/learning-content-format-v2.md).

### Медиа

- Хранится редактируемый original/source и оптимизированные derived preview/variants.
- Один physical blob обязательно переиспользуется в тысячах материалов без копирования, но ACL не определяется публичным hash.
- Удаление item/deck сначала создаёт tombstone; асинхронный GC удаляет только недостижимый media после grace period.
- Mermaid/drawing source сохраняется; render можно кэшировать и перестроить.
- Production object storage — Yandex Object Storage; local/CI — MinIO-compatible path.

### Обучение

- Browse — отдельный полезный режим.
- Study всегда запускается внутри выбранной колоды; межколодное смешивание запрещено.
- Exercise — отдельная versioned entity/config, а не card type.
- Материал остаётся документом; у flashcard exercise отдельно настраиваются вопрос и раскрываемый ответ. Интервальное повторение — политика расписания для разных упражнений, а не двухсторонний формат хранения материала.
- Авторы/пользователи могут добавлять совместимые упражнения к материалу; AI позже предлагает mapping.
- Exercise bindings выбирают части документа либо собственную короткую подпись/медиа упражнения. Matching может соединять слово с переводом или аудио; большой документ не вставляется целиком в компактную ячейку. Автор задаёт eligible материалы/группы и может создавать необычные валидные сочетания.
- Проверка сначала детерминированная: normalization, aliases, typo tolerance, sets/order/tolerance. AI нужен для эссе, объяснения, интервью и иных открытых ответов.
- AI assessment возвращает стабильный JSON verdict/evidence; пользователь может оспорить оценку.
- История прогресса не удаляется при смысловой правке материала.
- Изменение правильного ответа также сохраняет текущее расписание/состояние; автоматическая revalidation и reset отклонены. Пользователь может явно выбрать материал и «Учить заново» с сохранением истории.
- Прогресс показывается на уровне материала; агрегация нескольких проверяемых целей — инженерное решение с объяснимым смыслом, не произвольный процент полной выученности. Материалы и progress логически принадлежат одной колоде; shared physical bytes будущей копии не связывают расписания.
- `LearningItem` — канонический термин для единицы материала. Одно упражнение может ссылаться на один или несколько `LearningItem`/revision, а один item может участвовать в нескольких упражнениях.
- Подтверждённая оцениваемая попытка только в обычном SCHEDULED Study создаёт evidence для spaced practice. Сила evidence зависит от механики, подсказок, качества проверки и цели; точные веса и алгоритмы versioned, проверяются экспериментами и не зашиты в UI.
- После занятия доступны повтор сегодняшней сессии и дополнительная практика по всей колоде, включая многократные проходы перед экзаменом. REPLAY/PRACTICE не меняют canonical progress, due, освоение, streak или обычную учебную статистику. Не создаются отдельные искусственные колоды. Пустая очередь не запрещает учиться; реально появившиеся due/new допускают обычную сессию и в тот же день.
- Большая колода обрабатывается ограниченными порциями; seed/cursor воспроизводят выбранный порядок без загрузки/копирования всей колоды на каждый старт.

Каталог и P0/P1/P2: [exercise-catalog-v2](../product/exercise-catalog-v2.md).

### Будущие публичные колоды и collaboration

- Sharing/catalog/coauthors не входят в первый own-deck релиз. Будущий onboarding предлагает создать свою или взять качественную колоду сообщества; глобальный каталог, рекомендации, likes/quality ranking и возможная главная-каталог развиваются после проверки качества обучения.
- Чужая колода копируется быстро с физическим переиспользованием неизменённых данных; пользователь свободно меняет её metadata, материалы и упражнения. Прогресс независим. Произвольное cross-deck связывание материалов в UI не вводится.
- Update preview показывает изменения metadata, материалов и упражнений. Чистые изменения применяются безопасно, конфликтующие ждут выбора. Manual pull рекомендован для первой версии sharing; автоматическое обновление остаётся будущей опцией, default пока не выбран.
- Конфликт показывает пользовательский и новый варианты рядом, highlight diff и явный выбор.
- Если автообновление появится, пользователь может его отключить.
- Полная долгосрочная модель включает выборочное объединение своего и исходного содержания, «Предложить улучшение» автору и review/merge. Прежний постоянный запрет upstream flow отменён; эта возможность не входит в первый релиз и не требует literal Git UI/backend.
- Удаление/withdrawal публичной колоды не ломает подписчиков: им остаётся последняя доступная ревизия.
- Совместные авторы и review/approval публикации входят в целевую модель.
- Visibility: `PUBLIC`, `LINK/REQUEST_RESTRICTED` для классов/университетов и `PRIVATE`; coauthors допускаются в каждом подходящем режиме.
- У автора есть профиль с настраиваемой видимостью, колодами, текущим числом подписчиков/использующих и датой обновления.
- Creator payouts и подписки на update-notifications не входят в scope.

### Product и hosted business

- Первый рынок — Россия, инфраструктура также в России.
- Начальные каналы: студенты ВШЭ и Центрального университета, языковые преподаватели/курсы и Telegram-сообщества; программисты — дополнительный сегмент.
- Ценность формулируется вокруг эффективного изучения собственного материала и видимого прогресса, а не вокруг Git, конструктора или document conversion.
- Hosted в будущем использует managed AI и не показывает BYOK/provider selection, но manual content/Study MVP выпускается раньше AI; AI не является зависимостью greenfield foundation, editor или детерминированных упражнений.
- Текущий self-host режим можно не поддерживать во время v2 rewrite; возможен отдельный репозиторий позже.
- Первый manual own-deck/content/Study релиз бесплатен и не зависит от подписки. Variable-cost AI остаётся будущей монетизацией; не обещаем навсегда unlimited количество колод или AI-only монетизацию.
- Лимиты числа своих/скопированных колод и платный offline — новые гипотезы, не тариф. «5 своих + 5 скопированных» — пример. При окончании подписки существующие колоды не удаляются и остаются доступными; возможный лимит блокирует только добавление новых до освобождения места/продления. Точные числа, цены и состав подписки не утверждены.
- Предпочтительны freemium, одноразовый 14-day trial без карты и простые платные tiers; media quotas не должны скрываться под unlimited.
- Исторический future AI Starter имеет ориентир `299 ₽` за 30 дней; это не утверждает цену новой подписки на число колод/offline. Верхние AI tiers требуют существенно большей ценности, например tutor; реактивация #77 возвращает pricing validation.
- Yandex AI исключён из provider shortlist владельцем. Direct DeepSeek — основной eval-кандидат, но production route требует quality/cost/latency, доступности из РФ и cross-border/privacy gates; нужен не-Yandex fallback.
- Публичные колоды бесплатны; авторам не выплачивается revenue share.

### Greenfield rewrite, data reset, clients and operations

- Для планирования принимается owner assertion: продуктом никто не пользуется и RPS равен нулю; повторная проверка usage не является gate.
- Переписывается канонический продуктовый путь. `/v2`, параллельные API, dual read/write, migration adapters и compatibility runtime запрещены; заменённый v1 code удаляется, а временно неработающий продукт допустим.
- `auth` и `user` объединяются в один Identity & Account boundary/deployable. Сохраняются долгоживущие account identity/profile данные, включая локальные credentials, federated identities и относящийся к профилю avatar asset; sessions, OAuth authorizations/consents, grants and tokens не сохраняются.
- Decks, cards/templates, learning content, study/review state, imports, AI jobs/ledgers/credentials и learning media в DB/S3 удаляются безвозвратно. Legacy database/object snapshots, PITR/WAL copies, object versions, multipart uploads, caches и иные backup-shaped копии этих данных также удаляются; полный legacy snapshot не создаётся и не удерживается.
- Account-only export/restore допустим и обязан содержать только allowlisted сохраняемые account fields. После начала удаления legacy данных rollback к v1 отсутствует; восстановление возможно только roll-forward из новой системы и account-only recovery artifacts.
- Maintenance downtime, временная недоступность production и сломанные продуктовые flows во время rewrite разрешены.
- Сейчас web-only. Будущие отдельные native iOS/Android clients и offline review скачанных колод обязательны к учёту в IDs, idempotency, session modes и sync; изображения/audio/video входят в пакеты с version/hash validation. Прогресс синхронизируется между клиентами; стоимость offline открыта, PWA не является текущим приоритетом.
- Spring/Java and PostgreSQL remain. Kotlin migration is not a goal by itself.
- Начальная топология проще нынешней: единый Identity & Account deployable и modular Learning API; workers выделяются только для уже нужного failure/resource boundary. Существующая микросервисная нарезка не сохраняется ради совместимости.
- Нужны автоматизированные edge/E2E/load tests с реальным PostgreSQL и MinIO-compatible storage.
- Владелец предпочитает recovery/retention до шести месяцев, но этот срок не становится контрактом до появления законного основания по каждой категории данных; product data должны удаляться раньше, если того требует утверждённая policy.
- Возможность использовать публичные колоды для AI improvement остаётся открытой и требует consent/licensing/privacy решения до реализации.
- Existing private GitHub Project #4 остаётся Kanban/source of execution. Создание parent epics разрешено; новую taxonomy labels/fields/views пока не вводить, использовать существующие `Status` и `Priority`.
- Текущий шаг 2026-09-06 — формализация #74–#76 и отдельный clickable design prototype. #73 foundation/Identity уже реализованы в source; #146/#147 ждут replacement gates. Этот шаг не разрешает runtime implementation, публикацию GitHub или production reset.
- После просмотра документов и preview владелец одобрил направление и разрешил отдельный docs/prototype delivery через push, PR и защищённый squash-merge с полными quality gates. Это последующее разрешение заменяет запрет GitHub publication для данного конечного шага; production/data reset, новые dependencies и окончательный выбор непроверенной storage/editor реализации не включены.

## Rejected or deferred

- Два обязательных Markdown-документа как единственная форма item.
- MongoDB только ради гибкого content JSON.
- Literal Git/JGit как primary database или пользовательский Git UI.
- User JavaScript/MDX/arbitrary CSS в native renderer.
- Legacy Anki HTML/CSS renderer или sandbox capsule в hosted v2.
- APKG export без потерь.
- Глобальная Today queue, смешивающая колоды.
- Поддержка текущего self-host и BYOK как blocker hosted v2.
- Миграция старых decks/media/review/AI data.
- `/v2` namespace, side-by-side product generations, legacy compatibility/adapters и сохранение старых scheduler algorithms ради continuity.
- Любой retained full legacy snapshot после destructive cutover.
- Liquid Glass и сохранение текущей визуальной идентичности как constraint нового frontend.
- Произвольные theme/layout builders в первом релизе.
- Unlimited AI и монетизация размера содержимого одной колоды. Гипотеза лимита количества колод 2026-09-06 — отдельный будущий вопрос.
- Yandex AI как hosted provider.

## Open decisions with an owner or validation method

| ID | Вопрос | Рекомендация/следующий шаг |
|---|---|---|
| O-01 | Первый launch cohort: языки или exam/general students? | Запустить два concierge cohort внутри уже доступных каналов; roadmap P0 общий, messaging измерять отдельно. |
| O-02 | Что делать, если изменился проверяемый ответ? | **Решено 2026-09-06:** сохранить историю и текущее состояние/расписание, без автоматической revalidation. Явное «Учить заново» начинает новый learning epoch. Действительно новая независимая цель получает собственное начальное состояние. |
| O-03 | Какой editor engine? | Spike на IME/ruby/RTL, mobile selection, accessibility, large docs and Angular integration; dependency только после разрешения. |
| O-04 | Какой exact Anki conversion coverage достижим без legacy renderer? | После native launch собрать corpus; компилировать common HTML/CSS patterns в AST, выпускать per-item warnings и не публиковать unsafe/unsupported behaviour. |
| O-05 | Точный будущий AI Starter quota и нужен ли Plus? | Исторический ориентир 299 ₽/30 дней относится к AI, не deck/offline offer. После реактивации #77 проверить цену и quota по measured p95 cost; Plus не вводить до доказанной необходимости. |
| O-06 | AI provider routing? | Golden eval — фиксированный набор тестовых prompts/expected outputs. Сравнить direct DeepSeek и минимум один не-Yandex fallback по languages/STEM/code, cost/accepted item, p95 latency, privacy и доступности из РФ; см. [launch economics](../product/russia-launch-economics-2026.md). |
| O-07 | Лицензия нового кода? | **Решено 2026-08-30:** public source-available с private personal use для одного физического лица; любое organizational/shared/hosted/commercial/ML use требует отдельной письменной лицензии. Последний Apache-срез — `v1-apache-final`; см. [license transition](./source-license-transition.md). |
| O-08 | Можно ли использовать public content for AI improvement? | Require explicit author grant, provenance, deletion/export rules and provider terms before enabling. |
| O-09 | Точный account-retention legal policy? | Six months is not assumed lawful. Separate product deletion from legally retained tax/payment records and approve every retention period with Russian counsel; см. [legal launch checklist](../product/russia-legal-launch-checklist-2026.md). |
| O-10 | Формула понятного прогресса материала? | Агрегировать явно проверяемые цели, показывать coverage/next due; определить formula/version и проверить понимание. Не выдавать arbitrary double за научно доказанное освоение. |
| O-11 | Manual или automatic source updates? | Первый future sharing UX рекомендован manual/selective; auto-update default уточнить в community этапе. Upstream contribution теперь часть долгосрочной цели. |
| O-12 | Количество бесплатных колод и платность offline? | Гипотезы; 5+5 не фиксировать как тариф. Сохранность существующих колод при окончании подписки обязательна. |
| O-13 | Какая механика beyond P0 первой? | Matching с explicit short text/audio projections обязателен в target и prototype; production ordering относительно P0 уточнить без реализации всех mechanics сразу. |

## Immediate implementation gates

1. Согласовать #74–#76 по storage, deck-local identity, projection/answer bindings, drafts, session modes и media references; статус engineering proposals не скрывать.
2. Проверить новый бумажный prototype на основных пользовательских сценариях; production frontend строится отдельно на Angular.
3. Разбить реализацию на reviewable content/UI, media и Study slices; сохранить community/AI/native offline вне первого own-deck релиза.
4. При отдельной работе с GitHub синхронизировать scope эпиков; настоящая публикация/изменение задач этим документом не выполняются.
5. После replacement gates завершить #146/#147; destructive cutover остаётся отдельной последней operational задачей.
