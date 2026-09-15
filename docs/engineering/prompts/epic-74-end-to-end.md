# Запуск Epic #74 end to end

Ты — ведущий инженер Mnema. Выполни Epic #74 в MattoYuzuru/Mnema от актуального состояния репозитория до проверенной реализации, влитой в main через небольшие защищённые PR. Это запрос на выполнение: после необходимой ориентации переходи к работе, а не останавливайся на плане.

Рабочая директория: /Users/m.ryabushkin/Projects/personal/Mnema.
Epic: https://github.com/MattoYuzuru/Mnema/issues/74.
Project: https://github.com/users/MattoYuzuru/projects/4/views/1.

Цель — реальные собственные колоды и LearningItem, экономные immutable revisions, native content format, безопасный renderer, серверные черновики и полноценный новый Angular authoring UI. Первое сквозное доказательство: создать колоду → оставить запись «На потом» → оформить материал → сохранить → перезагрузить страницу → продолжить редактирование. Это первый milestone, а не весь Definition of Done эпика.

## 1. Контекст и границы

Работай без предположения, что у тебя есть предыдущая переписка. Сам прочитай AGENTS.md и применимые вложенные инструкции, затем:

- docs/engineering/work-item-standard.md;
- docs/engineering/epic-74-refinement.md;
- docs/engineering/v2-delivery-plan-2026-08.md;
- docs/decisions/owner-decisions-2026-08.md;
- docs/product/authoring-and-study-workflows.md;
- docs/architecture/content-platform-v2.md;
- docs/architecture/learning-content-format-v2.md;
- docs/architecture/revision-storage-and-runtime-boundaries.md;
- docs/frontend/design-and-experience-2026-09.md;
- design/prototype/README.md и визуальные свидетельства прототипа.

Другие документы подключай по конкретной необходимости. Используй применимые установленные skills: главный агент самостоятельно читает их обязательные инструкции, а не делегирует их интерпретацию.

Проверь реальный код, версии, tests/CI, рабочее дерево, удалённую ветку main, текущий #74 и существующие связанные задачи/PR. Не повторяй уже завершённую работу и не создавай дубликаты задач. PR #170 — исторический источник принятых документов, не гарантия текущего состояния.

На момент подготовки этого промпта текст #74 старее docs: там жёстко указан delta + head и ещё не выбран visual direction. Новые документы принимают paper/antiquity/indigo direction, но сохраняют физическую схему хранения как proposed до измерений. Сверь фактическое состояние: приведи описание эпика и acceptance к уже принятым решениям, сохрани объяснение изменения. Материальную новую неоднозначность вынеси владельцу; не разрешай её молча и не ослабляй критерии ради закрытия.

Не пересматривай весь продукт заново. В #74 не входят scheduler, реальные study sessions/exercises (#75), media lifecycle (#76), AI (#77), каталог, billing, native offline или пользовательский fork/pull/PR UI. Нужны их документированные content/rendering seams и доказательство пригодности хранения, а не скрытая реализация соседних эпиков.

## 2. Полномочия

В рамках #74 разрешаю local implementation, изолированные worktrees и disposable test resources, исследования и benchmarks на синтетических данных, создание/уточнение связанных child issues, обновление Project, ветки, commits, push feature branches, PR, исправление CI и squash-merge после всех обязательных проверок. Разрешаю закрывать только завершённые задачи #74 и сам #74 после доказанного выполнения всех его критериев. Не закрывай соседние эпики автоматически.

Ведущий агент владеет внешними GitHub writes и integration branch. Не спрашивай повторного разрешения на эти обычные действия при появлении новых номеров PR, веток и SHA в том же scope. Соблюдай preview/verification установленного GitHub workflow. Сливай только через protected PR; без direct push в main, force-push, bypass и изменения защиты или quality thresholds. Не меняй Git identity и не добавляй attribution trailers. Не удаляй ветки отдельной командой без необходимости и отдельного разрешения.

Production deployment, production data/reset/migration execution, shared-environment destructive operations, публикация вне GitHub и платные покупки не разрешены. Локальные schema migrations и изолированные DB tests входят в работу; применение миграций к production — нет. Штатные разрешённые non-production CI workflows не превращают этот запуск в разрешение на production cutover #147.

Сохраняются точки согласования:

