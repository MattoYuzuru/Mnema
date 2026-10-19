---
artifact:
  id: onboarding-package
  type: navigator
  title: "Onboarding: research, RFC, architecture and backlog"
  status: accepted
  created_at: "2026-10-14"
  updated_at: "2026-10-19"
  owners: ["project-owner"]
---

# Онбординг Mnema

Пакет по поручению владельца 2026-10-14: исследование онбордингов, RFC, архитектура,
ADR, четыре эпика с задачами, промпты и решения владельца. Решения приняты владельцем 2026-10-14
(`accepted`); OB-E1 и OB-E2 влиты в main (MattoYuzuru/Mnema#554, 1e4f764e), OB-E3 — (MattoYuzuru/Mnema#561, 1f708643), всё за выключенными флагами; OB-E4 — ветка `feat/537-onboarding-rhythm-reward`; фактический статус в [progress](progress.md). Задачи опубликованы как Issues в Mnema Kanban (Backlog);
номера — в [manifest](manifest.json). Соседний пакет — [learning core](../learning-core/README.md).

## Начать здесь

1. [Исследование и словарь онбордингов](research.md) — какие бывают, что доказано, что нет.
2. [Что уже есть в коде](current-model.md) — вопрос о цели, интересы, копии, тосты, промо, A/B.
3. [RFC](rfc.md) (historical) — варианты, рекомендации и что владелец решил иначе (§8).
4. [Принятое поведение](proposal.md) и [архитектура](architecture.md).
5. ADR: [движок тура](adr/adr-onb-001-tour-engine.md), [вехи «Пути»](adr/adr-onb-002-milestones.md),
   [учебная колода](adr/adr-onb-003-sample-deck.md), [ритм](adr/adr-onb-004-rhythm.md),
   [награда](adr/adr-onb-005-path-reward.md).
6. [Решения владельца](questions.md), [бэклог и оценки](backlog.md), [manifest](manifest.json),
   [ход работ](progress.md), [промпты сессий и протокол handoff](prompts.md).

## Эпики

| ID | Результат | Задачи |
|---|---|---|
| OB-E1 | «Начало пути»: приветствие, опрос, короткий spotlight-тур с ветками, пауза, перепрохождение | [эпик](epics/ob-e1-first-entry-and-tour.md), OB-101…105 |
| OB-E2 | «Моя первая колода» — личная нерасшариваемая копия шаблона редакции | [эпик](epics/ob-e2-sample-deck.md), OB-201…204 |
| OB-E3 | «Путь Mnema» — чек-лист освоения по реальным действиям с переходами и тостами | [эпик](epics/ob-e3-path.md), OB-301…305 |
| OB-E4 | Недельный ритм вместо стрика; награда за «Путь» на 7 дней (флаг в prod после проверки условий) | [эпик](epics/ob-e4-rhythm-and-reward.md), OB-401…403 |

Подготовка: OB-001 (прототип и тексты), OB-002 (политика ПДн), OB-003 (базовые колоды владельца).

## Что считается решением

- Решения владельца 2026-10-14 ([questions](questions.md)) приняты; [RFC](rfc.md) — historical.
  Действующие контракты Study/community/usage/notifications и стайлгайд меняются вместе с реализацией
  соответствующего эпика, до этого они описывают текущее поведение.
- Поручение «выполни сессию Sx» по [prompts](prompts.md) разрешает реализацию и коммиты в
  её scope; delivery и production — только по root `AGENTS.md` и отдельному поручению.
- Карта кода составлена по коду эпиков Сообщества 3–4 до их слияния; на старте реализации
  перепроверить пути и последнюю миграцию в свежем main.
