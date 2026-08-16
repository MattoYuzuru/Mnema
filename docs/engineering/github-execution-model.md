---
artifact:
  id: github-execution-model
  type: execution-plan
  title: "Mnema v2 GitHub planning model"
  status: current
  created_at: "2026-08-15"
  updated_at: "2026-08-15"
  owners: ["project-owner"]
---

# Организация Mnema v2 в GitHub

## Решение

GitHub Projects достаточно для Mnema; отдельная Jira сейчас добавит синхронизацию без пользы. Projects поддерживает table, board/Kanban, roadmap, custom fields, charts и iterations ([GitHub Projects](https://docs.github.com/en/issues/planning-and-tracking-with-projects)). «Эпики» в личном repository лучше моделировать parent issues с sub-issues; GitHub поддерживает такую иерархию напрямую ([sub-issues](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/adding-sub-issues)). Organization issue types с типом `Epic` для личного user repository не нужны.

Целевая доска: [MattoYuzuru Project #4](https://github.com/users/MattoYuzuru/projects/4). Владелец разрешил создать parent epics и добавить их в существующую доску без новой taxonomy. Остальные external changes по-прежнему проходят exact preview.

## Фактическое состояние Project #4

Project `Mnema Kanban` приватный и открыт. После materialization 2026-08-15 он содержит 76 linked items: 44 issues и 32 pull requests; 62 `Done`, 2 `Ready`, 11 `Backlog`, 1 `In progress`. `DraftIssue` отсутствуют и в рабочие отчёты не включаются. Поэтому новую доску создавать не нужно: Project #4 уже является execution surface.

На 2026-08-15 в `MattoYuzuru/Mnema` открыты:

| Issue | Содержание | Как поступить в v2 |
|---|---|---|
| #58 `Short сессии` | обычная/короткая deck-scoped session | сохранить и включить в epic Study; уточнить как session budget, а не отдельный scheduler |
| #45 `Интегрировать платежный модуль` | устаревший Prodamus/self-employed flow | переписать под ИП, T‑Bank recurrent, offer/consent/receipts; не закрывать как выполненную |
| #35 `Создать рекомендательную систему` | streak, author profiles, fork metrics, recommendations | разделить: profiles/usage metrics в P1; recommendations отложить до достаточного usage signal |

У repository нет milestones, а labels пока только стандартные GitHub. Доступ к приватному Project #4 подтверждён через GitHub CLI со scopes `read:project`/`project`. Issues #70–#80 созданы, добавлены в Project и получили существующие `Status`/`Priority`; других Project settings не меняли.

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
4. [#73 V2-фундамент и account-only cutover](https://github.com/MattoYuzuru/Mnema/issues/73).
5. [#74 Нативный контент, редактор и renderer](https://github.com/MattoYuzuru/Mnema/issues/74).
6. [#75 Deck-scoped Study и платформа упражнений](https://github.com/MattoYuzuru/Mnema/issues/75).
7. [#76 Медиа, storage и offline-compatible contracts](https://github.com/MattoYuzuru/Mnema/issues/76).
8. [#77 Managed AI, оценка провайдеров и квоты](https://github.com/MattoYuzuru/Mnema/issues/77).
9. [#78 ИП на НПД, документы и Роскомнадзор](https://github.com/MattoYuzuru/Mnema/issues/78) — преимущественно human actions.
10. [#79 T‑Bank acquiring, recurring и receipt reconciliation](https://github.com/MattoYuzuru/Mnema/issues/79).
11. [#80 Concierge-запуск и продуктовая валидация](https://github.com/MattoYuzuru/Mnema/issues/80).

Parent issue описывает outcome, scope/out-of-scope, dependency graph, metric и exit gate. Реализация живёт в sub-issues размером не больше 1–3 инженерных дней. Checklist не заменяет sub-issues, если работа имеет отдельный PR, owner или acceptance evidence.

## Очередь первых sub-issues

Parent epics уже materialized. Таблица ниже — ещё не созданные sub-issues; они проходят refinement по [work item standard](./work-item-standard.md) перед внешней записью. `Estimate` — ideal agent-days; calendar placement остаётся условным до editor spike.

| Parent | Sub-issue | Priority | Iteration | Size / estimate | Depends on |
|---|---|---|---|---:|---|
| Platform | Исправить non-atomic/cancellable deploy и ввести release identity/rollback | P0 | 17–31 Aug | L / 5 | delivery audit |
| Platform | Поднять изолированный staging namespace и namespace-scoped deploy identity | P0 | 17–31 Aug | M / 3 | server/DNS access |
| GitHub | Ввести main ruleset, required PR/checks и least-privilege Actions | P0 | 17–31 Aug | M / 3 | exact owner approval |
| Security | Включить Dependabot/CodeQL/dependency review и image/SBOM policy | P0 | 17–31 Aug | M / 3 | security policy |
| Foundation | Зафиксировать P0-контракты v2 и corpus golden fixtures | P0 | 17–31 Aug | S / 1.5 | owner decisions |
| Content | Проверить Angular editor на IME, ruby, RTL, mobile и a11y | P0 | 17–31 Aug | M / 3 | dependency permission after evidence |
| Content | Описать versioned AST P0 и security policy renderer | P0 | 17–31 Aug | M / 3 | editor spike |
| Foundation | Создать fresh v2 PostgreSQL schema и immutable revision API | P0 | 17–31 Aug | L / 5 | AST contract |
| Content | Реализовать safe native renderer и split editor/preview | P0 | 17–31 Aug | L / 5 | AST contract |
| Platform | Добавить PostgreSQL 18/MinIO protocol E2E harness | P0 | 17–31 Aug | M / 3 | media-ref contract |
| Study | Реализовать deck-scoped Browse и reveal/self-rating | P0 | 17–31 Aug | M / 3 | revision API + renderer |
| Study | Реализовать typed answer и idempotent attempt contract | P0 | 17–31 Aug | L / 4 | objective/exercise contract |
| Foundation | Отрепетировать account-only export/import и restore | P0 | 17–31 Aug | M / 3 | account allowlist |
| Platform | Прогнать blackbox E2E, renderer security, a11y и load baseline | P0 | 17–31 Aug | M / 3 | integrated staging slice |
| Billing | Зафиксировать plan/entitlement/trial/quota/usage contracts | P0 | 1–7 Sep | M / 3 | staging go/no-go + 299 ₽ offer |
| Billing | Реализовать idempotent quota reserve/commit/refund ledger | P0 | 1–7 Sep | L / 4 | billing contract |
| Billing | Добавить fake payment adapter и subscription state-machine tests | P0 | 1–7 Sep | M / 3 | billing contract |
| AI | Собрать golden eval из 100 fixtures для DeepSeek/fallback | P0 | 1–7 Sep | M / 3 | synthetic corpus + provider accounts |
| Legal | Подтвердить НПД, зарегистрировать ИП и собрать legal documents | P0 | 8–14 Sep | L / human | owner + lawyer/accountant |
| Payments | Получить T‑Bank terms/receipt answer и интегрировать sandbox | P0 | 8–14 Sep | L / 5 | merchant credentials + legal text |
| Launch | Провести closed-alpha go/no-go и записать residual risks | P0 | after gates | S / 1 | all P0 gates |

Сумма staging iteration превышает безопасную capacity и описывает целевой envelope. После CI/CD и editor/AST spikes Project оставляет в committed `Ready` только поднабор, который помещается в capacity; остальное переносится без скрытого сокращения тестов.

Существующие issues:

- #58 становится sub-issue/последующим refinement внутри Study parent, но не блокирует базовый session shell;
- #45 переименовывается и получает новые T‑Bank/legal acceptance criteria вместо создания дубля;
- #35 остаётся P2: profile/subscriber metrics отделяются позже, recommendation algorithm не входит в MVP.

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
3. ✅ Созданы #70–#80 и добавлены в Project: #70 и #72–#79 `Backlog/P0`, #71 `In progress/P0`, #80 `Backlog/P1`.
4. Следующим пакетом связать #58/#45/#35 с подходящими parents и переписать устаревшие acceptance criteria.
5. Создавать только Ready P0/P1 sub-issues из утверждённого delivery plan.
6. Даты назначать лишь committed задачам с выполненным Definition of Ready.
7. Post-MVP идеи держать в Backlog без фиктивных дат.

Создание десятков задач до согласования contracts создаст ложную точность. Сначала принимаются content format, progress-on-edit, license/repository boundary и двухнедельный scope; затем Project становится исполнимым источником истины.