1. Перед новой dependency либо необходимым upgrade с изменением dependency files покажи точные версии/лицензии, необходимость, альтернативы и риски; дождись разрешения. Не устанавливай пакет «для пробы» в обход этого правила.
2. После storage-spike представь evidence, рекомендуемую схему и rejected alternative для решения владельца до превращения proposed persisted schema в принятую реализацию.
3. Изменение принятых продуктовых правил или значимое расширение scope требует решения владельца.

Готовь конкретный короткий запрос на согласование, а не вопрос «можно продолжать?». При ожидании решения продолжай независимые разрешённые задачи. Если полезных независимых задач больше нет, сохрани checkpoint и явно сообщи, какое решение блокирует продолжение. Не выдумывай одобрение из отсутствия ответа.

## 3. Команда и модели

Я явно разрешаю сабагентов и model/effort overrides для этого запуска. Предпочтительная конфигурация: ведущий gpt-6-astra high; исполнители gpt-5.6-sol high; сложное storage/concurrency и независимое architectural review — gpt-6-astra high, с xhigh для конкретного трудного вопроса. Medium используй для узких fixtures, механических изменений и документации при чётких критериях.

Если ведущая модель уже выбрана иначе, не утверждай, что сменил её инструкцией. Используй доступные настройки инструментов. Недоступность model override не блокирует реализацию: сообщи фактическую конфигурацию и сохрани роли. Не считай Sol xhigh заведомо эквивалентной Astra high и не выдавай оценку расхода за измеренный факт.

Планируй максимум четыре одновременно активных агента, включая себя, либо фактический меньший лимит среды. Это потолок, не квота. Не создавай рекурсивные команды; сабагенты не делегируют дальше без согласования с ведущим.

Начальная раскладка:

- Ты: dependency graph, актуализация scope, acceptance matrix, общие контракты и интеграция; затем собственная реализация центральных backend seams. Не становись только диспетчером отчётов.
- Storage specialist: ограниченный R74-S, synthetic harness, сравнение вариантов и измерения. Не утверждает схему от имени владельца.
- Frontend/editor specialist: R74-E, версии/лицензии/совместимость и подготовка authoring UI. До dependency approval — разрешённая исследовательская работа и независимый shell на существующих возможностях.
- Independent verifier: критерии, adversarial cases и review конкретного готового результата. Активируй только когда есть независимая полезная работа; он не редактирует проверяемую реализацию.

Роли можно менять после завершения задач. После контрактов две implementation lanes — backend и frontend; независимый reviewer проверяет законченные slices, пока ведущий занимается интеграцией. Если AST/storage ещё не согласованы, renderer может проверять fixtures, но production persistence/editor binding ждут общей границы.

Каждому сабагенту дай: конкретный outcome, обязательные источники, base SHA, ownership файлов/модуля, входные и выходные контракты, запреты, проверки и формат отчёта. Отчёт: что изменено, commits/files, доказательства и команды, unresolved findings, следующий handoff.

Не назначай двум пишущим агентам одни файлы или migrations/version allocation. При отдельных worktrees координируй отдельные ветки, ports, DB/container names и test resources. В общем checkout только ведущий делает switch/commit/merge; пути редактирования разделены. Главные docs, shared AST/API schemas, dependency files, CI и миграционная нумерация имеют одного owner. Не запускай несколько полных quality gates на изменяющемся общем дереве одновременно.

Не дублируй работу агента, пока он её выполняет. После завершения прочитай его полный отчёт, все изменённые документы и reviewable diff, проверь ключевые утверждения и интегрируй результат. Если задача изменяется — сначала согласуй новый ownership. Независимое ревью не является GitHub approval другого человека.

## 4. Порядок выполнения

Составь короткий план зависимости задач и таблицу «требование → child issue → реализация → проверка → состояние». Разбей работу на reviewable slices по стандарту репозитория. Не переводи весь epic в Ready одной большой задачей и не делай один гигантский PR.

### Этап A: снять техническую неопределённость

Выполни R74-S и R74-E из epic-74-refinement. Это ограниченные проверки, не новый многонедельный discovery. Timebox в плане — предел исследования, не основание объявить недоказанное решение готовым.

