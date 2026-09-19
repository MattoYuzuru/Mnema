# R74-E direct ProseMirror adapter prototype

Date: 2026-09-06  
Issue: #172  
Repository base supplied to the work package: `2b20459cf6511a1ce77f0087a403e0aeb001800e`  
Classification: isolated research evidence, not production adoption or a canonical AST decision

## Outcome

Direct ProseMirror is viable for the bounded Mnema adapter experiment. The prototype
keeps the persisted value as a validated Mnema envelope and treats ProseMirror nodes,
marks, transactions, selections, history, and plugin state as private runtime data.
It provides concrete positive evidence for schema-governed editing, safe inert unknown
nodes, native ruby/direction rendering, clipboard control, browser history, and exact
native UUID restoration across the tested mutations and their undo/redo cycles.

This is **not** enough to adopt the editor in production. Real Japanese IME, physical
mobile keyboards/selections, Safari/VoiceOver, Android/TalkBack, Firefox, acknowledged
server drafts, and Angular lifecycle/Signals integration were not exercised. Angular
integration was deliberately not attempted while the separately owned Angular 18→22
migration was in progress and while this work package prohibited shared frontend
source/dependency edits.

The 2026-09-12 contract-alignment rerun closes the previously documented adapter
gaps against frozen native-v1 commit `a766097ae4ef8ceb9bd1450f7c2ff7792a9ae27b`.
Private runtime metadata now preserves optional-attribute presence, original `lang`,
`dir`, URL and UUID spelling, original native mark order, and absence of editor
defaults. This applies to every supported node, including the root and inline leaves;
none of that private metadata is emitted as ProseMirror JSON. A native list whose
direct slot is opaque, or whose first paragraph has a future version, is represented
as one inert whole-list placeholder because the PM list schema cannot represent that
host grammar losslessly. Export returns the exact semantic native subtree; copy gives
every contained node a fresh UUID.

Validation now matches the exercised frozen envelope/node rules, global case-insensitive
UUID uniqueness, 32-bit versions and ordered-list starts, present-vs-absent marks,
nonempty/nested constraints, all-node language/direction attributes,
property/scalar/native/JSON bounds, and every shared language/HTTPS vector.
The browser's built-in URL/IDNA implementation cannot promise Java 21 `IDN` parity for
arbitrary A-labels without another dependency. Client validation is therefore
advisory beyond the shared vectors; the Java reader remains publication authority.

The proposed hidden `mnema_text_identity` mark worked mechanically in this bounded
test, including ProseMirror's adjacent-text coalescing behavior:

- import gives every native text leaf a private identity mark containing UUID/version;
- a document-changing transaction is followed by identity normalization;
- the first document-order fragment retains the old ID; split/missing fragments receive
  UUIDv4 IDs;
- the appended normalization participates in the originating ProseMirror history event,
  producing one user-visible undo step rather than a separate normalization step;
- exported Mnema JSON strips all private marks and ProseMirror state;
- one undo restores the exact pre-change native AST; one redo restores the exact
  post-change AST, including all generated split IDs.

A follow-up adversarial review found that the original 11 passing tests did not cover
link identity as rigorously as text identity. Three confirmed gaps existed: an
external HTTPS link mark could retain `nativeId: null`, splitting one linked run across
blocks duplicated its link UUID, and export/copy silently dropped the link container
or retained its old mark when the link contained ruby or an opaque inline atom. The
corrected prototype now normalizes contiguous link-mark groups alongside text/node
identities, assigns a fresh ID to a repeated group after a block split, exports every
allowed inline child inside its native link container, and freshens link marks on
atoms. The first group retains the original link ID; the second receives one UUID that
is stable through redo. The new regression set proves exact undo/redo and mixed-child
round trips. The earlier 11/11 result must not be read as evidence for these cases.

