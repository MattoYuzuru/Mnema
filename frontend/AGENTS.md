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
  legacy `my-study`, public-deck, template or import route; do not reintroduce one. The only public deck route is the
  read-only view of someone else's deck at `/d/:code/:slug` and `/d/:code` (`features/public-deck`, guests included, no `authGuard`; one matcher route).
- Feature ownership and product-specific boundaries (generation, goal, plans, promo, experiment):
  [frontend feature changes](../docs/engineering/repository-guide.md#frontend-feature-changes).
- Native document/editor/renderer lives in `src/app/content`; theme values live in
  `src/theme/tokens.css`. Read the owning wire contract and nearby tests before changing a flow.
- Shared UI classes live in `src/theme/components.css` (`.button`, `.notice`, `.hint`, `.field`, `.field-error`, `.stamp`, `.eyebrow`, `.empty-state`, `.paper-surface`); do not redefine them in a component's CSS, add layout deltas only. The living catalogue is `/styleguide` (`src/app/styleguide`, dev builds only, registered behind `ngDevMode` in `app.routes.ts`): a new shared element goes there first. After `npm run build` run `node scripts/verify-no-styleguide.mjs`; see [styleguide](../docs/frontend/styleguide.md).
- Wire shapes come from [`contracts/`](../contracts) fixtures that backend and frontend specs both
  execute (for example `study-contract.spec.ts`). Change fixture, contract and both sides together.
- Long lists use `shared/auto-load.component.ts` (`root` for an embedded scrolling panel, `context` when a reload may
  return the same cursor); the [styleguide list contract](../docs/frontend/styleguide.md#длинные-списки-и-автоподгрузка) owns the rule.
- Workshop node overlays, selection edits and revise intents have dedicated scope and
  accessibility boundaries: [frontend feature changes](../docs/engineering/repository-guide.md#frontend-feature-changes).
- Learner-facing models never carry answer keys; references arrive only in feedback. Validate server
  envelopes strictly and treat an unknown network outcome by retrying the exact attempt.
- Keep keyboard, screen-reader, touch and non-drag alternatives for every interactive exercise
  mechanic (accepted a11y boundary in the exercise catalog and design docs).
