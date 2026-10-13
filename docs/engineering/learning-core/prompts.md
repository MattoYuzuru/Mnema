---
artifact:
  id: learning-core-autonomous-prompts
  type: execution-prompts
  title: "Learning core autonomous session prompts"
  status: proposed
  created_at: "2026-10-13"
  owners: ["project-owner"]
---

# Промпты будущему агенту

Промпты самодостаточны по entrypoint и scope; linked task bodies содержат все AC.
Ориентир десять часов не разрешает пропуск gates и не ограничивает необходимое продолжение.
Максимум два саб-агента одновременно, независимое review полезно, integrator владеет shared files.
Не создавать sidebar chats вместо subtasks. Точные source/branch/runtime paths проверять заново.

## Сессия 1: наблюдения

```text
Реализуй LC-E1 «Восстанавливаемая история обучения» по docs/engineering/learning-core/README.md,
epics/observations.md и tasks/lc-101.md…lc-104.md. Принимаю рекомендуемый ограниченный scope
proposal.md для этого эпика; новый scheduler/classifier/production rollout сюда не входят.
Работай автономно до результата и необходимых checks, максимум с двумя саб-агентами одновременно.

Начни с AGENTS.md, backend/AGENTS.md, docs/README.md, work-item-standard.md и
docs/reviews/local-workspace-2026-10-13.md. Не удаляй локальные worktrees/assets и не сбрасывай их.
Корневой checkout на момент плана b45cc99a был старой docs-веткой; local/epic4 f4f5e636 включал
local/epic3 f033d533 и cached origin/main ae556ece. Эти pins исторические: проверь actual refs.
Если GitHub недоступен, используй latest coherent local integration с lineage и V59, создай
изолированную рабочую ветку от проверенной базы. Если доступ есть, read свежий main и pending
эпики; не теряй их изменения, не публикуй повторные diff после squash. Не меняй main напрямую.

Сохрани компактную exact history terminal attempts/условий до snapshot purge, отличай observation
от canonical evidence. PRACTICE/REPLAY не получают due/streak/StudyState credit. Backfill bounded,
unknown history explicit, current head не заменяет выданную revision; restart/compensation/epoch
и receipt-first должны сохраниться. Не сохраняй text/quotes/transcripts в новом ledger.
Default retention и production lifecycle gate см. proposal.md; не объявляй #409 завершённым.

Новые migrations только после сверки inventory. Не добавляй dependencies без правила root AGENTS.
Для shared checks прочитай /Users/yuzuru/AGENTS.md, infrastructure navigator и workstation-dev.
Runtime owner один; retained mnema-local-v2 не перезапускай как тестовый fixture. Общие locks:
~/.local/state/agent-dev, resource heavy; тесты disposable PostgreSQL на текущем Mac, JDK25/Node24.
Перед делегированием передай resolved instruction/skill paths, checkout/HEAD, tool versions,
Docker endpoint, lock resource, report scope и ownership, не секреты.

Реализация и локальная проверка/commit входят в поручение. Пока отдельного поручения о delivery
нет, не push/создавай PR/меняй доску/merge/prod approval. GitHub outage не мешает локальному outcome.
Проведи независимый review, исправь findings, выполни full gate exact source и миграционную
репетицию, сохрани source-bound receipts. Не называй написанный код доказательством memory gain.
Заверши self-contained handoff: HEAD/base/diff, AC, gate results, migration/rollback, непроверенное,
local complete или delivery pending, следующий конкретный шаг. Если новая сессия нужна,
продолжение должно быть возможно без истории этого чата.
```

## Сессия 2: проверки и покрытие

```text
Реализуй LC-E2 «Разные проверки одной цели и честное покрытие» по
docs/engineering/learning-core/README.md, epics/representative-practice.md и tasks/lc-201.md…lc-204.md.
Принимаю рекомендованный scope D01…D08/R03…R05. Работай автономно, максимум два саб-агента.

Сначала проверь latest source и доказательство LC-E1: compact observation capture/backfill/TTL
готовы, migration и локальные gates подтверждены. Если LC-E1 лишь написан в документах,
сначала выполни его необходимую зависимость, не выдумывай завершение. Прочитай AGENTS/scoped,
work-item-standard, local-workspace audit, current-model, proposal/research/migration.
Не удаляй worktrees/локальные результаты, не теряй community эпики 3/4.

Сделай bounded versioned rotation eligible вариантов одной explicit цели: объективный cursor
по inventory, no starving prefix, exact resume/retry и REPLAY source order. Сохрани budgets,
one-objective/session, pinned snapshots, ACL/media/capability checks. Baseline reducer не меняй;
число вариантов/механик не повышает evidence автоматически.

Progress denominator — distinct enabled objectives. Добавление парафраза не меняет знание,
новая цель unknown. Покажи понятные границы покрытия, variant history как отдельную диагностику
с horizon/completeness. Не вводи процент mastery по числу упражнений и не заставляй проходить все.
Новые shared UI элементы сначала в styleguide, paper/indigo, semantic/a11y/mobile/reduced motion.

Подготовь reproducible offline selection report и delayed-check protocol, без нового session mode,
автоматической live randomization и causal lift claim. Classifier/FSRS/automatic edit reset/relearning
— follow-ups LC-301…304, не скрытый scope. Privacy и data lifecycle gates сохраняются.

Один runtime owner, workstation-dev и shared heavy lock на текущем Mac. Не трогай retained volumes.
Обязательны real-DB regression, critical components, actual browser desktop/mobile/keyboard и full gate;
не называй jsdom реальным screen-reader тестом. Independent review исправляется до final gate.
В этом поручении разрешены local implementation/checks/commit; remote delivery и production отдельно.
Заверши handoff с actual source pins, AC/evidence/limitations/rollback и next issue.
```

## Публикация локального бэклога

```text
Доступ к GitHub восстановлен. Опубликуй локальный backlog улучшения core из
docs/engineering/learning-core/manifest.json по github-import.md и work-item-standard.md.
Сначала проверь repository/project/status fields и существующие задачи, не создавай дубликаты.
Создай/свяжи два parent epics и child issues, сохрани mapping local ID → URL/number и dependencies.
Оставь Backlog всё, что не прошло Definition of Ready; исследования не выдавай за реализацию.
Не начинай implementation, merge или production в рамках публикации бэклога.
```

«Доступ вернулся» без просьбы публиковать означает возможность read, не standing permission
на любые внешние mutations. Иной явно названный delivery mandate применяется по root AGENTS.
