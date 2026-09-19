# Authoring UI candidate — issue #202

Status: local review candidate on `epic-74/issue-202-authoring-ui`; not merged,
deployed or verified against the pending #201 backend yet. This record deliberately
does not claim the HTTPS browser acceptance that requires the integrated API.

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
- Draft edits debounce to the server and retain an uncertain command ID for exact
  retry. A replay acknowledgement is reconciled with a fresh draft GET before it
  unlocks publication. Publication remains an explicit action and is available
  only after the current document has a server acknowledgement.
- Unknown-outcome draft creation and Capture create/convert retain their exact
  command and payload for replay instead of creating duplicate resources. Draft
  CAS conflicts expose explicit server-version and keep-local choices; keeping
  local first refreshes the acknowledged row version and requires a new save.
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

This is code/test evidence, not a claim of screen-reader or physical-device
certification. VoiceOver/TalkBack, mobile selection, real IME composition,
200% browser zoom and the 320/768/1440 viewport matrix remain in the integrated
HTTPS run below.

## Dependency and bundle evidence

Only the exact owner-approved direct packages were added:
`prosemirror-model@1.25.11`, `state@1.4.4`, `view@1.42.3`,
`commands@1.7.2`, `history@1.5.0`, `keymap@1.2.3` and
`schema-list@1.5.1`. They and their approved transitive set are MIT. Production
build output preserves their texts in `dist/mnema-frontend/3rdpartylicenses.txt`;
the root Mnema license and notice are unchanged. `prosemirror-view` is not below
the version containing the paste-XSS fix recorded in the dependency decision.

The production build on 2026-09-19 reported 594.86 kB raw / 147.29 kB estimated
initial JavaScript+CSS, with ProseMirror isolated to the guarded editor lazy chunk
(249.22 kB raw / 66.94 kB estimated). Capture and Browse remain separate 13.51 kB
and 10.96 kB raw lazy chunks and do not import the editor runtime. No speculative
request fan-out is introduced: Browse performs Deck + one bounded item page in
parallel; editor performs Deck + item + at most ten bounded draft pages, then one
draft detail/create request; Capture paginates explicitly.

## Local verification

- `npm run lint` — pass.
- `npm test` — 156 ChromeHeadless tests, zero failures. They cover route guards/laziness, exact item/draft/
  Capture wire contracts, private cache controls and CAS headers; native golden
  round-trip, unsafe paste, future-root read-only behavior, identity split/copy,
  undo and native limits; editor inert DOM and publication lock; long multilingual
  Capture conversion.
- `npm run build` — pass; lazy chunk and third-party notice evidence above.
- `npm audit --omit=dev --audit-level=low` under the repository Node 22.23.2/npm
  10.9.8 toolchain — zero production vulnerabilities.

## Integration gate after #201 rebase

Rebase onto the final #201 API and run the repository frontend gate plus real
authenticated HTTPS browser E2E. The E2E must prove create/reload/restore/autosave,
exact retry and 412 conflict, Capture source preservation/conversion, Browse/editor
renderer identity, offline/reconnect, unsafe HTML/JS/SVG/URL non-execution, IME,
RTL/ruby/paste/undo/redo, keyboard-only operation, VoiceOver/TalkBack smoke,
touch/coarse pointer, reduced motion, forced colors, 320/768/1440 widths and 200%
zoom. Record request counts and cold-route timings on the integrated build rather
than inventing an SLO from this isolated branch.

Rollback is a protected revert of this UI change plus its dependency lock entries;
server drafts, captures and published native documents remain canonical API data.
