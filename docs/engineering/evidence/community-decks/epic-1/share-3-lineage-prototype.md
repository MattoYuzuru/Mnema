---
artifact:
  id: community-decks-share-3-lineage-prototype
  type: verification-evidence
  title: "Share/3: несколько колод в одной линии хранения (прототип и план миграции)"
  status: current
  created_at: "2026-10-10"
  updated_at: "2026-10-10"
  owners: ["project-owner"]
---

# Share/3: прототип линии хранения

Задача [MattoYuzuru/Mnema#425](https://github.com/MattoYuzuru/Mnema/issues/425), эпик
[MattoYuzuru/Mnema#419](https://github.com/MattoYuzuru/Mnema/issues/419). Решение, которое
проверялось: ADR CD-1/CD-2 из [архитектуры](../../../../architecture/community-decks.md#4-линия-неизменяемые-строки-cd-1).
База: `origin/main` 366407f9.

## Вывод

Две колоды одной линии **безопасно делят страницы и блоки** при работающем GC. Принятый
план (CD-1) подтверждён без альтернативы. Уточнения к архитектуре §4 перечислены ниже, они
не меняют решение, а фиксируют, как его реализовать.

## Что проверено

| Проверка | Как | Результат |
|---|---|---|
| GC не удаляет то, что достижимо из любой колоды линии | `LineageScopeSharingIntegrationTest` (Testcontainers, настоящий `StorageGc`): источник с двумя материалами, копия в той же линии (строка `deck`, ревизия 0 на корнях опубликованной ревизии, свои durable-пины), правка источника path-copy, своя страница копии со ссылкой на поддерево источника, tombstone источника, брошенный staging-мусор | Мусор собран, весь граф копии и её страница на месте, `readBatch` читает граф целиком |
| Удаление источника не ломает копию | Тот же тест: tombstone источника, затем GC до установившегося состояния | Граф копии цел, недостижимых объектов в линии 0. Пины источника снять нельзя, пока живут его ревизии: их держат FK `deck_revision → storage_pin` и `item_revision → storage_object`, поэтому будущий purge аккаунта ([MattoYuzuru/Mnema#409](https://github.com/MattoYuzuru/Mnema/issues/409)) сначала удаляет ревизии, на которые не ссылаются копии |
| Черновой DDL линии для материалов совместим с данными | [share-3-draft-lineage-items.sql](./share-3-draft-lineage-items.sql) на копии локальной БД владельца в одноразовом PostgreSQL 18 | 77 операторов за **82 мс** (2 616 ревизий материалов, 724 664 рёбер, 11 колод) |
| Копия читает строки линии | [share-3-fork-probe.sql](./share-3-fork-probe.sql) после DDL: копия самой большой колоды (1 317 материалов) | 1 317 голов вставлены одним `INSERT … SELECT`; после tombstone источника все 1 317 читаются; из 38 070 объектов, достижимых из корней копии, неудерживаемых 0 |

Почему это безопасно по построению: объект ядра хранения удерживается входящим ребром или
любым пином **своей линии** (`storage_pin` и `storage_edge` уже ключуются `reuse_scope_id`).
Durable-пин ревизии колоды живёт столько же, сколько сама ревизия (FK из `deck_revision`), и
ядро его не снимает. Поэтому копия со своими пинами на те же корни удерживает весь граф, что
бы ни случилось с источником. Данные хранения не содержат `deck_id`: дескрипторы хранят только
`memberKey`/`itemRevisionId` и `exerciseId`/`exerciseRevisionId`.

## Уточнения к архитектуре §4

1. **Строки на колоде несут scope колоды.** Головы, журналы, «Эталон», черновики, захваты,
   отметки «новое» и строки Study получают колонку `reuse_scope_id` (неизменяемая у колоды) с FK
   `(deck_id, reuse_scope_id) → deck` и ссылаются на строки линии через неё. Строки линии
   (`learning_item`, ревизии, превью, медиа-ссылки, привязки, цели, упражнения) ключуются
   `(reuse_scope_id, …)`; `deck_id`, `deck_revision_id`, `deck_sequence`, `owner_id` на них
   остаются колонками происхождения. FK на ревизию колоды-автора сохраняется: колоды не
   удаляются физически.
2. **Чтение начинается с колоды читателя.** Запрос «что есть в колоде D» идёт от голов D или от
   закреплённых корней манифеста ревизии D. Фильтр `WHERE <строка линии>.deck_id = :deck` —
   это фильтр происхождения, для копии он неверен. Каждый такой запрос переписывается.
3. **Снимок Study читает манифест, а не номер ревизии автора.**
   `StudySessionRepository.sourceBatch` сейчас ищет упражнение «на момент `deck_sequence`»
   через `exercise_definition.deck_id` и `exercise_revision.deck_sequence`. У копии своя
   нумерация и нет своих `exercise_definition`, поэтому генерация кандидатов должна читать
   закреплённый `exercises_root_id` (лист: `logical_key` = `exercise_id`, дескриптор содержит
   `exerciseRevisionId`) с курсором по ординалу. Это та же точка, на которую уже ключуется
   `study_candidate_generation(deck_id, exercises_root_id)`. Индекс
   `exercise_revision_snapshot_seek` после этого не нужен.
4. **Номер ревизии.** Уникальность `(…, item_sequence)` на материал заменяется цепочкой
   родителя `(reuse_scope_id, member_key, parent_revision_id, parent_item_sequence)`. Копия и
   источник могут независимо продолжить одну ревизию; после приёма обновления (эпик 2) номер
   своей правки копии может совпасть с ранее выданным, поэтому номер не уникален.
5. **Миграция отключает guards неизменяемости только на время backfill.** Таблицы
   `deck_item_change`, `content_media_ref`, `capture_note`, `editing_draft` и все таблицы Study
   с триггерами immutable запрещают `UPDATE`; миграция добавляет производную колонку под
   `DISABLE TRIGGER … / ENABLE TRIGGER` в той же транзакции Flyway.
6. **FK по происхождению на время между Share/4 и Share/5.** Пока привязки упражнений ещё
   ключуются колодой, Share/4 оставляет `UNIQUE (deck_id, member_key, revision_id)` на
   `item_revision` для FK из `exercise_content_binding` и добавляет его после смены PK
   (иначе PostgreSQL привязывает новый FK к удаляемому PK-индексу). Share/5 удаляет его.

## План миграции

**Share/4 — материалы** (черновик: [share-3-draft-lineage-items.sql](./share-3-draft-lineage-items.sql)):

1. `deck`: `UNIQUE (deck_id, reuse_scope_id)`; `learning_item`: `UNIQUE (reuse_scope_id, member_key)`.
2. Колонка `reuse_scope_id` + backfill из `deck` + `NOT NULL`: `deck_head_item`,
   `deck_item_change`, `deck_item_exemplar`, `editing_draft`, `capture_note`, `item_preview`,
   `content_media_ref`.
3. Снять FK по колоде на `item_revision` и `learning_item` (11 ограничений, список в черновике).
4. `item_revision`: PK `(reuse_scope_id, member_key, revision_id)`, `UNIQUE (reuse_scope_id,
   revision_id)`, `UNIQUE (…, item_sequence)` для голов, `UNIQUE (…, owner_id)` для медиа-ссылок,
   FK родителя по линии; снять `UNIQUE (deck_id, member_key, item_sequence)`.
5. `item_preview`, `content_media_ref`: PK и FK по линии.
6. FK строк колоды по линии и `(deck_id, reuse_scope_id) → deck`.
7. Код: `ItemRepository`, `ItemPreviews`, `AuthoringRepository`, `DeckInsightsRepository`,
   `ContextRepository`, `GenerationRepository`, `MediaCatalog`, `MediaManifestCatalog`,
   `MediaGcRepository` (ссылки на `content_media_ref`), `SpeechInputRepository`: все `INSERT`
   строк колоды пишут scope; все соединения с ревизиями идут по `(reuse_scope_id, member_key,
   revision_id)` от голов колоды.

**Share/5 — цели, упражнения, Study** (порядок FK):

1. `memory_objective`, `exercise_definition`: `UNIQUE (reuse_scope_id, objective_id)`,
   `UNIQUE (reuse_scope_id, exercise_id)`, `UNIQUE (reuse_scope_id, objective_key)`.
2. Колонка scope + backfill: `objective_head`, `deck_head_exercise`, `deck_exercise_change`,
   `exercise_new_mark`, `study_candidate_generation`, `study_candidate`, `study_presentation`,
   `study_evidence`, `study_policy_assignment` (guards immutable отключаются на backfill).
3. Снять FK по колоде: на `objective_revision` (5), `exercise_revision` (6),
   `memory_objective` (3), `exercise_definition` (3), привязки на `item_revision` (временный
   ключ происхождения из Share/4).
4. Перевести на линию PK и FK `objective_revision`, `exercise_revision`,
   `exercise_content_binding`, `exercise_media_ref`; guard `exercise_binding_objective_guard`
   сравнивает scope, а не `deck_id`.
5. FK строк колоды и Study по линии; ключи прогресса `(account_id, deck_id, objective_id)` не
   меняются.
6. Снять временный `item_revision_origin_key` и индекс `exercise_revision_snapshot_seek`.
7. Код: `ExerciseRepository`, `StudySessionRepository` (генерация по манифесту, п. 3 выше),
   `StudyProgressRepository`, `StudyRestartRepository`, `AssessmentRepository`,
   `GenerationRepository`, `ContextRepository`, функция `exercise_media_ready` (V21) и запросы
   медиа, которые соединяют `asset.owner_id = ref.owner_id` (они остаются верными: `owner_id`
   медиа-ссылки линии — владелец ассета).

**Окно и откат.** Миграция чисто DDL с backfill одной колонки; на локальных данных материалы
заняли 82 мс, упражнений там примерно вчетверо больше строк, то есть порядка десятых долей
секунды. В production данных меньше, окно определяется перезапуском сервиса, а не DDL. Перед
выкаткой Share/4 и Share/5: свежий offsite backup проверен, в PR записан откат
восстановлением из него (down-миграций и двойных записей нет, правило greenfield).

## Нагрузка

- **Ноль.** Ни одной копии: колонка scope на строках колоды и новые ключи не меняют планы
  чтения своих колод (PK-поиск, как раньше). GC работает так же, как до изменения.
- **H** (архитектура §12). Колонка `reuse_scope_id` добавляет 16 байт на строку головы: при
  200k копий × 300 единиц это около 1 ГБ сверх оценённых 14 ГБ указателей, в пределах триггера
  copy-on-write. Ключи строк линии становятся длиннее на 16 байт только у ревизий, их число
  от копий не растёт. Материализация голов одним `INSERT … SELECT` на 1 317 строк прошла без
  заметного времени; пачки ≤N строк на транзакцию задаёт Share/10.

## Не проверено

- Перенос FK Study (Share/5) не прогонялся как DDL: порядок выше составлен по фактическим
  ограничениям, проверка — в задаче Share/5 с ревью `adversarial-architect`.
- Production-объём не измерялся (read-only статистика — задача
  [MattoYuzuru/Mnema#462](https://github.com/MattoYuzuru/Mnema/issues/462)).
