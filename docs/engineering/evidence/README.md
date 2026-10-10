---
artifact:
  id: engineering-evidence-index
  type: evidence-index
  title: "Engineering evidence index"
  status: current
  updated_at: "2026-10-07"
  owners: ["project-owner"]
---

# Engineering evidence index

Evidence is historical proof for a specific revision and environment. It does not
override current code, accepted contracts or the
[current delivery boundary](../../operations/production-delivery.md).
Records written before #266 use the removed exercise names (`TYPED`, `LISTEN_TYPE`,
`CLOZE_SINGLE`, `SINGLE_CHOICE`, `LISTEN_CHOICE`, `AUDIO_TEXT_MATCH`) and the older
wire shapes; the current mechanics and fields are in
[`contracts/study`](../../../contracts/study/README.md#exercise-mechanics-266). Screenshots
of the former two-column exercise editor are likewise historical.

## Owner console

- [Owner console delivery, 2026-10-09](./admin-console-2026-10/README.md) records the first implementation snapshot of epic #398, its local gate and browser boundary.

## Public content and feedback

- [Public footer and events acceptance, 2026-10-07](./public-feedback-2026-10-07/README.md)
  records current-task local Chrome/HTTPS evidence, responsive/keyboard checks and exact code revision.

## Epic #74 closure

- [Integrated main acceptance](./epic-74/verification/integrated-main-2026-09-19.md)
  is the shortest authoritative closure record.
- [Verification index](./epic-74/verification/README.md) separates final acceptance
  from the retained pre-implementation strategy.
- [Acceptance matrix](./epic-74/verification/acceptance-matrix.md) maps adversarial
  cases; [quality gates](./epic-74/verification/quality-gates.md) records exact runs.

## Epic #75 closure

- [Integrated Study acceptance](./epic-75/verification/integrated-main-2026-09-24.md)
  maps every P0 criterion to executable evidence, records local full-stack and
  browser results, and names the remaining device/cohort/production limits.

## Epic #76 closure

- [Integrated browser and Study run](./epic-76/integrated-browser/README.md),
  [five-minute phone-derived worker run](./epic-76/phone-worker.md) and
  [component visual checks](./epic-76/media-visual/README.md) are the local acceptance
  evidence for media; they cover three listening types that #266 later replaced.

## Epic #77

- [Прогон 1: платформа и контракты](./epic-77/run-1/README.md) — актуализация стека (#278)
  и контракты AI-слоя (#279) с точными commit'ами и границами проверки.
- [Прогон 2: фундамент без AI](./epic-77/run-2/README.md) — usage ledger, провайдеры, MBM-компилятор,
  `code_block`, центр уведомлений, хаб колоды и UI-примитивы; ревью, измерения и границы проверки.
- [Прогон 3](./epic-77/run-3/README.md) — composer, Мастерская, approval и заметки как источники.
- [Прогон 4](./epic-77/run-4/README.md) — упражнения, проверка объяснений, правки и planner.
- [AI-17 evaluation](./epic-77/run-5/ai-17/README.md) — frozen corpus, кандидаты и границы качества;
  отдельно отмечены pending owner labels, это не автоматическая приёмка всего эпика.

## Storage и content

- [Storage research index](./epic-74/storage/README.md) routes to measurements,
  raw summaries and reproducible experiments.
- [Storage kernel](./epic-74/storage-kernel.md),
  [native storage](./epic-74/native-storage.md) and
  [counted pages](./epic-74/counted-pages.md) are implementation evidence.
- [Content boundary review](./epic-74/verification/content-boundary-review.md) and
  [LearningItem API](./epic-74/learning-item-api.md) cover the #75-facing contracts.

## Frontend и browser

- [Декорации, удаление и аудиопары](./polish-followup-2026-09-30/README.md)
  содержит последующую настройку и desktop/mobile evidence.

- [Бумажный интерфейс и упражнения](./paper-polish-2026-09-30/README.md)
  содержит результаты 12 правок, desktop/mobile screenshots и границы проверки.

- [Frontend brand restoration](./frontend-brand-2026-09-28/README.md) —
  снимки production Angular на desktop/mobile и результат реального HTTPS-сценария.

- [Angular migration](./epic-74/angular-migration.md),
  [editor evidence](./epic-74/editor/README.md) and
  [native renderer](./epic-74/native-renderer/implementation.md) retain decisions.
- [Browser Identity](./epic-74/browser-identity.md),
  [own Decks UI](./epic-74/own-decks-ui.md) and
  [authoring browser](./epic-74/authoring-browser/README.md) contain local browser
  results and screenshots. They do not claim unrun screen-reader/device sessions.

## Security и boundaries

- [Learning security composition](./epic-74/learning-auth-blackbox.md) and
  [identity/boundaries](./epic-74/verification/identity-and-boundaries.md) retain
  authentication, cancellation and direct-ID evidence;
  [independent authentication review](./epic-74/learning-auth-review.md) validates the
  bearer-only CSRF finding.
- [Environment capabilities](./epic-74/verification/environment-capabilities.md)
  records what was and was not available for manual/device verification.

The remaining files under `epic-74/` are supporting raw evidence, prototypes or
slice reports linked by these indexes. Keep them out of the ordinary reading path;
do not delete them merely because the Epic is complete.
