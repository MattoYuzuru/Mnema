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
- Features live in `src/app/features/{authoring,generation,own-decks,study,usage}` (`usage` is the profile's «ИИ-бюджет» block over `GET /api/usage`; `generation` is the AI composer and Workshop over `contracts/generation`, see the brand contract); native document/editor/renderer in
  `src/app/content`. Theme values are centralized in `src/theme/tokens.css`; components use its semantic
  tokens as the brand contract requires.
- Wire shapes come from [`contracts/`](../contracts) fixtures that backend and frontend specs both
  execute (for example `study-contract.spec.ts`). Change fixture, contract and both sides together.
- Learner-facing models never carry answer keys; references arrive only in feedback. Validate server
  envelopes strictly and treat an unknown network outcome by retrying the exact attempt.
- Keep keyboard, screen-reader, touch and non-drag alternatives for every interactive exercise
  mechanic (accepted a11y boundary in the exercise catalog and design docs).