Storage: existing Java/PostgreSQL; fixtures 1k/10k/50k, отдельная 100k fork fixture, большой документ и 1,000 последовательных правок. Измеряй rows, heap/TOAST, indexes, WAL, latency и стоимость чтения истории, а не только размер JSON. Проверь metadata-only save, единичный edit/delete/reorder, bulk publication, fork-of-fork, CAS/idempotency, crash boundaries и reachability. Не обещай constant-time без определения изменяющихся параметров и доказательств.

Editor: 1–2 поддерживаемых кандидата, native AST adapter, round-trip, stable node IDs, unknown-node preservation, длинный русский текст, IME, RTL/LTR, ruby, paste/undo/redo и mobile editing. Используй официальную документацию точных версий. ProseMirror/Tiptap — кандидаты, не заранее одобренная зависимость. UI-library state не становится каноническим persisted format.

Параллельно можно строить согласованный paper shell и тестовую стратегию. Angular major upgrade, если нужен, выдели в отдельный reviewable child и согласуй dependency changes до применения; не смешивай его с новым persisted contract.

### Этап B: общий контракт

После необходимых решений зафиксируй минимальную достаточную схему, AST/node capabilities, API/error fixtures и ownership. Общие контракты включают deck summaries/details, paged members, material read/save, revision/base version, atomic publication, draft lifecycle и capture conversion.

Backend и frontend используют одни success/error fixtures. Контракт включает ACL, limits, CAS/conflict, retry/idempotency, missing/unsupported content и границу сохранённого/несохранённого. Изменение договора проходит через одного owner с обновлением обеих сторон.

### Этап C: реализация и ранняя интеграция

Выполни C74-1/2/3 и F74-1/2/3 из плана с дальнейшим делением oversized slices. Backend storage/API/drafts и frontend shell/renderer/editor идут параллельно только после своих prerequisites. После первого минимального API подключай настоящий backend, не откладывай всю интеграцию до конца.

Реализуй полноценные scoped authoring/Browse flows и исходные критерии #74: happy path, empty/loading/error/forbidden, сохранение/восстановление/конфликт, длинный контент, responsive/a11y и replacement cleanup. Проверь границу существующей Identity & Account интеграции, не переписывай её backend в этом epic.

Golden fixtures должны одинаково интерпретироваться editor/preview/Browse и переиспользуемым renderer для будущего Study. Это не разрешение реализовать #75 ради демонстрации; отсутствие scheduler не компенсируй фиктивным «готовым обучением».

Удаляй заменённые canonical v1 content/frontend пути в owning slices. Не сохраняй legacy builder/renderer/template dependence ради удобства. Не удаляй целиком runtime/media/import/AI модули, ещё принадлежащие другим эпикам: соблюдай #146/#147 boundary и явно фиксируй отложенные удаления.

### Этап D: проверка и завершение

На стабильном candidate независимый агент проверяет исходные требования, negative/adversarial cases, security и заявленные measurements. Исправь подтверждённые блокеры; не понижай их важность ради merge. Некритичные остаточные проблемы документируй с owner/action и объяснением, почему они не нарушают acceptance.

Доведи каждую готовую часть до защищённого merge, а затем проверь интегрированный main. Не объявляй весь epic завершённым после первого happy path или создания PR. Не расширяй #74 до всего продукта, если оставшаяся потребность действительно принадлежит #75/#76.

## 5. Обязательные инварианты

- LearningItem — deck-local логическая сущность. Physical block sharing не создаёт общего progress или cross-deck ссылочного материала. Экземпляры колод независимы.
- Metadata edit не создаёт revisions всех items и копии всего membership. Item edit переиспользует неизменённое содержимое. Immutable — семантика версии, не обязанность дублировать документ целиком.
- Saved revision публикуется атомарно. CAS/conflict и повтор command не теряют изменения и не создают дубли. Active readers не видят частично опубликованную версию.
- EditingDraft восстанавливается с сервера после подтверждённого сохранения; несколько вкладок/документов имеют явную conflict семантику. Cache eviction не означает потерю acknowledged draft.
- CaptureNote «На потом» долговечна, имеет createdAt, не имеет idle TTL и не участвует в обучении. Повтор convert не создаёт второй материал и не теряет исходную запись.
- Материал и exercise projection различны: front/back не обязательная структура LearningItem. Media/exercise seams следуют каноническим docs.
- Typed document не исполняет пользовательские HTML/JS. Протестированы sanitization, URL schemes, node capabilities, content limits и авторизация чтения по прямому ID.
- Greenfield replacement: без /v2 compatibility routes, dual writes/reads и обёрток вокруг v1 product code.
- Новый UI — paper/antiquity/indigo, русский B2C, семантический HTML, keyboard/focus/contrast/reduced motion. Не переносить Liquid Glass или memory-only demo architecture.
- App shell и первый полезный экран не ждут загрузки editor/media/study bundles. Длинные списки и документы имеют измеренные границы работы; lazy loading не заменяет быстрый первый полезный экран.
- Не вводи Redis, новый datastore, service split или sharding без доказанной потребности и необходимых approvals. Stateless replicas не лечат плохие запросы, unbounded fan-out и DB contention.

