---
artifact:
  id: lc-e1
  type: epic
  title: "LC-E1: recoverable learning observations"
  status: proposed
  created_at: "2026-10-13"
  owners: ["project-owner"]
---

# LC-E1 — Восстанавливаемая история обучения

## Зачем

После snapshot cleanup scheduled attempts теряют exact exercise provenance;
дополнительная практика не имеет пригодной истории. Это затрудняет миграцию,
выбор вариантов и оценку пользы, хотя старый result по objective сохраняется.

## Результат

Минимальные факты попыток и условий доступны независимо от короткоживущих presentations.
Backfill показывает, что восстановлено точно и чего мы не знаем. Старое расписание сохранено.

## Входит в scope

[LC-101](../tasks/lc-101.md) contract/schema;
[LC-102](../tasks/lc-102.md) terminal capture и server interactions;
[LC-103](../tasks/lc-103.md) bounded backfill/export;
[LC-104](../tasks/lc-104.md) lifecycle/gates/handoff.
Scheduled, practice, replay, restart и compensation различимы без текста ответов.

## Не входит

Новый scheduler, ML classifier, automatic reset, knowledge graph, новый session mode,
изменение срока raw response/snapshots, production migration без отдельного мандата.

## Зависимости и решения

[Proposal](../proposal.md) D01…D08/R01…R02, [migration](../migration.md),
[current model](../current-model.md). Реализовать поверх latest lineage и V59, не b45cc99a.
До production необходим lifecycle удаления новых records и опубликованная прозрачность
новой practice data category; не считать #409 закрытым этим эпиком.

## Критерии завершения

- [ ] Exact new exercise/revision/epoch/mode/result/evaluator provenance переживает snapshot cleanup.
- [ ] Retry/parallel submit не дублирует observation; terminal state/event атомарны.
- [ ] Practice/replay/cancel/unavailable не меняют canonical state/due/streak.
- [ ] Backfill idempotent, unknown fields явны, current head не выдан за историю.
- [ ] TTL/account purge и copy isolation проверены на реальной disposable PostgreSQL.
- [ ] Export воспроизводим и ограничен; полный gate свежий, documented rollback испытан.

## Метрики и evidence

Exact/partial/unknown provenance counts; duplicate counts; bounded batch timings/query plans;
zero baseline state deltas; source/model/schema pins. Не публиковать private identifiers/text.
Synthetic H — план, не capacity claim. Никакого утверждения learning improvement.

## Риски и rollback

Ошибочный join после purge, дубли effects, неверный epoch, увеличение sensitive retention.
Stop при data loss/unbounded query/ненулевом unexpected StudyState delta.
Сохранить additive schema/events и старые outcomes при безопасном отключении capture admission;
не удалять collected data, не откатывать latest shared volumes.

## Контекст

[Work item standard](../../work-item-standard.md), [Study](../../../../contracts/study/README.md),
[agent prompt](../prompts.md#сессия-1-наблюдения), [backlog](../backlog.md).