An initial experiment marked normalization `addToHistory: false`; it failed the exact
undo invariant because split identity marks survived after visible formatting was
undone. That variant is rejected. With ordinary appended-transaction history handling,
ProseMirror groups the normalization into the originating event: `undoDepth` is one,
not two, and exact before/after AST assertions pass for formatting, split, join, paste,
and move. This is adapter evidence only; it does not change or accept the proposed
canonical AST.

## What the prototype contains

`prototype/` is a complete static ESM harness. It is intentionally outside Angular and
outside `frontend/`:

- `src/adapter.js` — native validation, native↔ProseMirror conversion, identity
  normalization, opaque unknown nodes, strict URL/paste handling, move/copy helpers,
  and a separate DOM-built preview renderer;
- `src/fixtures.js` — Russian, Japanese ruby, Arabic RTL, English LTR, future
  type/version, code/math/media placeholders, unsafe-looking inert payload,
  long-document, near-limit, exact optional metadata, frozen lexical vectors, and
  opaque list-slot fixtures;
- `test/adapter.test.js` — deterministic adapter/history/copy/move/validation tests;
- `index.html`, `src/app.js`, and `src/prototype.css` — accessible split editor/preview
  demonstration with a mobile `Материал / Вид` mode;
- `verify-browser.mjs` — Chrome/Playwright verification;
- `measure.mjs` — synthetic near-limit adapter measurement;
- exact `package.json` and lockfile, containing only the approved dependency closure.

The browser never renders an opaque payload as HTML. Unknown-node views are built with
fixed elements and `textContent`; the editor exposes only a safe type/version label.
The research-only native JSON inspector also uses `textContent`. Paste HTML is parsed
in an inert document and rebuilt from the small allowlist `p`, line break, headings,
blockquote, lists, divider, strong/em/code, and HTTPS links. Scripts, styles, SVG,
forms, iframes, embedded/media elements, event attributes, credentials in URLs, and
non-HTTPS schemes do not enter the editor AST.

## Capability evidence

| Capability | Result | Evidence boundary |
|---|---|---|
| Mnema AST → PM → Mnema AST | Pass | Semantic deep equality for the mixed fixture; no private PM key in output |
| Optional native metadata | Pass | Root/every known node preserve presence, spelling, mark order and absence of defaults through edit + exact undo/redo |
| Opaque native list slots | Pass | Direct future list child and future first paragraph keep the whole native list inert and exact; recursive copy freshens IDs |
| Stable UUIDs on format split | Pass | One old ID survives, fresh UUIDv4 for other fragments, no duplicates |
| Stable link UUIDs on external paste/split | Pass | Missing IDs are generated; first split group keeps its ID, next group gets one fresh ID; exact undo/redo |
| Link with text + ruby + opaque children | Pass | Exact native round trip; all children remain inside the link container |
| Split/join | Pass | Exact native before/after AST and IDs restored by one undo/redo event |
| Undo/redo | Pass | Formatting undo equals original native AST; redo equals exact formatted AST/IDs |
| Move | Pass | Top-level opaque table keeps IDs/payload; exact undo/redo |
| Copy/paste identity | Pass | Fresh IDs recursively; exact undo/redo; multi-run and atom-containing links keep one fresh link ID without retaining the old atom mark |
| Unknown type | Pass | Inert placeholder; exact semantic JSON restored after edit/history |
| Known type with future version | Pass | Inert placeholder; exact semantic JSON restored |
| Future root version | Pass | Whole material read-only; exact original envelope exported |
| Ruby + RU + RTL/LTR | Pass in Chrome/static adapter | Present in editor and separate renderer; real assistive tech not tested |
| Real plain-text paste + undo/redo | Pass in Chrome | Browser clipboard permission and OS keyboard shortcuts |
| External/malicious HTML paste | Pass in synthetic clipboard event | HTTPS link passed sanitizer → PM parse → paste freshening → native export with a UUID and exact undo/redo; no execution/unsafe DOM; not a physical clipboard claim |
| Composition | Partial | PM reported composing and retained inserted Japanese text; event was synthetic, not an OS IME |
| Mobile selection/input | Partial | 390 CSS px, touch-enabled Chrome emulation; tab switch retained PM selection |
| Teardown | Pass for direct `EditorView` | View destroyed, DOM removed, stale input did not change output; Angular Signals not tested |
| Limits/validation | Pass for exercised cases | Frozen lexical vectors; semantic UUID duplicates; exact fields; host/list/link grammar; scalar/property/native/JSON bounds |

