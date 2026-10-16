---
artifact:
  id: onboarding-progress-ledger
  type: status-ledger
  title: "Onboarding: progress ledger and handoff index"
  status: current
  created_at: "2026-10-14"
  updated_at: "2026-10-16"
  owners: ["project-owner"]
---

# Ход работ по онбордингу

Единственное место, где сессии отмечают фактический локальный статус задач. Статус задачи на доске — в её Issue
(Mnema Kanban, номера в `manifest.json`); этот файл — что реально сделано и где доказательства.
Каждая сессия **в начале** сверяет таблицу с кодом и handoff предыдущей сессии, **в конце** обновляет
её одним коммитом вместе с handoff. Значения: `not started`, `in progress`, `blocked: <причина>`,
`local complete, delivery pending`, `merged`, `deployed` (два последних — только по факту на GitHub/prod).

## Рабочая база

| Поле | Значение |
|---|---|
| Пакет документов | `docs/engineering/onboarding/` (в main) |
| Ветка реализации | `feat/517-onboarding` (worktree `/Users/yuzuru/Projects/Mnema-wt-onboarding`), draft PR [MattoYuzuru/Mnema#554](https://github.com/MattoYuzuru/Mnema/pull/554); задачи OB-001/002/003/202 на доске — In review |
| База ветки | `origin/main` 42e35d0f (2026-10-15; эпики Сообщества 3–4 в нём) |
| Последний handoff | [S0](../evidence/onboarding/ob-prep/handoff-session-0.md) |

## Статус задач

| ID | Статус | Commit | Evidence / handoff | Заметки |
|---|---|---|---|---|
| OB-001 | local complete, delivery pending | 0801bcb7, 386bc99f | `design/prototype/onboarding/`, [тексты](../evidence/onboarding/ob-prep/texts-for-approval.md), [S0](../evidence/onboarding/ob-prep/handoff-session-0.md) | Утверждено владельцем 2026-10-16; PR #554 |
| OB-002 | in progress | e8969264, 386bc99f | [раздел 1.7](../evidence/onboarding/ob-prep/ob-002-policy-draft.md) | Текст утверждён 2026-10-16; дата вступления — в PR включения флагов |
| OB-003 | blocked: сборка по отдельному поручению владельца | — | [планы и digest](../evidence/onboarding/ob-prep/deck-plans.md); MnemaDeckStudio `runs/base-*` | Планы утверждены 2026-10-16; 10 колод; канон согласован |
| OB-101 | not started | — | — | |
| OB-102 | not started | — | — | |
| OB-103 | not started | — | — | |
| OB-104 | not started | — | — | |
| OB-105 | not started | — | — | |
| OB-201 | not started | — | — | |
| OB-202 | blocked: OB-201 (сборка в S2) | — | [план](../evidence/onboarding/ob-prep/deck-plans.md#ob-202-учебная-колода-как-устроена-память); `runs/onboarding-memory-sample` | План утверждён 2026-10-16 |
| OB-203 | not started | — | — | |
| OB-204 | not started | — | — | |
| OB-301 | not started | — | — | |
| OB-302 | not started | — | — | |
| OB-303 | not started | — | — | |
| OB-304 | not started | — | — | |
| OB-305 | not started | — | — | |
| OB-401 | not started | — | — | |
| OB-402 | not started | — | — | Флаг в production после OB-002 |
| OB-403 | not started | — | — | |

## Журнал сессий

| Дата | Сессия | Агент / модель | База → HEAD | Итог | Handoff |
|---|---|---|---|---|---|
| 2026-10-15/16 | S0 | Claude Opus 5.5 (lead), 2 × Opus 5.5 research, Opus 5.5 review | 42e35d0f → 0efe11d1 (+ handoff) | OB-001/002/003/202 подготовлены; 2026-10-16 утверждены владельцем, корейский перепланирован | [S0](../evidence/onboarding/ob-prep/handoff-session-0.md) |

Handoff-файлы кладутся в `docs/engineering/evidence/onboarding/<epic>/handoff-session-<N>.md` ветки
реализации и перечисляются здесь.
