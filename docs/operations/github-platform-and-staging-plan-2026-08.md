---
artifact:
  id: github-platform-and-staging-plan-2026-08
  type: operations-plan
  title: "GitHub platform, CI/CD and staging plan"
  status: current
  created_at: "2026-08-15"
  updated_at: "2026-08-17"
  owners: ["project-owner"]
  evidence_date: "2026-08-17"
---

# GitHub, CI/CD и staging Mnema v2

## Решение

Первый implementation epic — не перенос content model, а безопасный delivery foundation. До изменения production-схемы Mnema нужны защищённый PR flow, воспроизводимый release artifact, изолированный staging, некэнселируемый deploy, blackbox verification и проверенный rollback/restore.

Период 17–31 августа полностью остаётся staging-итерацией. Billing/quota начинается 1 сентября, T‑Bank/legal sandbox — не раньше 8 сентября. Existing v1-код, который будет удалён, не получает искусственное покрытие: новые quality gates сначала защищают account/auth, delivery и protocol boundaries, затем каждый новый v2 vertical slice.

Первичный аудит 15 августа был read-only. Часть описанных ниже изменений применена 16–17 августа в рамках [#71](https://github.com/MattoYuzuru/Mnema/issues/71); что именно применено, видно в колонке «Факт» и в разделе [External-change status](#external-change-status-и-следующий-gate).

## Фактическое состояние GitHub

Аудит 15 августа, состояние обновлено 17 августа после применения governance-пакета:

| Поверхность | Факт | Вывод |
|---|---|---|
| Repository | public `MattoYuzuru/Mnema`, default branch `main` | публичные Actions minutes не являются главным ограничением |
| Branch/rulesets | **применено 2026-08-16:** ruleset `main protection` (id 20917643), `enforcement: active`, `bypass_actors` пуст; правила `pull_request`, `required_status_checks`, `deletion`, `non_fast_forward`, `required_linear_history` | прямой push в `main` отклоняется; required checks `backend-quality` и `frontend-quality` привязаны к integration id 15368 |
| История интеграции | до 2026-08-16 последние 11 commits попали напрямую в `main`, у последних 25 merged PR нет review; PR #59 смержен при красном чеке, PR #69 — через ~8 секунд после создания | обход технически закрыт; независимого review по-прежнему нет — в репозитории один maintainer, требование апрува заблокировало бы любой merge |
| Actions token | default workflow permission — write | перейти к repository read + явным job-level permissions |
| Actions policy | разрешены все Actions; full-SHA pinning не требуется | supply-chain policy слишком широкая |
| Workflows | `pull-request.yaml` и `deploy.yaml` | база есть, но CI и production mutation сцеплены небезопасно |
| Environment `prod` | существует, protection rules нет | deployment jobs должны ссылаться на environment; нужны owner-approved gates |
| Secrets | 27 production secrets находятся на repository level; environment secrets/variables пусты | build/PR boundary должен быть отделён от staging/prod credentials |
| Merge settings | **применено 2026-08-16:** только squash; `delete_branch_on_merge` включён; auto-merge выключен | merge commit и rebase запрещены и на уровне ruleset (`allowed_merge_methods: [squash]`) |
| Releases/tags | отсутствуют | нужен release record с полным набором image digests |
| Security | secret scanning и push protection включены | хороший baseline, но этого недостаточно |
| Dependency/security automation | Dependabot security updates/config, dependency review и CodeQL отсутствуют | добавить поэтапно, без блокировки на legacy-noise |
| Локальный frontend audit | `npm audit` сообщает 82 findings: 3 critical, 44 high, 26 moderate, 9 low | сначала triage и supported Angular/toolchain migration, затем blocking gate |
| Project #4 | 2026-08-17: у всех 11 эпиков заполнены `Size`/`Estimate`; даты только у committed-работы (#71 и sub-issues #81–#84); `DraftIssue` нет | labels 9, полей 18, views 6 — не изменились; группировка board и date-поля roadmap через API недоступны и настраиваются в UI |
| Milestones | **создано 2026-08-16:** `Staging v2 — 2026-08-31`, `Billing core — 2026-09-07`, `Bank/legal sandbox — 2026-09-14` | #80 намеренно без milestone: срок зависит от прохождения P0-гейтов |
| Labels | стандартный набор GitHub | оставить без изменений: владелец подтвердил, что новую taxonomy не вводим; вид работы описывается в title/body по [work item standard](../engineering/work-item-standard.md) |
| Wiki | четыре коротких страницы; часть описывает BYOK/self-host v1 | не копировать туда engineering docs; пометить legacy или переписать как русскую user-facing справку |
| Community profile | `CONTRIBUTING.md`, `CODEOWNERS`, Issue/PR templates опубликованы 2026-08-16 (PR #85); Code of Conduct по-прежнему нет | добавить Code of Conduct, если public collaboration остаётся целью |
| Security reporting | `SECURITY.md` отправляет исследователя в public Issue | включить private vulnerability reporting и убрать публичное раскрытие |
| Insights | за 14 дней 0 views, 39 clones/35 uniques | clone spike не считать пользовательским traction |

Главная подтверждённая проблема: `deploy.yaml` использует `cancel-in-progress: true`, а main и AI меняются независимо. Уже был run, отменённый после успешного AI deploy и во время main deploy. Это допускает production из компонентов разных commits.

16 августа проблема подтвердилась второй раз и другим механизмом. Merge PR #85 запустил первый deploy с 3 июня: `apply-main-manifests` и `deploy-main` прошли успешно, `apply-ai-manifests` упал с `dial tcp <ai-cluster>:6443: i/o timeout`, `deploy-ai` пропущен. Production остался в состоянии частичного release — main-кластер на новых образах, AI-кластер на июньских. Функциональной разницы в этом случае нет, потому что PR менял только документацию, но падение одного leg не откатило другой. Evidence: [#70](https://github.com/MattoYuzuru/Mnema/issues/70).

Read-only диагностика 18 августа закрыла вопрос о причине: host firewall блокирует вход GitHub-hosted runner на `6443`, а AI Service ранее был переведён из `NodePort` в `ClusterIP`, поэтому source-owned bridge больше не соответствует runtime. Владелец выбрал вариант A в [#88](https://github.com/MattoYuzuru/Mnema/issues/88): Mnema AI выводится из release path, hosted AI временно скрывается и fail-fast отвечает unavailable; workloads, PostgreSQL, PVC и данные на малом сервере не меняются. Provider migration остаётся в #77/#73.

Ruleset gap тоже уже проявился: PR #59 был merged при красном `backend-quality`, а PR #69 — примерно через восемь секунд после создания, до завершения checks. Это закрыто 16 августа: required checks и запрет прямого push проверены на поведении, а не на ответе API (см. [#82](https://github.com/MattoYuzuru/Mnema/issues/82)).

Дополнительная ошибка GitHub Deployments: `environment: prod` назначен build-matrix, из-за чего одна release может создавать семь deployment records, тогда как job, который реально меняет cluster, environment не объявляет. Environment history и protection должны охватывать именно mutation.

Решение #89 разделяет этот путь на cancellable quality/build jobs в `Main CI` и reusable `Production Deploy`. Deployment call запускается только после успешной сборки всех images, сериализуется отдельной non-cancelling concurrency group и создаёт один `prod` deployment record на cluster mutation. Перед доступом к kubeconfig проверяется, что release SHA всё ещё совпадает с `main`. GitHub rerun исполняет историческую версию workflow, поэтому одного guard недостаточно: cluster authority переносится в environment-only `PROD_KUBECONFIG_B64`, а оба repository kubeconfig, доступные rerunnable историческим workflow (`KUBECONFIG_B64` и `AI_KUBECONFIG_B64`), отзываются.

Это обязательный **pre-merge** stop condition, а не post-merge cleanup: сначала создать `prod/PROD_KUBECONFIG_B64`, убедиться, что старый deploy не выполняется, удалить оба repository secret и read-back проверить `prod/PROD_KUBECONFIG_B64 present` плюс оба legacy name `absent`; только затем merge запускает новую Main CI. После полного отзыва старые main/AI workflow не могут аутентифицироваться ни в одном прежнем Kubernetes-контуре независимо от содержимого их YAML. Возвращать repository kubeconfig при rollback запрещено. Каждый job получает явный минимальный `GITHUB_TOKEN` contract; PR required checks остаются в отдельном `PR Quality` без изменения имён.

## Как использовать GitHub без Jira

### Source of truth

- Repository `/docs` — канонические architecture decisions, API/contracts, runbooks и evidence.
- Project #4 — исполнимый backlog: parent issues, sub-issues, priority, iteration, risk и status.
- Issues — outcome, acceptance criteria, human actions и связанные decisions.
- Pull requests — единственная дорога в `main`; evidence, screenshots, migrations и rollback находятся в PR.
- Milestones — только крупные outcomes: `Staging v2 — 2026-08-31`, `Billing core — 2026-09-07`, `Bank/legal sandbox — 2026-09-14`.
- Wiki — либо русская user-facing справка, либо banner `Legacy v1`; не вторая копия `/docs`.
- Releases/Deployments — immutable build identity, digests, migration note, known risks и rollback target.

Parent/sub-issue model, поля, views и первый backlog находятся в [GitHub execution model](../engineering/github-execution-model.md). Draft items не создаются и в отчёты не попадают.

### Правила для агента

1. Агент берёт только issue со статусом `Ready` и выполненным Definition of Ready.
2. На issue создаётся отдельная branch; один логический outcome — один PR.
3. Агент не пушит красный quality gate и не self-merges security, schema, dependency или production-delivery change.
4. PR связывает issue, перечисляет decision links, verification output, migration/rollback и residual risks.
5. UI PR содержит desktop/mobile evidence, keyboard/a11y и reduced-motion check.
6. Merge выполняет владелец, а не агент. Технического апрув-гейта нет и быть не может: в репозитории один maintainer, GitHub не разрешает апрувить собственный PR, а требование апрува заблокировало бы любой merge. Ruleset обеспечивает обязательный PR и зелёные checks; независимость review для migration, billing, security и production остаётся процедурным правилом, а не гарантией платформы. Review-bot/Copilot может предложить изменения, но не заменяет это решение.
7. Project item становится `Done` после merge и verification, а не после генерации кода.

`AGENTS.md` остаётся короткой operational инструкцией. Длинные архитектурные знания живут в `/docs`, чтобы разные агенты читали один и тот же источник истины.

## GitHub Pro, Copilot и плашка Agents

`GitHub Pro` и `Copilot Pro` — разные подписки. GitHub API не подтвердил текущий account plan, поэтому наличие Pro нужно проверить вручную в `Settings → Billing & licensing`.

- GitHub Pro добавляет 3 000 private-repository Actions minutes, 2 GB Packages и расширенные private-repo controls/insights. Для public repository стандартные hosted runners и так бесплатны в пределах правил GitHub ([GitHub plans](https://docs.github.com/en/get-started/learning-about-github/githubs-plans)).
- Copilot Free ограничен; Copilot Pro — отдельные `$10/month`, включает cloud agent и месячные GitHub AI Credits ([Copilot plans](https://docs.github.com/en/copilot/get-started/plans)).
- Плашка `Agents` — entry point Copilot cloud agent. Он работает в ephemeral Actions environment, делает branch/commits и может открыть PR; расходы учитывают Copilot credits и Actions usage ([cloud agent](https://docs.github.com/en/copilot/concepts/agents/cloud-agent/about-cloud-agent)).

Не удалять Agents только ради чистоты интерфейса. Сначала проверить Copilot plan и использовать его для bounded issues: docs, isolated tests, small cleanup. Persisted schema, auth, payments и deploy policy не делегировать без owner review. Если отдельного Copilot plan нет и панель не нужна, её можно просто не использовать или отключить в Copilot settings; GitHub Pro сам по себе её не оплачивает.

## Целевой CI contract

```text
pull request
  → fast path: formatting/lint + affected unit tests + contract/static checks
  → durable integration: PostgreSQL 18 + MinIO + backend slices + frontend components
  → build once: images by digest + SBOM/provenance + vulnerability scan
  → preview/staging deploy: rendered immutable manifest
  → blackbox: auth + content + media + study + build identity
  → PR approval
  → merge queue/main
  → non-cancellable production deploy
  → smoke/metrics gate
  → complete release record or rollback
```

### Test layers

| Layer | Когда | Что защищает сейчас |
|---|---|---|
| Unit/component | каждый PR | account/auth и новая v2 logic; legacy только при изменении |
| Slice/integration | каждый PR для затронутого backend | persistence, authz, stable error contract |
| PostgreSQL/MinIO protocol E2E | PR/nightly по стоимости | media ACL/dedup, revision/attempt idempotency, migration compatibility |
| Frontend E2E | staging candidate | editor/preview, Browse, Study, mobile/keyboard/a11y |
| Blackbox | после staging/prod deploy | публичный URL, build id, auth, disposable learning flow |
| Security | PR + schedule | secrets, dependencies, CodeQL/SAST, container/image scan, renderer XSS corpus |
| Load/resilience | nightly/release candidate | bounded RPS, queue/backpressure, retry, DB/storage limits |
| Restore | schedule + before destructive cutover | measured RPO/RTO and account-only reconciliation |

Не вводить один магический coverage percentage на весь v1. Для нового/изменённого кода coverage threshold обязателен; отдельно требуются behavior, integration и mutation-prone edge cases. Высокий line coverage не заменяет restore, authorization или idempotency evidence.

### P0 изменения pipeline

1. Разделить cancellable CI и non-cancellable deployment concurrency.
2. Ввести main ruleset: PR required, required checks, no force/delete, linear history по выбранной merge policy; admin bypass только аварийный и аудируемый.
3. Уменьшить `GITHUB_TOKEN` до read default и выдавать `packages:write`, `deployments:write`, OIDC/contents только конкретным jobs.
4. Pin third-party Actions на full commit SHA; GitHub рекомендует SHA как единственную immutable форму ссылки ([secure use](https://docs.github.com/en/actions/reference/security/secure-use)).
5. Frontend собирать через lockfile (`npm ci`) и включить hashed assets; HTML не кешировать immutable.
6. Build images один раз, публиковать digest/release manifest и применять manifest без промежуточного `latest` rollout.
7. Перенести `environment: prod` на mutation job; staging и prod разделить secrets/credentials.
8. Добавить post-deploy blackbox, timeout, diagnostics и автоматизированный rollback к предыдущему полному release.
9. Добавить scheduled backup + isolated restore drill до account cutover.
10. Включить Dependabot, dependency review, CodeQL и image scan в audit-mode; сделать blocking только после triage существующего baseline.

Обещание «ни одной уязвимости вообще» технически непроверяемо. Реальный release policy: ноль известных Critical/High в shipping scope; временное исключение допустимо только как private security issue с owner, компенсирующей мерой и датой истечения. Security scanners дополняют threat modeling, renderer adversarial corpus и ручное review, а не заменяют их.

## Read-only inventory серверов

Доступ по двум предоставленным SSH aliases подтверждён. Команды были только диагностическими; secrets и `.env` не читались.

| Контур | Ресурсы/нагрузка на снимке | Решение |
|---|---|---|
| основной общий сервер | 6 vCPU, 29 GiB RAM; после удаления Minecraft около 22 GiB available, диск 194 GiB/около 113 GiB free; Docker и k3s; несколько других workloads | staging допустим при строгих requests/limits; это shared failure domain, не dedicated capacity |
| малый k3s/AI сервер | 4 vCPU, 3,8 GiB RAM, около 72% Kubernetes memory usage и 31% CPU на снимке; legacy Mnema AI/DB и другие workloads | не размещать новый staging и не использовать в Mnema release path; существующие workloads/data не менять до отдельного v2 cutover |

На основном сервере текущий пользователь имеет Docker-доступ, но kubeconfig k3s доступен только root; `kubectl` context отсутствует. Это блокирует безопасный CD identity, но не требует выдавать агенту root/sudo.

2026-08-15 по прямому разрешению владельца удалён весь Minecraft runtime/data в пользовательском scope: Compose container/network, image и каталоги `/home/matto/minecraft-dark-fantasy`, `/home/matto/minecraft`, `/home/matto/mc`. Освобождено около 13,4 GiB RAM и около 4 GiB disk. Root-owned `/etc/systemd/system/minecraft.service` остаётся выключенным и неактивным; обходить sudo через Docker для его удаления не стали. Общие journals и dangling images других проектов не очищались.

## Что нужно от владельца для staging/CD

Секреты в сообщении не нужны. Нужен следующий минимальный input:

1. Выбрать staging hostname, например `staging.mnema.app`, и направить DNS на основной сервер.
2. Один раз создать namespace `mnema-staging` и namespace-scoped ServiceAccount/RBAC; передать CI отдельный kubeconfig/token без cluster-admin.
3. Решить, где staging хранит PostgreSQL 18 и MinIO: локально в namespace с небольшими PVC для начала или отдельные managed endpoints. Production credentials запрещены.
4. Передать только список uppercase env key names и владельца каждого секрета. Значения загружаются напрямую в GitHub Environment/cluster; в issue/PR они не публикуются.
5. Указать public/internal health URLs, допустимый maintenance window и желаемые RPO/RTO. Начальная рекомендация: staging RPO 24h/RTO 4h, production до beta RPO 1h/RTO 4h — подтвердить после стоимости backup.
6. Выбрать канал deploy/incident notifications.
7. Подтвердить, что основной сервер можно использовать как shared staging target и установить для Mnema resource quotas.

Если будет передан `.env`, безопасный аудит выводит только имена, начинающиеся с uppercase, наличие/пустоту и дубли. Значения, lengths, hashes и derived endpoints не выводятся и не коммитятся.

### Полученный `.env`: keys-only результат

Локальный `.env` найден и проверен без вывода значений. Он подходит под `*.env` в `.gitignore`, Git его не отслеживает. Извлечено 28 uppercase keys; пустых и дублирующихся keys нет.

Большинство production secret names совпадает с references в workflow. Обнаружены две важные границы:

- `CORE_INTERNAL_TOKEN` требуется core/AI configuration и local compose, но отсутствует и в локальном `.env`, и в GitHub secret mapping/deployment manifest. Это подтверждает ранее найденный config drift; token должен иметь одного владельца и явную staging/prod injection.
- `USER_INTERNAL_TOKEN` отсутствует в локальном `.env`; workflow при отсутствии GitHub secret пытается сохранить значение из cluster, а затем генерирует новое во время deploy. Для воспроизводимого release это поведение нужно заменить обязательным environment-owned secret и startup validation.

Остальные workflow-only names включают два kubeconfig, Grafana credentials и автоматически выдаваемый `GITHUB_TOKEN`; они не обязаны находиться в local `.env`. Сам keys-only audit не доказывает, что GitHub values актуальны, имеют правильный scope или успешно доходят до Pods — это проверяется staging smoke и secret contract test.

## Порядок работ 17–31 августа

### 17–20 августа — delivery foundation

- main ruleset/PR templates/CODEOWNERS и permissions;
- reproducible build + immutable release manifest;
- `mnema-staging`, RBAC, secrets contract и DNS/TLS;
- PostgreSQL/MinIO harness, backup/restore skeleton;
- non-cancellable staging deploy, build identity, smoke/rollback.

### 21–27 августа — v2 vertical slice

- versioned AST/editor/preview;
- fresh v2 schema и revision API;
- safe renderer, media contract и Browse;
- каждый slice проходит новый pipeline.

### 28–31 августа — release evidence

- Reveal/self-rating/typed answer;
- XSS, a11y, E2E, load/backpressure baseline;
- account-only export/import rehearsal;
- staging demo и go/no-go на сентябрьский billing core.

Production reset, payment integration и CodeQL-wide legacy cleanup в эту iteration не входят.

## External-change status и следующий gate

Выполнено после owner confirmation:

- созданы parent epics #70–#80 и добавлены в Project #4;
- **#81, 2026-08-16:** documentation/harness foundation опубликован в `main` через PR #85; это первый PR проекта, оформленный по новому стандарту;
- **#82, 2026-08-16:** применён ruleset `main protection` и squash-only merge policy; правила проверены на поведении;
- **#83, 2026-08-17:** созданы три milestone, заполнены `Size`/`Estimate` у всех эпиков, сняты просроченные даты, переписаны и связаны с эпиками #58/#45/#35;
- taxonomy не расширена: labels 9, полей Project 18, views 6 — без изменений.

Следующие внешние пакеты:

1. **GitHub settings, остаток:** least-privilege Actions token, environment protection и security automation — эпики #72 и #70, не #71.
2. **Delivery:** выполнить выбранное в #88 отключение AI release leg, затем разделить cancellable CI и non-cancellable deployment — эпик #70.
3. **Backlog refinement:** создавать Ready sub-issues следующих эпиков по мере снятия зависимостей; без новой taxonomy.
4. **Ручная настройка UI:** группировка board `Backlog` по `Status` и выбор date-полей для `Roadmap` — недоступны через API.

Перед каждым пакетом нужен exact preview: создаваемые/изменяемые объекты, destructive effects, branch, validation и rollback. Сам факт доступа к приватному Project или SSH не является разрешением на mutation.
