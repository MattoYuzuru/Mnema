---
artifact:
  id: lc-e2
  type: epic
  title: "LC-E2: representative practice and honest coverage"
  status: proposed
  created_at: "2026-10-13"
  owners: ["project-owner"]
---

# LC-E2 — Разные проверки одной цели и честное покрытие

## Зачем

Due objective сейчас выбирает первый eligible вариант. Это может закреплять конкретную
формулировку и оставлять другие задания неиспользованными. Количество вариантов не должно
управлять «процентом знания»; важны explicit goals и условия измерения.

## Результат

Bounded deterministic selection меняет eligible варианты одной цели, сохраняет resume/replay
и бюджеты. Material view понятно показывает scope/coverage целей; variety остаётся диагностикой.
Есть reproducible report и delayed-check protocol, без обещания научно доказанного lift.
Название «репрезентативная практика» обозначает цель: rotation inventory сама по себе
не подтверждает, что варианты содержательно покрывают заявленный навык.

## Входит в scope

[LC-201](../tasks/lc-201.md) rotation;
[LC-202](../tasks/lc-202.md) coverage/UI;
[LC-203](../tasks/lc-203.md) offline report/protocol;
[LC-204](../tasks/lc-204.md) integrated checks/evidence.

## Не входит

FSRS production switch, mastery percentage, all-exercises completion requirement, new diagnostic
session mode, semantic difficulty inference, automatic objective split, in-session relearning.

## Зависимости и решения

LC-E1 observations/backfill, [proposal](../proposal.md) R03…R05,
[science](../research.md) H08…H12. Selector advances explicit objective-local cursor across
eligible pinned inventory; historical uncertainty не превращается в «никогда не видел».
Ротация не повышает evidence class и не меняет reducer. Content-based novelty не определяется автоматически.

## Критерии завершения

- [ ] Due/new budgets, eligibility и одна цель/session сохраняются.
- [ ] В fixture с несколькими eligible вариантами следующие новые сессии не застревают на первом.
- [ ] Resume/retry/replay сохраняют выданное content/order; concurrent changes обработаны.
- [ ] Duplicate variants не изменяют objective coverage; unknown цели не помечены известными/неправильными.
- [ ] UI keyboard/reflow/reduced-motion checks зелёные; нет процента знания по inventory.
- [ ] Offline report/protocol различают engineering validity, prediction и causal learning result.
- [ ] Свежий full gate, independent review и self-contained handoff; delivery status честный.

## Метрики и evidence

Selector starvation/repetition/eligibility, bound on rows/history reads; diagnostic history completeness;
business-state matrix и browser snapshots desktop/mobile. Научный pilot позже: delayed unassisted
performance/time и alternate-form application отдельно, с learner-level uncertainty.

## Риски и rollback

Новый вариант может быть хуже/сложнее, не все reuse objectives эквивалентны, неизвестна old history.
Rollback возвращает selection policy для новых sessions; старые pinned presentations и ledger сохраняются.
Если bounded diverse choice невозможен, выбрать eligible fallback и reason, не делать full scan.

## Контекст

[Styleguide](../../../frontend/styleguide.md), [Study](../../../../contracts/study/README.md),
[agent prompt](../prompts.md#сессия-2-проверки-и-покрытие), [backlog](../backlog.md).
