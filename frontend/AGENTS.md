# Frontend scope

Adds to the root [`AGENTS.md`](../AGENTS.md); does not repeat it. Visual direction and tokens:
[brand and UI contract](../docs/frontend/mnema-brand-and-ui-contract.md); commands and machine setup:
[agent runbook](../docs/engineering/agent-runbook.md).

## Build and test

- Run from `frontend/`. Node is **24.21.0** (LTS) in CI and images; the workstation default may be newer.
- `npm ci`, `npm run lint` (`ng lint`), `npm run test` (Vitest + jsdom via `@angular/build:unit-test`, single run with
  coverage thresholds; no browser needed), `npm run build` (production, output `dist/mnema-frontend`).
- Unit tests run in jsdom, which has no layout engine (every box is 0x0): specs make no geometry assertions. Reflow,
  containment, grid stacking and decoration placement belong to the real-browser harness scenarios in
  `scripts/browser-identity/mechanics.mjs` (`mechanics_*_reflow`, `mechanics_constellation_geometry`,
  `mechanics_hold_to_delete_geometry`, `mechanics-study-*-390`). Browser-only APIs jsdom lacks are shimmed in
  `src/testing/jsdom-setup.ts`; do not skip a spec for lack of layout, move the geometry check to the harness.
- Spies are restored after every test (`vitest.config.mts`); write new specs with `vi.fn`/`vi.spyOn`, `SpyObj`/`spyObj`
  from `src/testing/mocks.ts` and `vi.useFakeTimers()`, not `fakeAsync` (the app and tests are zoneless).
- For a user-visible flow, a green unit run is not enough: use the real-browser harness
  (`scripts/browser-identity/run.py --authoring --media --mechanics`, see the runbook).
  Do not claim assistive-technology, touch, Safari or Firefox coverage from it.

## Boundaries

- Change detection is zoneless (no `zone.js`, no `provideZoneChangeDetection`); components are `OnPush` and rendering must be
  driven by signals, template events or `markForCheck`. Do not add `NgZone`-dependent code or zone polyfills.
- `src/app/app.routes.ts` is the route source of truth; feature routes are lazy. Deck authoring
  is under `/decks`; Study is `/decks/:deckId/study`. `/decks/:deckId` is the Deck hub (statistics, the material
  list with selection and bulk delete); there is no separate `/decks/:deckId/materials` list route. There is no
  legacy `my-study`, public-deck, template or import route; do not reintroduce one.
- Features live in `src/app/features/{ai-info,authoring,generation,goal,own-decks,plans,study,usage}` (`usage` is the profile's «ИИ-бюджет» block over `GET /api/usage`; `plans` is the paywall `/plans` over `GET /api/plans` plus the profile's «Тариф» block (read-only: nothing charges or grants); `goal` is the once-asked «Для чего вам Mnema?» (`LearningGoalStore`, centralised copy in `goal-copy.ts`, never sent to a provider); `ai-info` is the public `/ai`; `generation` is the AI composer and Workshop over `contracts/generation`, see the brand contract); native document/editor/renderer in
  `src/app/content`. Theme values are centralized in `src/theme/tokens.css`; components use its semantic
  tokens as the brand contract requires.
- Shared UI classes live in `src/theme/components.css` (`.button`, `.notice`, `.hint`, `.field`, `.field-error`, `.stamp`, `.eyebrow`, `.empty-state`, `.paper-surface`); do not redefine them in a component's CSS, add layout deltas only. The living catalogue is `/styleguide` (`src/app/styleguide`, dev builds only, registered behind `ngDevMode` in `app.routes.ts`): a new shared element goes there first. After `npm run build` run `node scripts/verify-no-styleguide.mjs`; see [styleguide](../docs/frontend/styleguide.md).
- Wire shapes come from [`contracts/`](../contracts) fixtures that backend and frontend specs both
  execute (for example `study-contract.spec.ts`). Change fixture, contract and both sides together.
- Node ids are the Workshop's alone (AI-11): the renderer draws `data-node-id` on top-level blocks only when it is given
  `exposeNodeIds` (and the Workshop's marks and slots through `overlay`); Browse, Study and the editor preview never pass them.
  Selection edits (`proposal-document`, `ai-prompt-window`) read a selection by node id, send whole blocks to `editArtifact` and
  follow the turn through the events; they announce only through the Workshop's summary line. The keyboard route is Shift+F10 or
  the menu key while the group is shown (the group says so); Tab from the selection reaches the group too, but after the block's own
  focusable elements (links, players), because the group follows the document in the tab order.
- «Попросить Мнему…» (AI-16, `generation/ask-mnema.component`) is the one composer of free text for what already exists: a collapsed disclosure in the
  material profile and the exercise editor (only with `aiGeneration`). The free `createIntent` call returns a spec the server built and clamped;
  the owner edits it as chips (the exercise builder's own `exercise-settings-fields`, the instruction, the voice) and only «Запустить» creates the
  session, which is the one step that reserves. `REVISE_ITEM` and `REVISE_EXERCISE` results live in the Workshop (`revise-item-result`,
  `revise-exercise-result`): «Оставить» is an ordinary revise approval, «Вернуть» goes to the first revision of the draft, «Ещё раз» is a new edit.
- Learner-facing models never carry answer keys; references arrive only in feedback. Validate server
  envelopes strictly and treat an unknown network outcome by retrying the exact attempt.
- Keep keyboard, screen-reader, touch and non-drag alternatives for every interactive exercise
  mechanic (accepted a11y boundary in the exercise catalog and design docs).
