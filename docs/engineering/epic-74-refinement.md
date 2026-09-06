---
artifact:
  id: epic-74-refinement
  type: implementation-plan
  status: proposed-execution-slices
  created_at: "2026-09-06"
  updated_at: "2026-09-06"
  owners: ["project-owner"]
---

# #74: подготовка к реализации

Цель — дать backend/frontend исполнителям одинаковые границы и короткие проверяемые
задачи. Продуктовые решения и paper direction одобрены владельцем; конкретные storage
schemas, editor engine и API DTOs ещё не выбраны. Этот план не утверждает, что spikes
уже выполнены, и не переводит весь epic в Ready. Публикация документов/прототипа через
защищённый PR разрешена; production, данные и новые dependencies этим шагом не меняются.

Источники: [owner workflows](../product/authoring-and-study-workflows.md),
[storage proposal](../architecture/revision-storage-and-runtime-boundaries.md),
[native format](../architecture/learning-content-format-v2.md),
[design handoff](../frontend/design-and-experience-2026-09.md).
Learning API уже имеет UUID/CAS/idempotency/error foundation; content/study domains
ещё отсутствуют. Standalone HTML prototype — визуальный материал, не кодовая основа Angular.

## Два первых research-задания

Оба задания могут выполняться независимо. Timebox — до двух инженерных дней на
каждое, не обещание автоматического завершения за это время. Если доказательств
недостаточно, результат — конкретный оставшийся вопрос и ограниченное продолжение,
а не молчаливое утверждение решения.

### R74-S: выбрать реализацию экономных revisions

Решение: пригодны ли предложенные immutable semantic blocks + persistent paged
manifests в PostgreSQL для первого implementation slice или нужны bounded deltas
с явно ограниченными checkpoints. Literal Git/JGit не внедряется.

Scope: изолированный synthetic harness; никаких production migrations, очистки
данных или API, зависящего от legacy core. Использовать текущие Java/PostgreSQL и
test conventions, не добавлять datastore/dependency ради benchmark.

Fixtures: 1k/10k/50k members; отдельная 100k fork fixture; короткий item и большой
документ; 1,000 последовательных правок одного материала; сохранение только deck
metadata; единичная правка/удаление/reorder; bulk edit; fork-of-fork; concurrent heads.

Evidence:

- metadata save не создаёт item revisions/копий членства;
- изменение одного блока переиспользует остальные; измеряются rows, heap/TOAST,
  indexes и WAL, а не только размер JSON ответа;
- стоимость обычного/historical read не растёт с длиной истории;
- fork имеет bounded synchronous writes и первую страницу без полного index build;
  inherited member keys не связывают логический прогресс двух колод;
- CAS/idempotency исключают lost updates/дубли; worker crash перед final publication
  не открывает частичную версию; GC не удаляет reachable fork/draft/attempt content;
- seed/cursor traversal и редкая eligibility не скрывают полный synchronous scan.

Результат: воспроизводимые команды и raw summary с hardware/configuration,
выбранный вариант и rejected alternative, proposed DDL/indexes/page limits,
transaction/staging/GC boundaries и одна следующая reviewable storage-задача.
Measured latency не выдаётся за production SLO. Незакрытая критическая инварианта
блокирует принятие storage schema, но не работу над визуальными компонентами.

### R74-E: выбрать редактор без зависимости формата от UI-библиотеки

Решение: какой поддерживаемый editor engine обеспечивает native AST, доступное
редактирование и приемлемую Angular integration. Сравнить 1–2 реальных кандидата
из официальной документации; ProseMirror/Tiptap — исходные кандидаты исследования,
не уже выбранные или разрешённые production dependencies.

До установки новых пакетов показать владельцу точные package/version/license,
почему platform options недостаточны, и альтернативу; получить разрешение на
dependency change. Чтение документации и фиксация требований этого не требуют.

Fixtures: длинный русский документ, IME и Japanese ruby, mixed RTL/LTR, стабильные
node IDs, unknown node round-trip, code/math placeholders и media references.
Проверить desktop editor/preview split, mobile selection/keyboard, undo/redo,
paste sanitation, focus, keyboard-only editing и отсутствие потери acknowledged draft.
Production media upload и все богатые renderers не строятся в spike.

