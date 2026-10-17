---
artifact:
  id: learning-core-local-backlog
  type: refinement
  title: "Two learning-core epics and gated follow-ups"
  status: proposed
  created_at: "2026-10-13"
  updated_at: "2026-10-13"
  owners: ["project-owner"]
---

# Локальный бэклог

Формат соответствует [work item standard](../work-item-standard.md). Local IDs стабильны,
GitHub numbers ещё отсутствуют. Все задачи `Backlog`: пакет готов к review и будущему
поручению, но запись документа не означает accepted behavior, Ready или implemented.

## Обязательные первые эпики

| ID | Название / тело задачи | Зависит от | Ориентир активного времени агента |
|---|---|---|---|
| LC-E1 | [Восстанавливаемые наблюдения обучения](epics/observations.md) | актуальный source + принятие proposal | 8–14 ч |
| LC-101 | [Contract/schema минимальной истории](tasks/lc-101.md) | — | 1–2 ч |
| LC-102 | [Атомарная запись попыток и условий](tasks/lc-102.md) | LC-101 | 3–4 ч |
| LC-103 | [Backfill/export с полнотой и watermark](tasks/lc-103.md) | LC-102 | 2–3 ч |
| LC-104 | [Retention/migration gates и handoff](tasks/lc-104.md) | LC-101…103 | 2–5 ч |
| LC-E2 | [Репрезентативная практика и честное покрытие](epics/representative-practice.md) | LC-E1 | 8–14 ч |
| LC-201 | [Bounded ротация вариантов одной цели](tasks/lc-201.md) | LC-102/103 | 3–4 ч |
| LC-202 | [Покрытие целей и diagnostic variants](tasks/lc-202.md) | LC-201 | 2–3 ч |
| LC-203 | [Offline сравнение и delayed-check protocol](tasks/lc-203.md) | LC-103/201 | 1–2 ч |
| LC-204 | [Интегрированная проверка и доставка](tasks/lc-204.md) | LC-201…203 | 2–5 ч |

## Объём и десятичасовые сессии

Две примерно десятичасовые автономные сессии — реалистичный план первых двух полезных
результатов, но не гарантия: диапазон 16–28 часов учитывает tests, docs и одну независимую
проверку каждого эпика. Это planning estimate по знакомой архитектуре, не измеренная
скорость выполнения. Gates/environment repairs, review findings и schema conflicts
могут потребовать следующего продолжения. Не урезать acceptance ради цифры 10.

В первой сессии приоритет LC-101/102/103, во второй LC-201/202. LC-104/204 — обязательная
часть done, а не опциональный хвост. Если сессия закончилась, handoff содержит фактический
HEAD, выполненные AC, незакрытые проверки и следующий конкретный шаг.
Новый diagnostic session mode, полный сбор client reveal и новая модель памяти сюда не добавляются.

Полный дальнейший core research/algorithm program крупнее: follow-ups имеют собственные
timeboxes и могут закончиться отказом от нового алгоритма. Human corpus и 7/30-дневный
pilot требуют календарного времени. «Почти идеальный core» не является критерием завершения.

## Последующие решения, не скрытый scope первых эпиков

| ID | Decision spike | Timebox / результат | Gate |
|---|---|---|---|
| LC-301 | [Надёжность rubric и дешёвый classifier](tasks/lc-301.md) | 3–6 ч automated preparation; затем human review | human-labelled held-out corpus; privacy для реальных ответов |
| LC-302 | [Elapsed-aware memory model / FSRS candidate](tasks/lc-302.md) | 4–8 ч offline comparison/ADR | пригодные observations; нет автоматического production switch |
| LC-303 | [Содержательные правки и scope целей](tasks/lc-303.md) | 2–4 ч сценарии/решение/next issue | сохранение owner decision об отсутствии automatic reset |
| LC-304 | [Ошибки, relearning, encoding aids](tasks/lc-304.md) | 2–4 ч продуктовый эксперимент/next issue | не менять unique objective/session без новой спецификации |

## Definition of Ready и evidence

Перед стартом child issue: актуальный owning checkout, принятый scope D01…D08/R01…R05,
нет незавершённого конфликта с live actor, известны affected contracts/tests и миграции,
rollback boundary и bounded H fixture. Вопросы следующего spike не блокируют LC-E1/E2.
Новые зависимости допускаются только по root AGENTS с сравнением альтернатив и permission.

LC-E1 не доказывает learning lift. LC-E2 не доказывает transfer. LC-301 не подтверждён
одним LLM judge, LC-302 не подтверждён одной симуляцией. Done инженерной задачи означает
applied/merged поведение и proportional verification; готовая, но не влитая ветка — это
`In review`, не Done.

Номера Issues — в [manifest](manifest.json).
Два эпика независимы от community ranking, но требуют latest lineage/retention contracts;
не смешивать их с Community #421/#422 и не подменять ими существующие эпики.
