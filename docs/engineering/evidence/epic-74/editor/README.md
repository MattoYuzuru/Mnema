# R74-E editor research checkpoint

Research date: 2026-09-06; implementation evidence updated 2026-09-19

Original research base: `33a71f814185a16e922923e034518e25baeadbb8`

Issue: #172

Status: owner approvals recorded; Angular 22 migration and the exact direct
ProseMirror set are implemented. The Mnema-owned adapter and production authoring
UI are verified locally on the issue #202 candidate. Protected merge and integrated
main verification remain delivery gates, not research blockers.

## Implemented result

The recommendation below was accepted without adding an Angular wrapper, Tiptap,
collaboration state or a second persisted format. The direct adapter imports and
exports only native-v1, keeps ProseMirror state private, preserves unknown nodes as
inert payloads and assigns stable UUIDv4 identities through split/paste/history
transactions. Shared preview and Browse continue to use the independent safe native
renderer.

ChromeHeadless coverage includes golden round-trip, stable and copied identities,
unknown/future nodes, ruby, RTL/LTR, formatting, paste sanitation, unsafe URL/HTML/
SVG corpus, undo/redo, external document replacement, native size/depth/node/scalar
limits and a 10,000-node render fixture. The integrated real HTTPS run additionally
used the production build and real contenteditable input through acknowledged draft
reload and publication; see [authoring UI evidence](../authoring-ui.md).

The final build keeps the editor in a guarded 253.10 kB raw lazy chunk; the initial
bundle is 591.99 kB raw and Deck/Capture/Browse do not import that runtime. The
distribution preserves the MIT notices and the production dependency audit reports
zero vulnerabilities. Human VoiceOver/TalkBack, real Japanese OS IME, physical
mobile selection, Safari and Firefox remain explicitly unverified manual/device
coverage rather than inferred passes.

## Recommendation requiring owner approval

Use direct ProseMirror modules for the isolated editor spike, pinned exactly:

| Direct package | Version | License | Purpose |
|---|---:|---|---|
| `prosemirror-model` | `1.25.11` | MIT | Schema and document model |
| `prosemirror-state` | `1.4.4` | MIT | Immutable editor state and transactions |
| `prosemirror-view` | `1.42.3` | MIT | Browser editing surface and selection |
| `prosemirror-commands` | `1.7.2` | MIT | Baseline editing commands |
| `prosemirror-history` | `1.5.0` | MIT | Undo and redo |
| `prosemirror-keymap` | `1.2.3` | MIT | Keyboard command binding |
| `prosemirror-schema-list` | `1.5.1` | MIT | List schema helpers and commands |

The exact resolved closure at research time is 11 packages. The four indirect
packages are `prosemirror-transform@1.12.1`, `orderedmap@2.1.1`,
`rope-sequence@1.3.4`, and `w3c-keyname@2.2.8`; all are MIT. Registry-reported
unpacked size for the closure is 2,277,554 bytes (2.17 MiB). This is not a browser
bundle measurement.

`prosemirror-view` must not be downgraded below `1.42.3`. The ProseMirror maintainer
reports paste-triggered XSS in earlier releases under `GHSA-c8x8-7fp4-3x9w`.
Mnema must still validate its own AST, URL schemes, and clipboard conversion; the
library fix does not replace those boundaries.

Approval requested: add only the seven direct packages above for R74-E. Do not add
an Angular wrapper, Tiptap, collaboration/Yjs, a sanitizer package, Markdown parser,
table package, or media/math/diagram editor in this spike.

## Why browser and Angular facilities are insufficient

`contenteditable` supplies browser editing, but not Mnema's schema validation,
transaction model, stable selection mapping, history, deterministic clipboard
conversion, or cross-browser IME workarounds. Angular forms and Signals coordinate
state; they do not implement a structured rich-text editor. Building those pieces
locally would recreate the risky part of an editor engine.

ProseMirror is a lower-level toolkit rather than a drop-in UI. That is useful here:
Mnema keeps ownership of its AST, rendering, controls, accessibility text, and paper
styling while the engine owns editing transactions and browser selection. The modules
are framework-agnostic ESM packages with TypeScript declarations and no Angular peer
dependency. Angular integration is a small lifecycle wrapper around `EditorView`, not
a third-party Angular component.

## Candidate comparison

