---
artifact:
  id: github-execution-model
  type: execution-plan
  title: "Mnema v2 GitHub planning model"
  status: current
  created_at: "2026-08-15"
  updated_at: "2026-08-30"
  owners: ["project-owner"]
---

# Организация Mnema v2 в GitHub

## Решение

GitHub Projects достаточно для Mnema; отдельная Jira сейчас добавит синхронизацию без пользы. Projects поддерживает table, board/Kanban, roadmap, custom fields, charts и iterations ([GitHub Projects](https://docs.github.com/en/issues/planning-and-tracking-with-projects)). «Эпики» в личном repository лучше моделировать parent issues с sub-issues; GitHub поддерживает такую иерархию напрямую ([sub-issues](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/adding-sub-issues)). Organization issue types с типом `Epic` для личного user repository не нужны.

Целевая доска: [MattoYuzuru Project #4](https://github.com/users/MattoYuzuru/projects/4). Parent epics и reviewable tasks добавляются в существующую доску без новой taxonomy.

## Фактическое состояние Project #4

Project `Mnema Kanban` приватный и открыт. После refinement #73 2026-08-30 он содержит 106 linked items: 69 issues и 37 pull requests. `DraftIssue` отсутствуют и в рабочие отчёты не включаются. Новую доску создавать не нужно: Project #4 уже является execution surface.

Состояние на 2026-08-17: у всех 11 эпиков заполнены `Size` и `Estimate` в ideal agent-days. Даты стоят только у committed-работы — эпика #71 и его sub-issues #81–#84; у #45 и #58 просроченные даты из старого планирования сняты, потому что создавали ложное впечатление запланированной работы. Taxonomy не расширялась: labels 9, полей 18, views 6.

На 2026-08-15 в `MattoYuzuru/Mnema` открыты:

| Issue | Содержание | Как поступить в v2 |
|---|---|---|
| #58 `Short сессии` | обычная/короткая deck-scoped session | сохранить и включить в epic Study; уточнить как session budget, а не отдельный scheduler |
| #45 `Интегрировать платежный модуль` | устаревший Prodamus/self-employed flow | переписать под ИП, T‑Bank recurrent, offer/consent/receipts; не закрывать как выполненную |
| #35 `Создать рекомендательную систему` | streak, author profiles, fork metrics, recommendations | разделить: profiles/usage metrics в P1; recommendations отложить до достаточного usage signal |

Labels остаются только стандартные GitHub. Milestones используются лишь для реально committed calendar outcomes. У #73–#77 milestone снят: greenfield foundation/content/study/media ещё не прошли последовательный refinement, а #77 явно deferred. Доступ к приватному Project #4 подтверждён через GitHub API helper с project scope.

## Поля Project #4

Не дублировать одно и то же labels и fields. Labels описывают устойчивый вид работы; Project fields — приоритет и планирование.

Уже существуют `Status`, `Priority`, `Size`, numeric `Estimate`, `Start date`, `End date` и стандартные relation fields. Их нужно переиспользовать:

| Существующее поле | Значения/правило |
|---|---|
| Status | `Backlog`, `Ready`, `In progress`, `In review`, `Done`; новых значений пока не добавлять |
| Priority | уже есть `P0`, `P1`, `P2`; этого достаточно, `P3` не добавлять |
| Size | уже есть `XS`, `S`, `M`, `L`, `XL`; новый `XL` запрещён для Ready и обязан быть разбит |
| Estimate | уже есть numeric; считать в ideal agent-days, не дублировать Size |
| Start/End date | использовать только для committed work, не для всего backlog |

Owner decision: новую taxonomy сейчас не добавлять. `Workstream`, `Iteration`, `Risk`, `Target`, новые labels и отдельный `Blocked` status откладываются до доказанной навигационной проблемы. Parent issue, существующие fields и даты уже дают достаточную структуру.

Standard GitHub labels остаются как есть. Классификация задачи пишется в понятном title/body по [work item standard](./work-item-standard.md), а не кодируется новой системой labels.

## Представления

Уже существуют views `Backlog`, `Priority board`, `Team items`, `Roadmap`, `In review`, `My items`. Не создавать их дубли и новые views до реальной потребности:

1. переименовать/настроить **Backlog** как board по `Status`, сортировка Priority;
2. **Priority board** оставить обзором всего P0–P2;
3. **Roadmap** использовать с parent и Start/End date;
4. **In review** и **My items** оставить как операционные views;
5. блокировку описывать в Issue с конкретным условием разблокировки; отдельный field/view пока не нужен.

WIP limit — максимум одна `In progress` задача на один независимый агентный workstream и максимум три одновременно на владельца/reviewer. Генерация кода может быть параллельной; архитектурные контракты, schema/migration и release gate должны иметь одного ответственного интегратора.

## Parent issues вместо больших «эпиков»

Созданные parent issues:

1. [#70 Delivery foundation: CI, staging CD, rollback и recovery](https://github.com/MattoYuzuru/Mnema/issues/70) — первый implementation epic.
2. [#71 GitHub governance и agent/PR workflow](https://github.com/MattoYuzuru/Mnema/issues/71).
3. [#72 Security, supply chain и quality gates](https://github.com/MattoYuzuru/Mnema/issues/72).
4. [#73 Greenfield foundation, unified Identity и account-only cutover](https://github.com/MattoYuzuru/Mnema/issues/73).
5. [#74 LearningItem, immutable content revisions и полный frontend redesign](https://github.com/MattoYuzuru/Mnema/issues/74).
6. [#75 Deck-scoped Study, M:N exercises и новый scheduler](https://github.com/MattoYuzuru/Mnema/issues/75).
7. [#76 Greenfield media lifecycle и offline-ready references](https://github.com/MattoYuzuru/Mnema/issues/76).
8. [#77 Deferred AI capabilities после product validation](https://github.com/MattoYuzuru/Mnema/issues/77).
9. [#78 ИП на НПД, документы и Роскомнадзор](https://github.com/MattoYuzuru/Mnema/issues/78) — преимущественно human actions.
10. [#79 T‑Bank acquiring, recurring и receipt reconciliation](https://github.com/MattoYuzuru/Mnema/issues/79).
11. [#80 Concierge-запуск и продуктовая валидация](https://github.com/MattoYuzuru/Mnema/issues/80).

Parent issue описывает outcome, scope/out-of-scope, dependency graph, metric и exit gate. Реализация живёт в самостоятельных issues размером не больше 1–3 инженерных дней. GitHub поддерживает native sub-issues, но установленный repository helper пока не создаёт это relation: поэтому #73 использует проверяемый parent checklist и двусторонние `Parent #73` links в body. Это ограничение инструмента, а не разрешение складывать реализацию в один issue; каждый task всё равно имеет отдельный PR/owner/evidence.

## Очередь tasks epic #73

#73 разбит 2026-08-30. Все tasks добавлены в Project как `Backlog/P0`; только один reviewable task переводится в активную колонку одновременно. `Estimate` — ideal agent-days, фиктивные даты не назначаются.

| Issue | Outcome | Size / estimate | Depends on |
|---|---|---:|---|
| #139 | Greenfield decisions и supersession map | S / 1.5 | owner decisions |
| #140 | Runtime shell и canonical API boundary | M / 3 | #139 |
| #141 | Unified Identity & Account schema/issuer | M / 2.5 | #139–#140 |
| #142 | Account/profile behavior без standalone `user` | M / 3 | #141 |
| #143 | Replacement CI/CD topology | S / 2 | #140–#142 |
| #144 | Account-only transfer/reconciliation | M / 3 | #141–#142 |
| #145 | No-snapshot purge preflight/rehearsal | M / 3 | #143–#144, #76 target boundary |
| #146 | Legacy runtime/build removal | M / 3 | #74–#76 replacement gates, #145 |
| #147 | Production cutover и irreversible purge | S / 1.5 | #139–#146, #74–#76 release slices |

Архитектура и реализация будущих доменов не раскладываются внутри #73: #74–#76 получат собственные tasks только при старте их refinement. #77 остаётся `Backlog/P2` без milestone и implementation tasks до отдельной реактивации.

Существующие issues разобраны 2026-08-17 в рамках #83:

- #58 переформулирован как бюджет сессии внутри deck-scoped Study и связан с #75; замысел владельца сохранён, но это параметр отбора и остановки, а не второй scheduler. Базовый session shell не блокирует;
- #45 переписан под hosted T‑Bank checkout, рекуррентные списания, чеки и сверку вместо Prodamus, связан с #79; часть про сервисный аккаунт AI-провайдера вынесена в #77. Как выполненный не закрыт;
- #35 разделён: ранние profile/usage-метрики отделены от рекомендательного алгоритма, добавлено явное условие разблокировки отложенной части, `Priority` P2, связан с #80.

## Definition of Ready для sub-issue

- пользовательский/операционный outcome сформулирован одним предложением;
- известны affected contracts и out-of-scope;
- нет неразрешённого product/architecture выбора;
- есть наблюдаемые acceptance criteria, негативные случаи и a11y/security требования;
- перечислены тесты и verification command;
- для destructive/external work указаны approval и rollback boundary;
- issue помещается в 1–3 дня или разбит дальше.

## Definition of Done

- implementation и tests находятся в одном reviewable change;
- backend quality/static analysis/tests и frontend lint/tests пройдены для затронутого scope;
- architecture/API/docs обновлены вместе с contract;
- migration/retry/idempotency/authorization проверены там, где применимо;
- UI проверен desktop/mobile/keyboard/screen reader/reduced motion;
- PR связан с sub-issue, содержит evidence и residual risks;
- Project item переводится в `Done` после merge и проверки результата, не после генерации кода.

## Коммиты и PR

«Коммиты перед собой» лучше превратить в короткий audit trail:

- одна логическая задача — один branch и обычно один PR;
- conventional prefix по смыслу: `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `chore`;
- message объясняет outcome, например `feat(content): persist immutable learning item revisions`;
- не смешивать массовое удаление legacy, новую schema и UI в один commit;
- destructive deletion — отдельный reviewed commit после verified replacement;
- squash допустим для шумной реализации, но decision/migration commits сохраняются отдельно;
- PR template должен содержать issue, decision links, validation output, screenshots/a11y для UI, migration/rollback и residual risks.

## Порядок материализации бэклога

Materialization выполняется поэтапно:

1. ✅ Прочитаны существующие views, fields и linked items; `DraftIssue` нет.
2. ✅ Сохранена существующая taxonomy.
3. ✅ Созданы #70–#80 и добавлены в Project; приоритеты и milestones уточняются по текущим owner decisions.
4. ✅ #58/#45/#35 связаны с подходящими parents, устаревшие acceptance criteria переписаны (2026-08-17).
5. ✅ Первый пакет sub-issues создан только для #71: #81–#84, из них в `Ready` переводился по одному.
6. ✅ #73 уточнён и разбит на #139–#147; oversized parent возвращён в `Backlog`, а tasks оценены по 1–3 ideal agent-days.
7. Создавать Ready P0/P1 tasks следующих эпиков по мере снятия зависимостей и отдельного refinement.
8. Даты назначать лишь committed задачам с выполненным Definition of Ready.
9. Post-MVP идеи держать в Backlog без фиктивных дат.

Создание десятков задач до согласования contracts создаст ложную точность. Поэтому будущие #74–#76 пока описывают outcome/границы/acceptance, а их implementation tasks появятся во время отдельного refinement; #77 не активируется до продуктового сигнала.
