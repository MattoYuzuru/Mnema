---
artifact:
  id: owner-decisions-2026-08
  type: decision-log
  title: "Mnema v2 owner decisions"
  status: accepted-input
  created_at: "2026-08-15"
  updated_at: "2026-08-15"
  owners: ["project-owner"]
---

# Решения владельца для Mnema v2

Это каноническая фиксация ответов владельца после первичного аудита. `Accepted` означает продуктовый input для проектирования, а не то, что функция уже реализована. Оставшиеся вопросы не должны возвращать обсуждение к уже отвергнутым шаблонам/полям.

## Accepted

### Контент и редактор

- Нативная модель больше не строится вокруг пользовательских templates, fields или обязательной пары языков.
- В одной колоде свободно сосуществуют разные виды материала.
- Требуются текст, ruby/furigana, RTL и смешанные языки, math/chemistry, code, Mermaid/схемы, изображения, GIF, audio, video и в будущем drawings.
- Длинный материал не обрезается: карточка/материал свободно скроллится.
- Интерактивный ответ относится к exercise, а не встраивается исполняемым элементом в rich content.
- На desktop нужен split-screen: editor с toolbar/hotkeys слева и live preview справа; на mobile — две понятные поверхности editor/preview без потери draft/selection.
- В будущем нужны full editor, quick/batch draft, voice capture/transcription и AI generation/enhancement.
- На первом этапе достаточно системной темы; произвольная стилизация карточек отложена.
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
- Авторы/пользователи могут добавлять совместимые упражнения к материалу; AI позже предлагает mapping.
- Проверка сначала детерминированная: normalization, aliases, typo tolerance, sets/order/tolerance. AI нужен для эссе, объяснения, интервью и иных открытых ответов.
- AI assessment возвращает стабильный JSON verdict/evidence; пользователь может оспорить оценку.
- История прогресса не удаляется при смысловой правке материала.

Каталог и P0/P1/P2: [exercise-catalog-v2](../product/exercise-catalog-v2.md).

### Публичные колоды и collaboration

- Conflict-free обновления могут применяться автоматически с краткой сводкой изменений.
- Конфликт показывает пользовательский и новый варианты рядом, highlight diff и явный выбор.
- Пользователь может отключить автообновление.
- Upstream pull request/merge от подписчика не нужен. Независимое редактирование — clone/fork без обратной отправки.
- Удаление/withdrawal публичной колоды не ломает подписчиков: им остаётся последняя доступная ревизия.
- Совместные авторы и review/approval публикации входят в целевую модель.
- Visibility: `PUBLIC`, `LINK/REQUEST_RESTRICTED` для классов/университетов и `PRIVATE`; coauthors допускаются в каждом подходящем режиме.
- У автора есть профиль с настраиваемой видимостью, колодами, текущим числом подписчиков/использующих и датой обновления.
- Creator payouts и подписки на update-notifications не входят в scope.

### Product и hosted business

- Первый рынок — Россия, инфраструктура также в России.
- Начальные каналы: студенты ВШЭ и Центрального университета, языковые преподаватели/курсы и Telegram-сообщества; программисты — дополнительный сегмент.
- Ценность формулируется вокруг эффективного изучения собственного материала и видимого прогресса, а не вокруг Git, конструктора или document conversion.
- Hosted использует managed AI по умолчанию и не показывает BYOK/provider selection.
- Текущий self-host режим можно не поддерживать во время v2 rewrite; возможен отдельный репозиторий позже.
- Manual content/study остаются бесплатными; монетизируется variable-cost AI.
- Предпочтительны freemium, одноразовый 14-day trial без карты и простые платные tiers; media quotas не должны скрываться под unlimited.
- Первый платный Starter фиксируется на `299 ₽` за 30 дней; верхние tiers до 1 000 ₽ требуют существенно большей ценности, например tutor.
- Yandex AI исключён из provider shortlist владельцем. Direct DeepSeek — основной eval-кандидат, но production route требует quality/cost/latency, доступности из РФ и cross-border/privacy gates; нужен не-Yandex fallback.
- Публичные колоды бесплатны; авторам не выплачивается revenue share.

### Data reset, clients and operations