| Criterion | Direct ProseMirror | Tiptap `3.31.3` |
|---|---|---|
| Core model | Schema-governed immutable tree and transactions | ProseMirror underneath an extension/editor facade |
| Angular path | Direct DOM lifecycle integration; no wrapper required | Official docs call Angular integration a community effort; direct vanilla integration remains possible |
| Mnema AST boundary | Explicit adapter is unavoidable and visible | Explicit adapter is still required; Tiptap JSON must not become the contract |
| Unknown content | Schema rejects unknown content; adapter must provide opaque atoms | Official docs state unknown content is stripped by default unless content checks are enabled; preservation still needs an adapter |
| Built-in convenience | Low; toolbar, schema and commands are assembled locally | Higher through StarterKit and extensions |
| Exact closure considered | 11 packages, all MIT | 42 packages, all MIT |
| Registry unpacked footprint | 2.17 MiB | 7.98 MiB |
| Decision | Recommended for the bounded spike | Rejected for now; convenience does not remove the hard adapter work |

The Tiptap comparison used exact direct packages `@tiptap/core@3.31.3`,
`@tiptap/pm@3.31.3`, and `@tiptap/starter-kit@3.31.3`. Their closure contains 25
Tiptap packages/extensions, 14 ProseMirror packages, and `linkifyjs`, `orderedmap`,
and `rope-sequence`. Tiptap's documented Ruby extension is useful evidence that
ruby can be edited on a ProseMirror base, but it is not in StarterKit and was not
included in the proposed dependency set.

The rejection is proportional, not permanent. Revisit Tiptap if the direct
ProseMirror spike shows that Mnema would otherwise build a large generic extension
framework. Do not revisit merely to obtain ready-made visual controls; the accepted
paper UI still needs Mnema-owned semantic HTML and CSS.

## Version and maintenance risk

- The current ProseMirror modules are actively published, but some repositories moved
  from GitHub to the maintainer's `code.haverbeke.berlin` host in 2026. Pin exact
  versions and preserve lockfile integrity rather than relying on broad ranges.
- `prosemirror-view@1.42.3` was published shortly before this research and is a
  security fix. The spike must include malicious clipboard fixtures and a normal
  dependency audit; freshness is not evidence of regression freedom.
- ProseMirror deliberately leaves menus, labels, focus behavior around custom node
  views, and accessibility semantics to the integrator.
- Neither candidate guarantees real Japanese IME, Android keyboard, Safari, or
  screen-reader behavior. Those are verification gates, not documentation claims.

## Angular baseline and separate upgrade gate

The repository is Angular `18.2.14`, TypeScript `5.5.4`, RxJS `7.8.1`, and Zone.js
`0.14.10`. Angular 18 is no longer supported. The local default Node `26.3.0` is not
supported by Angular 18; CI uses Node `22.23.2`, and the same runtime is available at:

```text
/tmp/mnema-143-resume/node22/node-v22.23.2-darwin-arm64/bin/node
```

Current supported Angular is `22.1.5`. Both Node `22.23.2` and Node `26.3.0` satisfy
Angular 22's official runtime range. Angular requires cross-major updates one major
at a time, so approval must cover these exact migration targets:

| Stage | Runtime/core/compiler/forms/router/platform | CLI/build | CDK | `angular-eslint` | TypeScript | Zone.js |
|---|---:|---:|---:|---:|---:|---:|
| 18 -> 19 | `19.2.25` | `19.2.27` | `19.2.19` | `19.8.1` | `5.8.3` | `0.15.1` |
| 19 -> 20 | `20.3.30` | `20.3.36` | `20.2.14` | `20.7.0` | `5.9.3` | `0.15.1` |
| 20 -> 21 | `21.2.22` | `21.2.23` | `21.2.14` | `21.4.0` | `5.9.3` | `0.16.3` |
| 21 -> 22 | `22.1.5` | `22.1.7` | `22.1.5` | `22.2.0` | `6.0.3` | `0.16.3` |

At the final stage, update RxJS to `7.8.2` and `tslib` to `2.8.1`. Licenses are:
Angular/CDK/CLI, Zone.js and angular-eslint MIT; TypeScript and RxJS Apache-2.0;
`tslib` 0BSD.

Run every exact major migration with the installed Node 22 runtime and execute lint,
tests, and production build before advancing to the next major. Keep this as a
separate child/PR from R74-E so migration changes and editor behavior remain
reviewable.

Remove `@angular/animations` and `provideAnimations()` during the migration instead
of upgrading them. The repository has no animation trigger usage; Angular deprecated
the package in 20.2 and intends removal in 23. Native CSS plus the existing reduced
motion policy fits the accepted design direction.

## Mnema AST adapter proposal