The adapter accepts an already parsed JavaScript value. Duplicate JSON object keys,
raw malformed UTF-8, raw-byte size, and source numeric spelling cannot be detected
after standard `JSON.parse`; canonical-byte expansion remains a server-ingress check.
The adapter independently bounds its parsed semantic JSON, but it is not a replacement
for `NativeDocumentReader`. The prototype also does
not implement editable tables, math, code blocks, Mermaid, media, upload, Markdown,
collaboration/Yjs, or arbitrary HTML.

## Reproduction

Use the approved Node runtime and an isolated sibling directory. The recorded run used
`/Users/m.ryabushkin/Projects/personal/mnema-r74-editor-LI7Lo4`; any new sibling copy is
equivalent.

```sh
cp -R docs/engineering/evidence/epic-74/editor/adapter/prototype \
  /Users/m.ryabushkin/Projects/personal/mnema-r74-editor-review
cd /Users/m.ryabushkin/Projects/personal/mnema-r74-editor-review

PATH=/tmp/mnema-epic175-node22-recovery.a4W4JI/runtime/node-v22.23.2-darwin-arm64/bin:$PATH \
  npm ci --ignore-scripts --no-audit

PATH=/tmp/mnema-epic175-node22-recovery.a4W4JI/runtime/node-v22.23.2-darwin-arm64/bin:$PATH npm test
PATH=/tmp/mnema-epic175-node22-recovery.a4W4JI/runtime/node-v22.23.2-darwin-arm64/bin:$PATH \
  node --expose-gc measure.mjs

PATH=/tmp/mnema-epic175-node22-recovery.a4W4JI/runtime/node-v22.23.2-darwin-arm64/bin:$PATH npm run serve
```

In another shell:

```sh
cd /Users/m.ryabushkin/Projects/personal/mnema-r74-editor-review
PATH=/tmp/mnema-epic175-node22-recovery.a4W4JI/runtime/node-v22.23.2-darwin-arm64/bin:$PATH \
PLAYWRIGHT_MODULE=/Users/m.ryabushkin/.npm/_npx/51691537fc71f2b0/node_modules/playwright/index.mjs \
  node verify-browser.mjs
```

`PLAYWRIGHT_MODULE` is an already available external Codex runtime, not a Mnema or
prototype dependency. The browser command uses installed Chrome at
`/Applications/Google Chrome.app/Contents/MacOS/Google Chrome` unless `CHROME_PATH`
overrides it.

Historical 2026-09-06 results (retained unchanged in the unsuffixed artifacts):

- `npm test`: 14/14 Node tests pass; the three added regressions cover external link
  IDs/paste freshening, linked block split with exact undo/redo, and mixed
  text+ruby+opaque link round-trip/copy;
- Chrome `152.0.7977.82`: no console warnings/errors, page errors, failed requests,
  or external requests in desktop and 390 px emulated-mobile runs;
- the synthetic Chrome HTTPS paste produced a fresh valid link UUID through the full
  clipboard pipeline and restored exact before/after native ASTs with undo/redo;
- the Chrome linked-block split retained the first link UUID, assigned the second a
  fresh UUID, and restored exact native ASTs with undo/redo;
- all 403 exported IDs after browser edits were unique; one normalization transaction
  was observed participating in the originating history event;