Результат: небольшой изолированный Angular prototype после dependency approval,
проверенные версии/лицензии, browser/device evidence и ограничения, выбранный adapter
между Mnema AST и editor state, proposed initial node schemas/capabilities и
следующая reviewable editor-задача. Screenshot без editing/round-trip evidence
недостаточен. Сохранять весь private editor state как native format запрещено.

## Контракты после spikes

Одна bounded contract-задача связывает принятый storage результат с UI fixtures.
Точные canonical URLs фиксируются здесь только после исследования; `/v2` и legacy
aliases не добавляются. Для каждого контракта нужны success/error fixtures,
ownership, expected version, retry/idempotency и bounded request/response.

| Contract family | Обязательная семантика |
|---|---|
| Deck summaries / details | Cursor page; header отдельно от списка материалов; без per-deck N+1 и всего содержимого в summary |
| Material read / save | Deck-local identity, exact revision/format capabilities, expected head, atomic publication, conflict без потери ввода |
| EditingDraft | Server acknowledgement, base/row version, expiry/limits, several documents/tabs, explicit conflict |
| CaptureNote / convert | CreatedAt, без idle TTL/study eligibility; conversion retry создаёт один материал и сохраняет source |
| Exercise seam (#75) | Versioned projections, stable node references, eligible pool, separate answer spec; сохранение не требует готового scheduler |
| Media seam (#76) | Authorized asset references/capabilities и missing/processing states; не inline binary и не public URL как ACL |

Перед parallel delivery backend и frontend используют одинаковые contract fixtures.
Mock tests не заменяют итоговую интеграцию. Изменение контракта обновляет обе стороны
и negative fixtures в одной согласованной задаче.

## Предлагаемая очередь implementation slices

| Slice | Наблюдаемый результат | Зависимость / gate |
|---|---|---|
| C74-1 Storage kernel | Saved roots/revisions с bounded reads и CAS, доказанные storage invariants | R74-S и отдельный schema review |
| C74-2 Private deck/material API | Создать, прочитать страницами и сохранить material без legacy API | C74-1 + contract fixtures |
| C74-3 Draft/capture lifecycle | Правки восстанавливаются; заметка не истекает; convert идемпотентен | C74-2 + lifecycle contract |
| F74-1 Paper app shell | Landing/навигация/свои колоды на Angular, lazy routes, empty/error/loading | Design handoff; можно начать на fixtures |
| F74-2 Editor adapter | Native AST round-trip, split preview и stable selections | R74-E + node contract |
| F74-3 Authoring integration | Create → capture → material → save → reload → edit | C74-2/3 + F74-1/2 |

Это границы декомпозиции, не гарантированно готовые 1–3-day issues: oversized slice
делится перед переводом в Ready. В реализации #74 нет фиктивного StudyState ради
демонстрации готового обучения. #75 добавляет actual exercises/reducer/session modes;
#76 реализует media, не native offline приложения. Каталог/forks UI/AI/billing не
блокируют own-deck loop.

## Проверки, риски и approvals

Будущие implementation PRs запускают repository backend quality (включая реальные
PostgreSQL tests и coverage), frontend lint/tests/build, contract/failure tests и
visual/keyboard проверку изменённых flows. Никакого отключения container tests или
порогов ради зелёного gate. На текущем research PR проверяется документация и demo,
а не притворная производительность ещё отсутствующего backend.

Материальные approvals: новая dependency; изменение принятой продуктовой семантики;
окончательный переход от proposed storage к принятой схеме после evidence;
production/data cutover отдельно в #147. Точные тексты UI и конфигурируемые защитные
defaults можно уточнять инженерно в уже согласованных границах.

Rollback текущего docs/prototype изменения — обычный revert через PR, без DB effects.
Spikes удаляются/изолируются до shipping; owning implementation заменяет canonical
path напрямую. Будущий production cutover не наследует разрешение на merge этого PR.
