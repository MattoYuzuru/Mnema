# Frontend scope

Adds to the root [`AGENTS.md`](../AGENTS.md); does not repeat it. Visual direction and tokens:
[brand and UI contract](../docs/frontend/mnema-brand-and-ui-contract.md); commands and machine setup:
[agent runbook](../docs/engineering/agent-runbook.md).

## Build and test

- Run from `frontend/`. Node is **22.23.2** in CI and images; the workstation default may be newer.
- `npm ci`, `npm run lint` (`ng lint`), `npm run test` (headless Chrome, single run),
  `npm run build` (production, output `dist/mnema-frontend`).
- For a user-visible flow, a green unit run is not enough: use the real-browser harness
  (`scripts/browser-identity/run.py --authoring --media --mechanics`, see the runbook).
  Do not claim assistive-technology, touch, Safari or Firefox coverage from it.

## Boundaries

- `src/app/app.routes.ts` is the route source of truth; feature routes are lazy. Deck authoring
  is under `/decks`; Study is `/decks/:deckId/study`. There is no legacy `my-study`, public-deck,
  template or import route; do not reintroduce one.
- Features live in `src/app/features/{authoring,own-decks,study}`; native document/editor/renderer in
  `src/app/content`. Theme values are centralized in `src/theme/tokens.css`; components use its semantic
  tokens as the brand contract requires.
- Wire shapes come from [`contracts/`](../contracts) fixtures that backend and frontend specs both
  execute (for example `study-contract.spec.ts`). Change fixture, contract and both sides together.
- Learner-facing models never carry answer keys; references arrive only in feedback. Validate server
  envelopes strictly and treat an unknown network outcome by retrying the exact attempt.
- Keep keyboard, screen-reader, touch and non-drag alternatives for every interactive exercise
  mechanic (accepted a11y boundary in the exercise catalog and design docs).