The canonical persisted value remains a Mnema document envelope. ProseMirror
`EditorState`, plugin state, selections, steps, decorations, history, and ProseMirror
JSON are private runtime data and are never sent as the material or draft contract.

Proposed uniform native shape:

```json
{
  "id": "UUIDv4",
  "type": "paragraph",
  "version": 1,
  "attrs": {"lang": "ru", "dir": "auto"},
  "content": [
    {
      "id": "UUIDv4",
      "type": "text",
      "version": 1,
      "attrs": {"text": "Длинный текст", "marks": ["strong"]},
      "content": []
    }
  ]
}
```

Rules before shared fixtures are accepted:

- every node, including `doc` and text leaves, has a unique UUIDv4 `id`;
- every node has an independently supported integer version;
- text marks are an ordered, duplicate-free enum array, not arbitrary CSS/HTML;
- `lang` is a validated BCP 47 value and `dir` is `auto`, `ltr`, or `rtl` where the
  node capability permits them;
- canonicalization fixes attribute and mark ordering for semantic hashes but does
  not claim byte-preserving JSON round trips;
- proposed engineering bounds are 1 MiB serialized UTF-8, 10,000 nodes, depth 32,
  and 32 KiB per scalar text value. Long paragraphs use multiple text runs rather
  than bypassing the scalar bound;
- duplicate IDs, duplicate JSON keys, unsupported number values, malformed envelopes,
  and limit violations fail before editor construction.

Initial semantic types may include `doc`, `section`, `paragraph`, `heading`,
`blockquote`, `bullet_list`, `ordered_list`, `list_item`, `divider`, `table`, `row`,
`cell`, `text`, `ruby`, and `link`. `ruby` owns validated `base` and `reading` strings.
`link` is an inline container with a validated HTTPS `href`. Code, math, Mermaid,
and media remain typed data with safe placeholders until their editor capability is
implemented; the spike does not imply media upload or executable rendering.

### Stable text IDs: explicit compatibility risk

ProseMirror text nodes cannot carry attributes, and adjacent text nodes with the same
marks are automatically merged. Directly serializing a ProseMirror document would
therefore lose the proposed native text-run IDs.

The spike should test this adapter strategy:

1. Import a native text leaf as ProseMirror text with a private, non-rendered
   `mnemaTextId` mark containing its `id` and node version.
2. Convert native marks to separate ProseMirror marks.
3. Normalize after document-changing transactions: one surviving run retains an old
   ID; genuinely split or inserted runs receive client-generated UUIDv4 IDs.
4. Export each run back to a native `text` leaf and strip the private identity mark
   from native marks.
5. Do not add normalization-only changes to user-visible undo history.

Composition, formatting a substring, paste, split/join, copy, undo, and redo must
prove that this strategy is stable. If normalization perturbs IME or makes identity
ambiguous, that is a schema decision blocker: reject ProseMirror or revise text-run
identity before production binding. Do not silently exempt text from stable IDs.

### Unknown type/version preservation

An unknown type or an unsupported version of a known type becomes an inert
`unsupported_block` or `unsupported_inline` atom. Classification comes from the
known parent's unambiguous content slot. Avoid P0 parent schemas that accept mixed
block and inline children because an unknown child could not be classified safely.

The atom retains a deep copy of the complete native JSON in editor-only state. Its
DOM contains only a fixed accessible label and safe type/version text created with
DOM APIs; the opaque payload is never placed in an HTML attribute or `innerHTML`.
Export restores the untouched JSON semantically unless the user explicitly deletes
or replaces the placeholder. Move and undo/redo retain the same IDs; copy creates
fresh IDs recursively so a document cannot contain duplicates.

Known parents and the generic envelope validator may still enforce total size,
depth, node count, UUID uniqueness, and JSON shape inside an unknown subtree without
interpreting its node-specific attributes. An unknown root is opened read-only as
one unsupported document rather than coerced to an empty known document.

### Capability boundary for the seven-package spike

The spike should directly edit paragraphs, headings, basic marks, blockquotes, lists,
divider, direction/language, ruby, and safe HTTPS links. Tables can round-trip as an
inert typed subtree for this gate. Reliable editable tables would require a separate
approval for `prosemirror-tables` or a later evidence-backed implementation; writing
table behavior from scratch is not justified.

Paste is converted through a strict allowlist into registered nodes. Event handlers,
forms, iframes, styles, scripts, SVG, public media URLs, and non-HTTPS schemes never
enter the AST. Rendering uses Mnema's separate safe renderer, not copied editor DOM.

## Prototype verification plan after approval

Automated contract/spec evidence:

- native AST -> editor -> native AST semantic equality, stable IDs, and canonical
  mark ordering;
- unknown type and known type with future version survive unchanged;
- formatting, text split/join, ruby, RTL/LTR, undo/redo, and sanitized paste;
- malicious clipboard corpus and URL scheme rejection;
- private ProseMirror state absent from serialized native output;
- long Russian fixture and document/node/depth/scalar limits;
- component teardown destroys the editor and stale callbacks do not mutate Signals.

Browser evidence:

- desktop split editor/preview and mobile `Материал / Вид` mode preserve input,
  selection where feasible, and focus order;
- keyboard-only toolbar, editor, unsupported placeholders, save, and preview;
- Chrome desktop at 320/390/768/1440 CSS px, zoom/text scaling, reduced motion,
  narrow Russian labels, and no horizontal overflow;
- real paste, undo/redo, composition, ruby, mixed RTL/LTR, and long-document input;
- console errors and unexpected network requests captured.

Available today: Chrome `152.0.7977.82`, Safari `26.6.2`, Karma/Jasmine through the
Angular test target, and ChromeHeadless. Playwright, Puppeteer, Selenium, and a
repository E2E target are not installed in Mnema. An existing external Codex runtime
later supplied Playwright for the isolated paper-shell visual check without changing
Mnema dependencies. Synthetic composition events are not a real OS IME test. Real
Japanese IME, Safari/VoiceOver, Android/TalkBack, physical touch keyboards, and
Firefox remain explicitly unverified until those environments are exercised.

## Sources and reproducible inspection

Primary sources used:

- [ProseMirror guide](https://prosemirror.net/docs/guide/) — schema, immutable nodes,
  transactions, serialization, view, paste, selection, and browser/IME behavior.
- [ProseMirror reference](https://prosemirror.net/docs/ref/) and
  [module index](https://code.haverbeke.berlin/prosemirror/) — module boundaries.
- [`prosemirror-view@1.42.3` registry metadata](https://www.npmjs.com/package/prosemirror-view/v/1.42.3)
  and [maintainer security announcement](https://discuss.prosemirror.net/t/xss-vulnerability-found-in-prosemirror-view/9067).
- [Tiptap install guide](https://tiptap.dev/docs/editor/getting-started/install) —
  framework-agnostic core and community Angular status.
- [Tiptap schema handling](https://tiptap.dev/docs/editor/core-concepts/schema),
  [persistence](https://tiptap.dev/docs/editor/core-concepts/persistence),
  [Ruby extension](https://tiptap.dev/docs/editor/extensions/marks/ruby-text), and
  [MIT license](https://github.com/ueberdosis/tiptap/blob/main/LICENSE.md).
- [Angular compatibility table](https://angular.dev/reference/versions),
  [release/update policy](https://angular.dev/reference/releases),
  [`ng update`](https://angular.dev/cli/update), and
  [animations migration](https://angular.dev/guide/animations/migration).

Read-only commands used; none install or mutate dependencies:

```sh
git rev-parse HEAD
git status --short
node --version
npm --version
npx ng version
npm ls --depth=0
npm view <package> version license dependencies peerDependencies engines --json
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --version
/usr/bin/safaridriver --version
```

No full quality gate was run for this research-only checkpoint. No editor runtime,
round trip, IME, browser interaction, accessibility, or performance result is claimed
before the dependency approval and isolated prototype exist.

## Independent paper shell while approvals are pending

With lead-owned file boundaries, a dependency-free presentational slice was added
under `frontend/src/app/paper/`:

- `PaperShellComponent` receives viewer/session display data and route strings as
  signal inputs; it does not inject auth or API services;
- `PaperLandingComponent` covers own-deck authoring, durable «На потом», whole
  materials, and Browse without claiming Study, catalog, AI, or server behavior;
- both components use semantic landmarks/headings/links, a skip target, visible
  focus, 44+ px primary targets, straight paper rules, indigo/cream tokens, forced
  color fallbacks, and responsive layout rules;
- the optional engraving is an input. No asset was copied and the default state has
  no broken network request;
- no route, bootstrap, auth, global style, dependency, renderer, or shared AST file
  was changed.

Local validation used the CI-compatible Node runtime:

```sh
PATH=/tmp/mnema-143-resume/node22/node-v22.23.2-darwin-arm64/bin:$PATH \
  ./node_modules/.bin/eslint 'src/app/paper/**/*.ts' 'src/app/paper/**/*.html'

PATH=/tmp/mnema-143-resume/node22/node-v22.23.2-darwin-arm64/bin:$PATH \
  npm test -- --include='src/app/paper/*.spec.ts'
```

Both commands exited 0 on 2026-09-06. Karma ran 5 specs successfully in Chrome
Headless `152.0.0.0`. The test build emitted the repository's existing stale
`baseline-browser-mapping` data warning; no dependency was changed to silence it.

The components remain deliberately unintegrated. The lead owns
route/assets/global-token integration and the full frontend quality gate.

### Isolated visual verification

This is disposable-harness evidence, not production integration evidence. At shared
HEAD `2afcb1e631c70b69c9ccde0cdceb0d7fc1e80a00`, the owned components were linked
into `/tmp/mnema-r74-paper-gKmCHy`; that harness reused Mnema's installed Angular 18
dependencies, the CI-compatible Node `22.23.2` binary, the existing external Codex
Playwright runtime, and installed Chrome `152.0.7977.82`. No package manifest,
dependency, shared application source, global style, or asset was changed.

The authenticated fixture used this deliberately long value:
`Марина Александровна Рябушкина — исследовательница очень длинных русских названий`.
The first capture exposed horizontal overflow and ambiguous clipping of the account
label. Scoped CSS now gives every component descendant predictable border-box
sizing, constrains the account control, and ellipsizes a dedicated label span while
keeping the arrow visible. The skip link was also raised to a 44 px minimum height.

The rerun found, at 320, 390, 768, and 1440 CSS px:

- document and body scroll widths exactly equal the viewport width;
- no element extends outside the viewport and no link/button is below 44 × 44 px;
- one `main`, one `h1`, no console warning/error, and no failed request;
- keyboard order is skip link, wordmark, Материалы, Как это устроено, Мои колоды,
  account; activating the skip link produces `#paper-main` and focuses `paper-main`;
- with `prefers-reduced-motion: reduce`, computed styles contain no non-zero
  animation/transition duration and no smooth scrolling.

All final screenshots were visually inspected. The mobile layouts reflow without
cropping, the long Russian name starts visibly and ends with an ellipsis beside the
arrow, the 768 px layout remains a deliberate single column, and the 1440 px layout
uses the two-column composition. The focused skip-link capture shows its visible
indigo control and outline. These are screenshots from desktop Chrome viewport
emulation, not claims about a physical phone or tablet.

Screenshot evidence:

- `screenshots/paper-landing-320.png` — 320 × 5024;
- `screenshots/paper-landing-390.png` — 390 × 4833;
- `screenshots/paper-landing-768.png` — 768 × 4543;
- `screenshots/paper-landing-1440.png` — 1440 × 3880;
- `screenshots/paper-landing-390-skip-focus.png` — 390 × 844.

The bounded palette check is reproducible from `contrast-pairs.json`; the captured
result is `contrast-results.json`. All six foreground/background pairs pass WCAG AA
for normal text. Ratios range from 5.615:1 (muted text on paper) to 13.595:1 (paper
sheet text on indigo). This arithmetic check does not substitute for forced-colors,
screen-reader, or full-page accessibility testing.

Commands used after the visual fixes:

```sh
PATH=/tmp/mnema-143-resume/node22/node-v22.23.2-darwin-arm64/bin:$PATH \
  ./node_modules/.bin/ng build

PATH=/tmp/mnema-143-resume/node22/node-v22.23.2-darwin-arm64/bin:$PATH \
  node verify.mjs

python3 /Users/m.ryabushkin/.codex-home/plugins/cache/yuzuru-engineering/frontend-agent/0.1.0/skills/frontend-agent/scripts/contrast_check.py \
  docs/engineering/evidence/epic-74/editor/contrast-pairs.json

PATH=/tmp/mnema-143-resume/node22/node-v22.23.2-darwin-arm64/bin:$PATH \
  ./node_modules/.bin/eslint 'src/app/paper/**/*.ts' 'src/app/paper/**/*.html'

PATH=/tmp/mnema-143-resume/node22/node-v22.23.2-darwin-arm64/bin:$PATH \
  npm test -- --include='src/app/paper/*.spec.ts'
```

The final harness build, scoped ESLint, and five Karma specs exited 0. The existing
stale `baseline-browser-mapping` warning remains; no dependency was changed. Browser
zoom/text scaling, forced-colors rendering, Safari, Firefox, a manual keyboard pass,
screen readers, and real devices remain pending production integration. Editor
round-trip, paste, undo/redo, IME, ruby, RTL, unknown-node preservation, and long
document evidence also remain pending owner approval of the editor dependencies.
