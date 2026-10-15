---
artifact:
  id: onboarding-progress-ledger
  type: status-ledger
  title: "Onboarding: progress ledger and handoff index"
  status: current
  created_at: "2026-10-14"
  updated_at: "2026-10-14"
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
| Ветка реализации | ещё не создана |
| База ветки | свежий `main` (эпики Сообщества 3–4 в нём) |
| Последний handoff | — |

## Статус задач

| ID | Статус | Commit | Evidence / handoff | Заметки |
|---|---|---|---|---|
| OB-001 | not started | — | — | Прототип и тексты |
| OB-002 | not started | — | — | Политика ПДн, юрист |
| OB-003 | not started | — | — | Базовые колоды владельца (MnemaDeckStudio) |
| OB-101 | not started | — | — | |
| OB-102 | not started | — | — | |
| OB-103 | not started | — | — | |
| OB-104 | not started | — | — | |
| OB-105 | not started | — | — | |
| OB-201 | not started | — | — | |
| OB-202 | not started | — | — | Шаблон «Как устроена память» |
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
| — | — | — | — | — | — |

Handoff-файлы кладутся в `docs/engineering/evidence/onboarding/<epic>/handoff-session-<N>.md` ветки
реализации и перечисляются здесь.
