---
artifact:
  id: learning-core-migration-proposal
  type: migration-plan
  title: "Preserve learner history while evolving Study"
  status: proposed
  created_at: "2026-10-13"
  updated_at: "2026-10-13"
  owners: ["project-owner"]
---

# Миграция без сброса истории

В первые два эпика reducer не заменяется: добавляются observations и selection policy.
Следующая модель памяти — отдельный LC-302. Сто пользователей не является пределом
миграции; нагрузка определяется количеством events/objectives/variants и индексами.
Production/retained data не изменялись в этом исследовании.

## Что защищаем

Пользовательские материалы, копии, private edits, independent progress; существующие
outcomes/idempotency; learningEpoch/restart/dispute; nearest due/streak; privacy retention.
Исходные old transitions не пересчитываются и не переписываются как будто всегда
получались новым алгоритмом. Только новая projection/init state с provenance.
Исторический account-only v1 cutover из content architecture сюда не относится.

## Этап LC-E1

1. Закрепить actual HEAD и Flyway inventory fresh main + pending epics; выбрать следующий
   свободный номер (на 2026-10-19 последняя — V76), не заранее. Прочитать V59 и последующие миграции и account lifecycle.
2. Добавить compact observation schema с source/quality/version и индексированными
   account/objective/time lookups, atomic writer и bounded maintenance. Existing API path
   заменяется непосредственно; временной dual product path нет.
3. На synthetic fixture снять watermark по sequence/event order, выполнить bounded
   идемпотентный backfill и reconciliation; live backfill — только в named environment scope.
4. Scheduled old evidence join к сохранённой presentation даёт exact exercise/revision;
   без неё — legacy record с unknown exercise/mechanic. Current head, raw response и
   cumulative counter не используются для выдумывания исторического exercise.
5. Practice/replay восстанавливать только из доступного terminal receipt+presentation,
   с доказуемым mode/time; отсутствующий event не создавать. Старое «практики не было»
   нельзя утверждать. Сохранить historic evaluator/result без новой AI-оценки.
6. Compensations и restarts — явные последовательные facts. Replay projection исключает
   compensated credit как валидную оценку; попытка/feedback остаются воздействиями обучения,
   disputed не превращается в incorrect. Audit record сохраняется. Unknown assistance не равно false.
7. Проверить duplicate runs, concurrent terminal writes/purge, partial batch retry,
   latest epoch isolation, event totals и exact provenance share. Backfill маркирует
   source=LEGACY_BACKFILL и не создаёт due/streak/exposure effects.
8. Закрыть gate: свежие tests, bounded query plans, privacy/TTL/purge, counts and hashes,
   interruption/resume и восстановление старого runtime на новой схеме проверены.

Пакет может использовать отдельную staging projection и dry-run для расчётов. Это
аналитические артефакты, не вечные compatibility readers. Backfill не требует текста ответов.

## LC-E2 и pending sessions

Session фиксирует selection version и выдачу. Новая версия применяется к новым сессиям;
активная сессия завершает pinned policy. Если sharing/update invalidates eligibility,
действуют нынешние правила. Нельзя откатывать policy и при этом выбрасывать уже accepted outcomes.
До переключения capture observations должен работать; потом выбор вариантов использует
доступную историю и explicit horizon. Массового пересчёта due здесь нет.

## Будущий LC-302: новая memory model

Сначала offline кандидат с immutable model/config и declared inputs. Time split,
holdout by learner/objective, assistance/variant/mode stratification; weak observations
не отображаются слепо в Anki Good/Easy. Low-data users получают conservative population
initialization и uncertainty; старые пропуски не «лечатся» fake data.

Перед cutover подготовить dry-run init projection с input watermark и reconciliation.
Неизменённые historical due сохранить как входной факт; новые due сдвиги показать
распределением и projected workload, в том числе backlog tail. Предельные сдвиги и
daily workload — продуктовые параметры следующего scope, здесь они не утверждены.
Не назначать всем немедленный reset/перепроверку.

Последний replay учитывает accepted writes после watermark и terminal order, а не
client timestamps. Активные assessment/provider jobs и presentations требуют fence:
либо закончить в коротком writer pause, либо оставить старую pinned policy до окончания
с явным reconciliation; выбрать один путь в LC-302 ADR. Не делать обе модели
одновременно владельцами canonical StudyState.

## Stop и rollback

Stop: потеря existing receipts/history, необъяснимые counts, другая attribution копии,
TTL удаляет canonical evidence, unbounded query, stale source watermark, gate failure,
резкий непредусмотренный workload. Установленная snapshot retention остаётся включённой.

Для первых эпиков rollback отключает новый selector/writer admission безопасным способом,
сохраняет already captured events и baseline state; constraint/migration additive rollback
должен быть испытан на disposable fixture. Не удалять таблицы данных в качестве rollback.
Для будущей модели сохранить before-cutover state и replay accepted post-cutover events
при возвращении baseline; один старый backup потеряет новые ответы и не является готовым rollback.

Любой restore из backup проверяется отдельно; Git commit не защищает данные PostgreSQL.
Репетиция uses disposable DB, не retained `mnema-local-v2`. Production deployment только
по [delivery](../../operations/production-delivery.md) в явно названном мандате.

## Evidence checklist

- Source/model/config pins, input range/watermark, event counts и missing-provenance counts.
- Проверка идемпотентности и interrupted resume, CAS/epoch/compensation cases.
- Query plans на 0 и H: synthetic 100 learners×10k attempts, 1000 variants/objective;
  это план теста, не измеренная capacity.
- Zero differences baseline due/streak после observation-only backfill.
- Snapshot/raw/diagnostic expiry и account purge; секреты и private response вне отчёта.
- Approved rollout scope, свежие gates, восстановление, фактические ограничения.
