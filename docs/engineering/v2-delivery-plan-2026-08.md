---
artifact:
  id: v2-delivery-plan-2026-08
  type: implementation-plan
  title: "Mnema greenfield replacement delivery plan"
  status: proposed
  created_at: "2026-08-15"
  updated_at: "2026-09-06"
  owners: ["project-owner"]
---

# План greenfield replacement

## Назначение текущего шага

Текущий refinement фиксирует уточнения владельца от 6 сентября в канонических
документах и отдельном интерактивном prototype. #73 уже имеет реализованные
foundation slices; #74–#76 получают обновлённые продуктовые границы. Этот шаг
не меняет production, базы, S3, product runtime или deployment и не обновляет GitHub
issues автоматически. Локальный prototype — design evidence, не production UI.

`V2` в названиях старых документов означает поколение продукта, а не API
namespace. Replacement занимает канонические routes; `/v2`, side-by-side runtime,
dual read/write, migration adapters и legacy fallback запрещены.

## Принятая граница

- v1 code и данные не имеют preservation value, кроме исторического tag
  `v1-apache-final`;
- временно сломанные flows, maintenance и downtime допустимы;
- сохраняются только долгоживущие Identity & Account данные и account avatar;
- sessions, grants, authorizations, tokens и всё learning/content/study/import/AI
  состояние удаляются;
- полный legacy snapshot не создаётся и не удерживается; после начала удаления
  rollback к v1 отсутствует;
- `auth` и `user` объединяются в один deployable;
- manual LearningItem/Study MVP предшествует managed AI;
- заменённый code удаляется в том же owning epic, а не прячется за adapter.

## Границы эпиков

| Epic | Outcome | Что намеренно остаётся там |
|---|---|---|
| #73 Greenfield foundation и account-only cutover | новый runtime skeleton, Identity & Account, canonical API/data foundation, delivery topology, account-only transfer и финальный destructive cutover | не реализует editor, exercise catalogue, media platform или AI |
| #74 LearningItem content и frontend reset | личные Deck/LearningItem, экономные revisions, server drafts/quick notes, native editor/renderer и новый Angular UI | выбрана paper/antiquity direction; каталог/fork UI позже; Liquid Glass/v1 components не сохраняются |
| #75 Study и exercise platform | M:N projections/pools, evidence, scheduler, material progress, normal/replay/practice и явный restart | mechanics, learning policies и calibration здесь; AI отсутствует |
| #76 New media/offline boundaries | новая asset/reference/variant модель для image/audio/video и versioned download/sync boundaries | ближайший продукт web-only; native offline позднее; legacy S3 не мигрирует |
| #77 Deferred managed AI | open-answer evaluation, generation, provider eval и quota/cost boundary | AI не блокирует #73–#76 и не обновляет scheduler напрямую |

## Reviewable work items epic #73

Каждая задача рассчитана на отдельный PR или отдельное operational evidence и
обычно на 1–3 ideal agent-days. Persisted schema, API, UI и production cutover не
смешиваются в одном issue.

| Порядок | Issue | Outcome | Size / estimate | Зависит от |
|---:|---|---|---:|---|
| 1 | #139 | Зафиксировать greenfield decisions и supersession map | S / 1.5 | owner decisions |
| 2 | #140 | Создать runtime/PostgreSQL baseline и общий canonical API boundary | M / 3 | #139 |
| 3 | #141 | Создать unified Identity & Account schema и issuer contract | M / 2.5 | #139–#140 |
| 4 | #143 | Перенастроить CI/CD и manifests под replacement topology и maintenance | S / 2 | #140–#141 |
| 5 | #142 | Перенести account/profile/avatar behavior и удалить standalone `user` runtime | M / 3 | #141, #143 |
| 6 | #144 | Реализовать account-only export/import/reconciliation с точным allowlist | M / 3 | #141–#142 |
| 7 | #145 | Автоматизировать manifest-driven no-snapshot purge preflight и disposable rehearsal | M / 3 | #143–#144, #76 deletion targets |
| 8 | #146 | Удалить legacy runtime/build wiring после replacement gates | M / 3 | #74–#76 и #145 |
| 9 | #147 | Выполнить production cutover и необратимое удаление после всех MVP gates | S / 1.5 operational | #139–#146 и минимальные #74–#76 slices |

Owner подтвердил порядок `#143 → #142 → #144` 2026-09-05: старый image/build matrix
требовал исходники `auth/user`, поэтому их удаление в #142 до изменения delivery
делало Main CI неработоспособным. #143 публикует только два существующих runtime
shell в явном maintenance-режиме; product behavior и удаление исходников остаются
в своих задачах. Это не разрешает production promotion maintenance-релиза.

Задача #147 остаётся Backlog до отдельного go/no-go. Её rollback boundary заканчивается
перед первой операцией удаления. Успешный exit code недостаточен: нужны account
reconciliation, отсутствие legacy resources/routes и synthetic smoke нового runtime.

## Межэпиковые контракты

### Identity

