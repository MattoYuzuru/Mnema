# Authoring UI candidate — issue #202

Status: merged through protected PR #207 as
`f5ae54d4f1dab4665b398290524017f0d3d4c656`; verified against the merged #201
backend and the canonical multi-edit publication contract. It is not deployed;
deployment is intentionally outside the current local-only delivery boundary.

## Implemented boundary

- Guarded lazy routes provide bounded Browse pages, a server-backed multi-document
  draft editor and per-Deck Capture. The Deck page links the complete
  Browse → Capture/new → edit → explicit publish → shared-renderer loop.
- `ItemApiService` and `AuthoringApiService` use the canonical #200/#201 routes,
  strict response shapes, `private, no-store`, strong `ETag`/`If-Match`, opaque
  cursor bounds and exact command IDs. They do not use product mocks, browser
  storage or ProseMirror JSON as a persistence contract.
- Direct item reads intentionally accept `ordinal: null`. Edit links carry the
  source ordinal obtained from the current K3-backed Browse page or create
  acknowledgement; save sends it as required `expectedOrdinal`, so a stale
  position fails closed instead of scanning or trusting the browser.
- Existing-item publication computes at most 100 ordered `insert`/`delete`/`move`
  intents against stable native IDs. The backend validates every evolving topology,
  treats insert as an atomic final subtree and requires the final topology to equal
  the submitted native document before one publication transaction can commit.
- Draft edits debounce to the server and retain an uncertain command ID for exact
  retry. A replay acknowledgement is reconciled with a fresh draft GET before it
  unlocks publication. Publication remains an explicit action and is available
  only after the current document has a server acknowledgement.
- Unknown-outcome draft creation and Capture create/convert retain their exact
  command and payload for replay instead of creating duplicate resources. Draft
  CAS conflicts expose explicit server-version and keep-local choices; keeping
  local first refreshes the acknowledged row version and requires a new save.
- Deterministic 4xx rejections release the editor for correction and a new command;
  uncertain transport/5xx outcomes retain the exact command. Replayed publication
  performs a fresh item GET before deleting its source draft. An external document
  replacement also resets the ProseMirror state, preventing a resolved server copy
  from being overwritten by stale local state on the next keystroke.
- The Mnema-owned direct ProseMirror adapter persists only native-v1. It preserves
  the shared golden fixture exactly, keeps unknown/future nodes opaque, allocates
  UUIDv4 identities for split/pasted known and opaque subtrees, and keeps identity
  normalization in the originating undo event. Future document roots are read-only.
- Paste removes executable/embedded HTML and unsafe links before schema parsing.
  The independent native boundary enforces the 1 MiB, 10,000-node, depth-32,
  JSON-depth-128 and 32 KiB scalar bounds before editor or API use. The existing
  shared native renderer remains the sole preview and Browse renderer.

## Accessibility and responsive behavior

The production shell remains the single `main`; every authoring page has one `h1`.
Controls use semantic links, buttons, labels, fieldsets, status/alert regions,
44-pixel minimum targets and visible focus. Formatting toggles expose
`aria-pressed`; the editor surface is a labelled multiline textbox and becomes
non-editable, together with its toolbar, during publication. CSS supplies a
single-column mobile layout, forced-colors borders and reduced-motion fallback.

The real HTTPS run verified keyboard order, visible skip-link focus transfer,
reduced-motion behavior, no horizontal overflow at 390 CSS px, a 44 px publication
target, and existing own-deck evidence at 1440/390 plus 320 CSS px at 2x raster
scale. Current authoring screenshots are
[editor 390](authoring-browser/authoring-editor-390.png) and
[Browse 1440](authoring-browser/authoring-browse-1440.png).

This is code/browser evidence, not a claim of physical-device certification.
Chrome's real contenteditable path, native insertion, paste sanitation, ruby,
mixed RTL/LTR, undo/redo and long multilingual fixtures are covered, but a human
VoiceOver/TalkBack session, real Japanese OS IME, physical touch selection, Safari
and Firefox remain explicitly unverified device/manual gaps. Automated semantics
and synthetic composition do not replace those checks.

## Dependency and bundle evidence

Only the exact owner-approved direct packages were added:
`prosemirror-model@1.25.11`, `state@1.4.4`, `view@1.42.3`,
`commands@1.7.2`, `history@1.5.0`, `keymap@1.2.3` and
`schema-list@1.5.1`. They and their approved transitive set are MIT. Production
build output preserves their texts in `dist/mnema-frontend/3rdpartylicenses.txt`;
the root Mnema license and notice are unchanged. `prosemirror-view` is not below
the version containing the paste-XSS fix recorded in the dependency decision.

The final production build on 2026-09-19 reported 591.99 kB raw / 146.68 kB estimated
initial JavaScript+CSS, with ProseMirror isolated to the guarded editor lazy chunk
(253.10 kB raw / 67.80 kB estimated). Capture and Browse remain separate 13.51 kB
and 10.96 kB raw lazy chunks and do not import the editor runtime. No speculative
request fan-out is introduced: Browse performs Deck + one bounded item page in
parallel; editor performs Deck + item + at most ten bounded draft pages, then one
draft detail/create request; Capture paginates explicitly.

## Local verification

- `npm run lint` — pass.
- `npm test` — 162 ChromeHeadless tests, zero failures. They cover route guards/laziness, exact item/draft/
  Capture wire contracts, private cache controls and CAS headers; native golden
  round-trip, unsafe paste, future-root read-only behavior, identity split/copy,
  undo and native limits; editor inert DOM and publication lock; long multilingual
  Capture conversion.
- `npm run build` — pass; lazy chunk and third-party notice evidence above.
- `npm audit --omit=dev --audit-level=low` under the repository Node 22.23.2/npm
  10.9.8 toolchain — zero production vulnerabilities.
- Learning parser/editor unit and real PostgreSQL structural/item integration suites
  pass with ordered multi-edit, final-subtree insert and invalid-topology cases.
- `python3 -m unittest scripts/browser-identity/test_fixture.py` — 11 tests pass;
  `node --check scripts/browser-identity/browser.mjs` passes.

## Integrated HTTPS evidence

The reproducible command is documented in
[authoring browser evidence](authoring-browser/README.md). It started real packaged
Identity and Learning applications against disposable PostgreSQL 18.6 and served
the production Angular build over HTTPS. Chrome 154 completed the authenticated
authoring loop in 2.410 seconds and 85 requests inside the already-running fixture:
create Deck, create adversarial multilingual Capture, convert, edit native content,
receive a server draft acknowledgement, reload and restore it, explicitly publish,
render the item in Browse, and directly re-read the preserved Capture source and
conversion. This duration is local evidence, not a production SLO.

The sanitized raw summaries are [browser.json](authoring-browser/browser.json) and
[fixture.json](authoring-browser/fixture.json). The run recorded 0 browser runtime
errors, 0 external requests, no executed unsafe markup, fresh Identity validation,
logout revocation and two-account isolation. Private keys, credentials, bearer
tokens and service logs were deleted by the harness and are not evidence artifacts.

The exact candidate full repository gate and hosted PR checks passed before protected
squash; #203 independently reruns the integrated `main` evidence. Offline/reconnect
and 412 semantics have deterministic
unit/integration coverage; the real browser flow covers their ordinary successful path.

Rollback is a protected revert of this UI change plus its dependency lock entries;
server drafts, captures and published native documents remain canonical API data.