- 1440 px and 390 px document scroll widths equalled their viewport widths;
- every visible emulated-mobile button was at least 44 × 44 CSS px;
- `npm audit --omit=dev`: zero known vulnerabilities;
- 3,500-paragraph / 7,001-node / 986,013-byte synthetic native round trip, 12 local
  iterations on Node 22 arm64: median 36.63 ms, observed p95/max 58.39 ms and
  6,719,440-byte heap delta after GC. This is a local adapter measurement, not a
  browser rendering result or production SLO.

Contract-alignment rerun on 2026-09-12 (new suffixed artifacts):

- Node `22.23.2`: 18/18 tests pass. The four added test areas cover exact optional
  metadata/mark ordering/default absence, lossless opaque-list fallback and copy,
  all frozen lexical vectors, and aligned grammar/UUID/opaque/JSON-bound failures.
  Boundary assertions additionally reject `marks: null` while preserving absent
  `marks`, preserve `order: 2147483647`, and reject `order: 2147483648`;
- Chrome `153.0.8010.36`, runner Node `22.23.2`: no console/page/request failures;
  exact metadata edit undo/redo passed and both PM-incompatible list shapes rendered
  as inert whole-container placeholders with exact semantic export. The external HTML
  sanitizer and PM parser normalize overflow/zero `<ol start>` to the bounded absent
  default and preserve the positive-int32 maximum through native export;
- 3,500 paragraphs / 7,001 nodes / 986,013 bytes, 12 Node 22 local iterations:
  median 92.34 ms, observed p95/max 135.78 ms, 7,705,088-byte heap delta after GC.
  This is a synthetic local measurement and not a production SLO;
- a Node 26 diagnostic run made while the prescribed binary was temporarily absent is
  retained separately and is not acceptance evidence.

Raw machine evidence is in `artifacts/`; inspected viewport captures are in
`screenshots/`.

## Dependency closure

The lockfile resolves exactly the owner-approved direct and transitive versions. All
eleven packages report MIT:

| Direct | Transitive |
|---|---|
| `prosemirror-model@1.25.11` | `prosemirror-transform@1.12.1` |
| `prosemirror-state@1.4.4` | `orderedmap@2.1.1` |
| `prosemirror-view@1.42.3` | `rope-sequence@1.3.4` |
| `prosemirror-commands@1.7.2` | `w3c-keyname@2.2.8` |
| `prosemirror-history@1.5.0` | |
| `prosemirror-keymap@1.2.3` | |
| `prosemirror-schema-list@1.5.1` | |

The implementation follows supported APIs documented in the official
[ProseMirror guide](https://prosemirror.net/docs/guide/) and
[reference manual](https://prosemirror.net/docs/ref/): custom `Schema`, immutable
`EditorState`/transactions, `EditorView.dispatchTransaction`, `NodeView`,
`transformPastedHTML`/`transformPasted`, history commands, transaction metadata, and
`EditorView.destroy`. `prosemirror-view@1.42.3` is retained because it contains the
maintainer's fix for [GHSA-c8x8-7fp4-3x9w](https://github.com/ProseMirror/prosemirror-view/security/advisories/GHSA-c8x8-7fp4-3x9w).

## Next reviewable handoff

After the Angular 22 migration and canonical P0 node contract are reviewed, create a
small F74-2 adapter change that ports this evidence—not the prototype UI—into an
Angular standalone component. It should:

1. accept/emit only the reviewed shared native types and never persist PM JSON;
2. retain the one-event exact-AST undo/redo assertions in Angular component tests;
3. prove Signals synchronization and `DestroyRef`/view teardown with component tests;
4. add real Japanese IME plus Safari/VoiceOver and Android/TalkBack manual evidence;
5. connect acknowledged drafts only after the material/draft contract exists.

Production adoption should remain blocked if Angular integration, real IME/device
verification, or the eventual reviewed native node contract fails. This spike neither
changes the canonical AST document nor authorizes dependencies in the production frontend.