Identity & Account владеет credentials, federated bindings, account state, profile
и avatar. Issuer и subject остаются стабильными для нового runtime, но ни отдельный
`user` service, ни proxy compatibility route не сохраняются. OAuth clients, signing
keys and secrets восстанавливаются из configuration; sessions and grants начинаются
заново.

### Content

`Deck` и revisions имеют opaque UUID; логическая identity материала —
`(deck_id, member_key)`, включая будущие fork namespaces. Immutable content
blocks и paged roots переиспользуются; metadata-only save не копирует items.
Snapshot reads не зависят от длины истории. Весь этот domain kernel вместе с rich document nodes,
editor/renderer and visual UX принадлежит #74, а не foundation #73.

### Study

#73 резервирует IDs/idempotency/concurrency conventions, но не переносит текущие
SM2/FSRS/HLR implementations. #75 определяет `MemoryObjective`, M:N exercise
bindings, normalized evidence, canonical scheduler reducer, experiment assignment
и attempt history. Browse, replay и extra practice не изменяют canonical state;
правка ответа сохраняет scheduling, explicit restart сохраняет старую историю.

### Media and AI

Account avatar — часть account allowlist. Learning media создаются заново в #76;
старые media rows/objects/versions удаляются при cutover. #77 подключается позже к
versioned evaluator contract и возвращает auditable evidence; provider verdict не
получает прямой write access к StudyState.

## Sequencing и status policy

1. На проверке 2026-09-06 у #73 закрыты #139–#145 и #157; #146/#147 ожидают
   replacement gates. Identity & Account реализован; Learning API пока platform
   shell, а не готовые content/study domains. Это не свидетельство production cutover.
2. Продолжить #74 после storage/API/editor refinement. Фронтенд можно вести
   параллельно backend по зафиксированным DTO/error/state fixtures; mock не заменяет
   integration acceptance. Не ждать всей реализации #75 ради landing/editor shell.
3. Затем #75; media contract из #76 согласовать заранее, а независимые media slices
   можно делать параллельно после фиксации asset refs/ownership. Не включать native
   приложения или community release в условие завершения ручного web loop.
4. Каждому epic — reviewable 1–3-day slices с закрытыми локальными decisions,
   acceptance и failure tests. Весь epic не переводится в Ready как одна XL task.
5. После достаточных #74–#76 behavior gates завершить #146 и повторить изолированную
   rehearsal #145 с актуальными exact targets.
6. Последняя destructive task — **#147 (порядок 9)**, не #146. Нужен отдельный
   production go/no-go; после первой deletion остаётся лишь roll-forward.

### Suggested refinement slices, not newly created issues

Следующие два spike-задания, contract gate и порядок content/UI slices подробно
описаны в [подготовке #74](./epic-74-refinement.md). Они ещё не выполнены и не
означают, что весь epic готов к параллельному implementation.

- #74: prove block/page storage and cheap fork invariants; define canonical paged
  read/save/draft/capture contracts; implement private authoring; native render/editor
  spike; Angular paper shell and accessible connected flows. Exercise display-spec
  seam согласовать с #75 до persistence, не реализуя все mechanics заранее.
- #75: projection/pool authoring and validation; common attempt/mode envelope;
  scheduled reducer + explicit restart; bounded session selection/replay/practice;
  material aggregate UI and P0 mechanics; then evidence-gated P1 matching/ordering.
- #76: upload/asset/variant lifecycle; authorization and rendering; bounded media
  jobs/GC; manifest/hash/sync contract tests. Native download/offline clients later.

Canonical details: [owner workflows](../product/authoring-and-study-workflows.md),
[storage/runtime](../architecture/revision-storage-and-runtime-boundaries.md),
[frontend handoff](../frontend/design-and-experience-2026-09.md). Still open before
specific implementation: page/node limits and tested storage LLD, editor engine,
exact DTOs, scheduler/aggregate calibration and offline concurrent-event policy.

## Evidence gates

- Identity & Account integration tests подтверждают password/OAuth linking,
  password reset, profile/avatar ownership и отсутствие восстановленных sessions;
- canonical API contract не содержит `/v2` или fallback routes;
- fresh database строится с нуля без выполнения legacy migration chain;
- #74 revision fixtures показывают linear changes/head growth, а не quadratic
  snapshot copies; #73 только предоставляет общие UUID/CAS/idempotency contracts;
- CI/CD публикует только новую topology и не требует работоспособности удалённых
  product flows;
- account-only rehearsal совпадает по allowlisted IDs/fields/checksum и не содержит
  learning data;
- purge dry-run перечисляет DB/PVC/backups/PITR/WAL, Redis, object versions,
  multipart uploads и old deployables without secrets or user content;
- repository and deployed manifests contain no replaced legacy code/path after the
  owning task completes.

## Не делать

- не чинить v1 defects ради сохранения текущего поведения;
- не строить facade вокруг `CardService`, старых algorithms или six-service topology;
- не добавлять `/v2`, feature flag для двух поколений, dual writes или legacy reader;
- не создавать full legacy backup «на всякий случай»;
- не включать AI, final visual design или все exercise mechanics в foundation;
- не переводить unresolved/oversized issue в `Ready`.
