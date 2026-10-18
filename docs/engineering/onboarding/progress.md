---
artifact:
  id: onboarding-progress-ledger
  type: status-ledger
  title: "Onboarding: progress ledger and handoff index"
  status: current
  created_at: "2026-10-14"
  updated_at: "2026-10-18"
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
| Ветка реализации | E1/E2: `feat/517-onboarding` влита в main squash-коммитом 1e4f764e (MattoYuzuru/Mnema#554, 2026-10-17) за выключенными флагами, ветка удалена. E3: `feat/531-onboarding-path` от `origin/main` 5c432a9b (S3) |
| База ветки | E1/E2: `origin/main` 42e35d0f (S0) + b6f14df8 (S1). E3: 5c432a9b (после #557, #497–#499, #555, #559, #560) |
| Последний handoff | [S2](../evidence/onboarding/ob-e2/handoff-session-2.md); S2 запушена и влита в #554 (1e4f764e) — пометка «ветка не запушена» в S2 устарела |

## Статус задач

Коммиты в столбце «Commit» для OB-0xx…OB-2xx — исходные коммиты ветки `feat/517-onboarding`; в main они входят одним
squash-коммитом 1e4f764e (ветка удалена после merge, исходные SHA в истории main не встречаются).

| ID | Статус | Commit | Evidence / handoff | Заметки |
|---|---|---|---|---|
| OB-001 | merged | 0801bcb7, 386bc99f | `design/prototype/onboarding/`, [тексты](../evidence/onboarding/ob-prep/texts-for-approval.md), [S0](../evidence/onboarding/ob-prep/handoff-session-0.md) | Утверждено владельцем 2026-10-16; PR #554 |
| OB-002 | in progress | e8969264, 386bc99f | [раздел 1.7](../evidence/onboarding/ob-prep/ob-002-policy-draft.md) | Текст утверждён 2026-10-16; дата вступления — в PR включения флагов |
| OB-003 | in progress | — | [сборка и проверка](../evidence/onboarding/ob-prep/deck-plans.md#сборка-базовых-колод-2026-10-1617); MnemaDeckStudio `runs/base-*` | 2026-10-17: 8 новых колод собраны, ревью PASSED, опубликованы PRIVATE в production и проверены; вместе с 2 английскими — 10. Осталось AC 2 (local stack и ветка «Найти готовую») |
| OB-101 | merged | 4d824cc5, 9b2b69c5, a0d2bf3b, 70d6b537 | [S1](../evidence/onboarding/ob-e1/handoff-session-1.md), `contracts/onboarding/` | V72; флаги prod off |
| OB-102 | merged | 213d67c6, e034d361, 335e59bf | [S1](../evidence/onboarding/ob-e1/handoff-session-1.md) | Открыты AC: VoiceOver smoke, resize/scroll в браузере |
| OB-103 | merged | e5f30a26, e034d361, 062ed44b, 748043a2 | [S1](../evidence/onboarding/ob-e1/handoff-session-1.md), [harness](../evidence/onboarding/ob-e1/onboarding-results.json) | |
| OB-104 | merged | e5f30a26, e034d361, 335e59bf, 062ed44b | [S1](../evidence/onboarding/ob-e1/handoff-session-1.md), [harness](../evidence/onboarding/ob-e1/onboarding-results.json) | e2e в одноразовом harness, не на retained stack; «Сообщество» до открытия занятия; 2 строки на утверждение |
| OB-105 | merged | 9b2b69c5, 420d43cb, a0d2bf3b, 77294b3a | [S1](../evidence/onboarding/ob-e1/handoff-session-1.md) | Holdout server-only; production после OB-002 |
| OB-201 | merged | 89dcc666, 9fe582df, 482de3df | [S2](../evidence/onboarding/ob-e2/handoff-session-2.md) | V73; purge — #409; L4 (LINK) на решение владельца |
| OB-202 | merged (шаблон только на local stack) | студия вне git; ea723063, c9eb70c5 | [runbook](../evidence/onboarding/ob-e2/sample-template-runbook.md), [S2](../evidence/onboarding/ob-e2/handoff-session-2.md) | Шаблон на local stack (deck d59520c8-…, LINK); production — отдельное поручение |
| OB-203 | merged | 691d1919, 96c15aaf, 729e6fd4 | [S2](../evidence/onboarding/ob-e2/handoff-session-2.md), [снимки](../evidence/onboarding/ob-e2/) | «ИИ-бюджет» не показывает бесплатные проверки |
| OB-204 | merged | 3ecff102 (gate) | [S2](../evidence/onboarding/ob-e2/handoff-session-2.md) | |
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
| 2026-10-16/17 | S0+ (колоды) | Claude Opus 5.5 (lead), Sonnet 5.5 high (авторы), Haiku 5.5 (картинки), Opus 5.5 high (ревью) | MnemaDeckStudio (вне git) | 8 базовых колод PRIVATE в production, TTS $0.30 | [сводка](../evidence/onboarding/ob-prep/deck-plans.md) |
| 2026-10-16/17 | S1 | Claude Opus 5.5 (lead), 2 × Sonnet 5.5 high (backend, frontend), Sonnet 5.5 high (harness), 3 × Opus 5.5 high review | 71ab1c89 + merge b6f14df8 → 748043a2 (+ handoff) | OB-101…OB-105 local complete; полный gate и harness `--onboarding` зелёные | [S1](../evidence/onboarding/ob-e1/handoff-session-1.md) |
| 2026-10-17 | S2 | Claude Opus 5.5 (lead), Sonnet 5.5 high: backend, frontend, импортёр студии, автор pack; Opus 5.5 high: review кода и содержания | 05c1d45c → 3ecff102 (+ handoff) | OB-201…204 local complete; шаблон установлен на local stack; gate зелёный | [S2](../evidence/onboarding/ob-e2/handoff-session-2.md) |

Handoff-файлы кладутся в `docs/engineering/evidence/onboarding/<epic>/handoff-session-<N>.md` ветки
реализации и перечисляются здесь.