## 6. Проверки и GitHub delivery

В начале выясни актуальные команды CI и окружение; не доверяй устаревшей локальной обёртке. До каждого push и перед merge выполняй полный требуемый AGENTS quality gate на точном candidate commit. Worker-targeted tests полезны, но не заменяют интеграционный gate. Не ослабляй coverage и не превращай обязательные DB tests в skipped.

Ожидаемые команды на исходном состоянии: backend ./gradlew quality; frontend npm ci, npm run lint, npm run test, npm run build. Сверь текущие workflows, container-test verifier и policy/contract/integration scripts: этот список не заменяет полный repository gate. Если gate отсутствует или среда блокирует запуск, явно укажи это; не называй результат зелёным.

Помимо unit tests нужны реальные DB integration/concurrency tests, shared contract/golden tests, renderer adversarial corpus, authoring E2E с настоящим API, draft restore/conflict/retry cases, deck ownership isolation и large-content/large-deck measurements. Проверяй keyboard, responsive desktop/mobile, reduced motion, IME/RTL/ruby и screen reader по acceptance. Не выдавай автоматическую a11y проверку за выполненный manual screen-reader test.

Сохраняй команды, конфигурацию измерений, результаты, screenshots и выявленные ограничения в evidence, без секретов и production/private данных. Замеры синтетической нагрузки не называй production SLO или доказательством «любого highload».

Перед каждым merge перечитай текущий head SHA, base/rules, threads и checks; требуется up-to-date branch, green backend-quality/frontend-quality, все остальные обязательные проверки и разрешённый squash. При чужих материальных изменениях останови затронутый PR и согласуй scope. После merge проверь merged SHA, интеграционное состояние и относящиеся к доставке CI/non-production workflows; production boundary не пересекай.

## 7. Длительная работа и честное завершение

Продолжай от milestone к milestone, пока есть безопасный разрешённый следующий шаг. Не заканчивай ответом «могу продолжить», перечнем будущих действий или созданием PR при наличии работы внутри мандата. Это не обещание бессрочного процесса: при техническом прерывании запуск должен быть возобновляемым.

Веди один компактный checkpoint, например docs/engineering/epic-74-execution.md: принятые решения/ожидаемые approvals, текущий dependency graph, lane ownership, issue/PR/branch/SHA, выполненные проверки, блокеры и ближайшие действия. Обновляй его после существенных milestones. После compaction восстанавливай контекст по checkpoint и фактическому Git/GitHub, не начинай исследование заново. Не сохраняй внутренние рассуждения — только решения, факты и evidence.

Не расходуй контекст на постоянные полные логи и дублирование документов. Жди работающих агентов/CI предусмотренными инструментами с ограниченными ожиданиями. Сообщай по-русски короткие обновления о реальном результате и следующем шаге с частотой, требуемой средой. Если нужны owner answers, объединяй связанные вопросы и предлагай обоснованный вариант.

Definition of Done: все актуальные критерии #74 подтверждены; согласованные children завершены; production-quality реализация находится в main; integrated authoring E2E и обязательные quality/security/a11y/performance evidence получены; docs и Project отражают факты; нет незакрытых acceptance blockers. Merge не означает deployed или production-verified.

Если Done недостижим без owner action/недостающего доступа, не подменяй его частичным успехом. Перечисли завершённое, точный блокер, подготовленное решение и шаг возобновления. Не уменьшай scope и не закрывай epic молча.

Финальный отчёт: что пользователь теперь может сделать, закрытые criteria/issues, merged PR/commits, проверки и существенные измерения, остаточные ограничения, статус deploy отдельно. Начинай с текущего состояния и первого полезного параллельного этапа.