- В production примерно десять аккаунтов и фактически нулевой RPS.
- Сохраняются account/identity данные. Decks, content/media, review history and AI jobs разрешено удалить безвозвратно.
- Maintenance downtime разрешён.
- Future iOS/Android clients and offline review downloaded decks обязательны к учёту в IDs, idempotency and sync; PWA не является текущим приоритетом.
- Spring/Java and PostgreSQL remain. Kotlin migration is not a goal by itself.
- Начальная топология может быть проще нынешней: modular API плюс отдельно масштабируемые AI/import/media workers. Физические сервисы выделяются по measured load/failure/security boundary.
- Нужны автоматизированные edge/E2E/load tests с реальным PostgreSQL и MinIO-compatible storage.
- Владелец предпочитает recovery/retention до шести месяцев, но этот срок не становится контрактом до появления законного основания по каждой категории данных; product data должны удаляться раньше, если того требует утверждённая policy.
- Возможность использовать публичные колоды для AI improvement остаётся открытой и требует consent/licensing/privacy решения до реализации.
- Existing private GitHub Project #4 остаётся Kanban/source of execution. Создание parent epics разрешено; новую taxonomy labels/fields/views пока не вводить, использовать существующие `Status` и `Priority`.
- Период 17–31 августа посвящён staging/delivery foundation и v2 vertical slice; billing/quota начинается после этого gate.

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
- Произвольные theme/layout builders в первом релизе.
- Unlimited AI и deck-size monetization limit.
- Yandex AI как hosted provider.

## Open decisions with an owner or validation method

| ID | Вопрос | Рекомендация/следующий шаг |
|---|---|---|
| O-01 | Первый launch cohort: языки или exam/general students? | Запустить два concierge cohort внутри уже доступных каналов; roadmap P0 общий, messaging измерять отдельно. |
| O-02 | Что делать, если изменился проверяемый ответ или сама цель обучения? | Не использовать jargon в UI. Историю оставить на старой revision, новую цель пометить `REVALIDATION_REQUIRED` и дать одну скорую проверку; подтвердить UX-тестом, не нужен ли другой schedule. |
| O-03 | Какой editor engine? | Spike на IME/ruby/RTL, mobile selection, accessibility, large docs and Angular integration; dependency только после разрешения. |
| O-04 | Какой exact Anki conversion coverage достижим без legacy renderer? | После native launch собрать corpus; компилировать common HTML/CSS patterns в AST, выпускать per-item warnings и не публиковать unsafe/unsupported behaviour. |
| O-05 | Точный Starter quota и нужен ли Plus? | Цена Starter принята: 299 ₽/30 дней. Quota выбрать после measured p95 cost; Plus не вводить до доказанной необходимости. |
| O-06 | AI provider routing? | Golden eval — фиксированный набор тестовых prompts/expected outputs. Сравнить direct DeepSeek и минимум один не-Yandex fallback по languages/STEM/code, cost/accepted item, p95 latency, privacy и доступности из РФ; см. [launch economics](../product/russia-launch-economics-2026.md). |
| O-07 | Лицензия нового кода? | Рекомендуется public Apache v1 archive + private proprietary v2. Existing grants cannot be revoked; public view-only остаётся более слабой альтернативой. Требуется выбор владельца и IP-lawyer review; см. [license transition](./source-license-transition.md). |
| O-08 | Можно ли использовать public content for AI improvement? | Require explicit author grant, provenance, deletion/export rules and provider terms before enabling. |
| O-09 | Точный account-retention legal policy? | Six months is not assumed lawful. Separate product deletion from legally retained tax/payment records and approve every retention period with Russian counsel; см. [legal launch checklist](../product/russia-legal-launch-checklist-2026.md). |
| O-10 | Какие production deletion artifacts можно retain? | Decide whether only counts/hashes and account mapping may remain; no raw content backup after irreversible reset deadline. |

## Immediate implementation gates

1. Approve the content/exercise ADRs and fresh account-only migration boundary.
2. Query/export production accounts and rehearse restore without touching production.
3. Prototype P0 AST/editor/renderer and one end-to-end exercise contract.
4. Build the v2 relational schema only after the prototype validates node/reference semantics.
5. Fix current release cache/deployment safety before any hosted cutover.
