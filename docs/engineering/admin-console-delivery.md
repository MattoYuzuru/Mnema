---
artifact:
  id: admin-console-delivery
  type: delivery-record
  title: "Owner console work and verification"
  status: current
  created_at: "2026-10-09"
  updated_at: "2026-10-09"
  owners: ["project-owner"]
---

# Owner console delivery

[Epic #398](https://github.com/MattoYuzuru/Mnema/issues/398) и native sub-issues
добавлены в [Project #4](https://github.com/users/MattoYuzuru/projects/4).
Implementation base — `cae7c0e11c29d63d98b2ff3a838df7d21c0f613c`, отдельная
ветка `codex/admin-console`. User-owned infinite-scroll changes и retained
local stacks не входят в изменение. Live статусы задач читаются из GitHub.

| Task | Outcome |
|---|---|
| [#399](https://github.com/MattoYuzuru/Mnema/issues/399) | Exact owner access, Identity directory, host/OAuth и moderation audit |
| [#400](https://github.com/MattoYuzuru/Mnema/issues/400) | Persisted reporting, denominators, percentiles и точность источников |
| [#401](https://github.com/MattoYuzuru/Mnema/issues/401) | Shell/users list/detail и usage mini report |
| [#402](https://github.com/MattoYuzuru/Mnema/issues/402) | Promo idempotent API и полный UI |
| [#403](https://github.com/MattoYuzuru/Mnema/issues/403) | News editor и публичный preview |
| [#404](https://github.com/MattoYuzuru/Mnema/issues/404) | Bot private bridge/receipt/CAS/notes/outbox |
| [#405](https://github.com/MattoYuzuru/Mnema/issues/405) | Ticket queue/filter/thread/reply UX |
| [#406](https://github.com/MattoYuzuru/Mnema/issues/406) | Source-labelled finance/usage dashboards |
| [#407](https://github.com/MattoYuzuru/Mnema/issues/407) | Independent review, full gate и интегрированная проверка |
| [#409](https://github.com/MattoYuzuru/Mnema/issues/409) | Backlog: owner retention/deletion решение и отдельный lifecycle workstream до production приёмки |

## Реализация и локальная проверка — 2026-10-09

Реализованы owner shell/host OAuth, Identity directory и moderation, текущий
access/source/expiry и исторические allowances, usage/reporting/percentiles,
полный promo UI и exact-command create, news preview, support queue/thread/notes/
reply и два видимых журнала. Общие элементы сначала добавлены в styleguide.
Mobile navigation использует компактное раскрытие; learner notifications и
их объявления приостановлены в кабинете. Privileged role checks не используют
положительный standing cache.

| Проверка | Результат |
|---|---|
| JDK25/Docker `./gradlew clean quality` + два `bootJar` | 2182 tests, 0 failures/errors, 25 explicit opt-in skips; JaCoCo floors пройдены |
| Node24 `npm ci`, lint, test, production build | 2174 tests / 178 files, lint и coverage thresholds пройдены |
| `verify-no-styleguide.mjs` | Dev catalogue/route/chunk отсутствуют в 180 production files |
| `verify_docs.py` | 201 Markdown files, links/statuses валидны |
| `scripts/tests` и browser fixture safety | 396 + 39 tests passed |
| Companion bot | Python3.14.7, 53 tests passed, включая competing SQLite CAS/duplicate и note isolation |

Full gate receipt: `check-4gz41pvr`, source base
`cae7c0e11c29d63d98b2ff3a838df7d21c0f613c`, dirty-source fingerprint
`1aa15dc59d26909b53c8461631334f304b2b70525cf45e5ca61f2e8efbda1295`;
source remained unchanged during the gate. Этот текст evidence добавлен после
проверки; production code после проверенной ревизии не менялся.

25 skips — предусмотренные live provider/eval, speech/STT, real S3 parity и
worker/media opt-in scenarios. Реальные оплачиваемые provider calls, S3 bucket
operations и live Telegram delivery не выполнялись. Console DB/filter/receipt/
math и bridge/parser suites на эти пропуски не опираются.

Независимые architecture/frontend/SRE reviews проверили ownership, актуальные
права, units/accuracy, immutable receipts и privacy. Исправлены stale grant cache,
stale reserved OAuth client, late private-response resurrection, late dismissal
failure, native ban-form submit и nullable attachment size. Review не является
формальным penetration test или assistive-technology сертификатом.

## Браузерная граница

Реальный Chrome на disposable PostgreSQL/боте прошёл базовую Identity/authoring
проверку, owner login, report/users/current mini info, create/disable promo и
news draft→preview→publish. Desktop/768/390/320 overview snapshots осмотрены.
Первый run остановился на старом конечном request budget; budget расширен только
для нового `--admin` scope. Следующий дошёл до support filter: сценарий ошибочно
сочетал default `open` с `uncertain` waiting-ticket; fixture проверен, сценарий
исправлен. Эти runs не объявлены полностью зелёным integrated browser evidence.

Владелец 2026-10-09 явно остановил дальнейший browser/screenshots этап и попросил
завершить после quality gate. Последующие mobile-nav/notification/attachment
исправления покрыты автоматическими проверками, а законченный browser support
flow, native200% zoom, real AT/touch/Safari/Firefox и production smoke здесь не
заявляются. Harness остаётся исполнимым для следующего отдельного запуска.

## Что осталось за границей текущей поставки

Billing исключён владельцем: #389 владеет V45; перед интеграцией/поставкой V46
нужен согласованный актуальный migration history. Revenue/provider/cloud invoice
sources не подключены; estimate, credits и inventory не заменяют cash evidence.

Source находится локально в `codex/admin-console`; companion bot — локальная
`codex/admin-support`, base `3445d90067199ad494a8a9b7a65dd7f2af504de5`.
Ни source publication/merge, ни production/DNS/SSH, ни настоящий reply пользователю
не выполнялись. Фактический admin-host/private TLS transport, owner UUID/admin
bootstrap и bridge credentials требуют отдельного production поручения.

[Backlog #409](https://github.com/MattoYuzuru/Mnema/issues/409) владеет решениями
retention/account deletion для audit/support и связанным lifecycle. Это не
разрешение на destructive purge. Задачи эпика остаются open до merged/applied
outcome по [work item standard](./work-item-standard.md).
