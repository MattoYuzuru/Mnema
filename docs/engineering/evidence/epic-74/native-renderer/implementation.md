# Native renderer baseline — implementation evidence (#183)

Date: 2026-09-12
Candidate base: `49e62f3` (`main` `788546a` plus native contract `a766097`)
Runtime: Node `22.23.2`, npm `10.9.8`, Angular `22.1.5`, Angular CLI/build `22.1.7`, Chrome Headless `153.0.0.0`

## Outcome and boundary

`NativeDocumentRendererComponent` is a standalone, OnPush, read-only renderer for the
11 supported native-v1 types. It accepts the shared semantic `NativeDocument` with a
required signal input, prepares one memoized render model per immutable input, and
uses only static Angular templates. It has no route, API, editor, persistence, media,
study, or legacy card/template binding in this slice.

The component emits an `article`, never a `main` or `h1`; its host screen owns those.
Native heading levels 1–5 map to `h2`–`h6`. Native level 6 uses `role="heading"` and
`aria-level="7"`, preserving the final relative level below the screen h1 instead of
collapsing it into level 6. WAI ARIA12 documents level 7 as the fallback beyond HTML's
six native ranks, while also requiring target browser/assistive-technology testing.

The scoped visual treatment follows the accepted quiet paper direction: cream sheet,
indigo headings and rules, book typography, almost-square edges, no glass, animation,
remote font, image, or decorative network dependency. Content wraps rather than
truncates. Print exposes link destinations and removes surface decoration.

## Rendering and security controls

- `doc`, `paragraph`, `heading`, `blockquote`, `bullet_list`, `ordered_list`,
  `list_item`, `text`, `ruby`, `link`, and `divider` map to semantic HTML; ordered-list `order`, exact
  `lang`/`dir`, ruby base/reading, and source mark order are retained.
- Known version-1 nodes are shape-checked before any partial DOM is emitted. Invalid
  known content produces one fixed failure state without reflecting private values.
- Unknown types and future node versions produce one fixed block/inline/list-item placeholder.
  Their attrs and descendants never enter the render model and are not traversed,
  reflected, interpreted, fetched, or executed. A future root produces one document
  placeholder. Opaque children of `ul`/`ol` remain semantic `li` elements, so ordered
  numbering and list structure are not broken. Messages make no claim about whether
  unsupported content was persisted.
- Text uses interpolation. There is no `innerHTML`, generated Angular template,
  direct DOM manipulation, `DomSanitizer` bypass, resource URL, iframe, form, media
  request, or renderer-side HTTP.
- Authored text boundaries are exact in the DOM: adjacent marked text does not gain
  template whitespace, ruby base text is not padded or trimmed, and
  `white-space: pre-wrap` preserves authored repeated spaces and newlines while still
  wrapping.
- A link becomes an `href` only after the bounded native HTTPS profile accepts its
  lexical form. Tests import the canonical native-v1 accept/reject JSON directly,
  including user-info, malformed ports, alternate IPv4, Unicode input, backslash,
  percent escape, IPv6
  zone, JavaScript and malformed A-label rejection. Valid expanded and IPv4-mapped
  IPv6 plus DNS names whose final label merely ends in a digit are accepted. The
  exact source `href` is retained rather than replaced by the browser-normalized URL.
  Punycode is decoded and passed through browser host re-encoding; the server reader
  remains the canonical Java-21 semantic validator.
- Renderer work is independently bounded to 10,000 visited render nodes, depth 32,
  32 KiB UTF-8 per inspected scalar, and 1 MiB total inspected/visible UTF-8 scalar
  data. Exact node/depth limits remain accepted. An opaque payload is not serialized
  or walked merely to account for it. These are DOM/work controls, not a claim that
  the browser duplicates the server's complete raw/canonical JSON validation.
- UUID spelling and optional source metadata are not changed; the input object is
  not mutated or cloned. Duplicate visible IDs are rejected case-insensitively.

Angular's current security guidance determined the static-template/interpolation
boundary: Angular treats bound data as untrusted, warns against generated templates
and direct DOM APIs, and reserves bypass APIs for explicitly trusted executable
contexts. Signal input and OnPush follow the current Angular component APIs.

## Verification

All commands ran from `frontend/` with:

```sh
export PATH=/tmp/mnema-epic175-node22-recovery.a4W4JI/runtime/node-v22.23.2-darwin-arm64/bin:$PATH
```

| Command | Result |
|---|---|
| `node --version`; `npm --version` | `v22.23.2`; `10.9.8` |
| `npm ci` | PASS, 635 packages from existing lock; no dependency or lock change. npm reported the pre-existing 2 low/2 moderate audit findings. |
| `PORT=9877 npm test -- --include='src/app/content/rendering/*.spec.ts'` | PASS, final 22/22 in Chrome Headless 153 on an isolated Karma port. |
| `PORT=9877 npm test` | PASS, final full frontend suite 74/74, including all 22 renderer tests. |
| `npm run lint` | PASS, all files. |
| `npm run build` | PASS, production build. Initial 683.88 kB raw / 160.76 kB estimated transfer. |
| frontend-agent `contrast_check.py` | PASS: body/paper 12.205:1, ink/paper 13.595:1, muted/paper 6.021:1. |

The retained Chrome test rendered an exact 10,000-node document (root plus 9,999
empty paragraphs) with no truncation; the final full-suite run measured prepare plus
DOM time at 127.60 ms on this host. This is one local observation, not a service-level budget.
Tests also exercise exact depth 32, over-limit failure, the canonical mixed
multilingual corpus, nested mark order, exact text/ruby whitespace, RTL, ordered
start, semantic opaque list slots, keyboard focus, hostile text/URLs, opaque payload
getters, and fixed failure states. `frontend/tsconfig.spec.json` has main-owned
`resolveJsonModule: true`, which keeps these JSON imports test-only; TypeScript's
official option reference confirms that it enables JSON module imports and derives
their static shape.

Real Chrome layout assertions found no component overflow at 320, 390, or 1440 CSS px,
including a 512-character unbreakable token. At 320 px, changing the actual root font
from 16 px to 32 px increased the computed article font from 16.65 px to 32.17 px and
still fit; CSS `zoom: 2` produced a measured 640 px visual frame from its 320 px CSS
width and did not introduce component overflow. The allowed link remained a native
focusable, underlined anchor. There is no motion to suppress. Forced-colors and print
overrides are present. These automated checks are not screenshots or
assistive-technology/device claims.

The renderer is intentionally not imported by a production route yet, so the
production optimizer tree-shakes it and current shipping route bytes do not increase.
The Karma-only component spec bundle includes the canonical JSON corpus and test
module compilation, so it is deliberately not used as a prediction of the later lazy
production chunk.

### Failure-first history

The first targeted run completed 12/14. One assertion had not trimmed Angular template
whitespace around safely escaped text. The other showed that the browser URL parser
alone accepted two malformed shared A-label vectors. The assertion was corrected and
the URL boundary gained bounded Punycode decode/browser re-encode validation; the
subsequent 14/14, 16/16, and 17/17 targeted runs passed, followed by the then-current
70/70 full run containing 18 renderer tests. The initial lint invocation
could not find `ng` because this new worktree had no `node_modules`; locked installation
resolved the environment without changing dependencies.

The independent-review regressions were then added before the fixes. The executable
red run completed 16/22: expanded/mapped IPv6 and digit-ending DNS were rejected,
template whitespace changed exact text/ruby boundaries, authored whitespace computed
as `normal`, opaque list children rendered as `div`, and unsupported messages claimed
persistence. That run also exposed one mistaken corpus expectation in the new test;
after the implementation fixes, the misplaced expectations were corrected against
`valid/mixed.json`. The final targeted run completed 22/22. An attempted
`--port=9877` invocation failed before compilation because Angular's Karma builder has
no `port` option; `PORT=9877` is the working Karma configuration and all reported test
runs use it.

## Changed artifacts

- `frontend/src/app/content/rendering/native-document-renderer.component.ts`
- `frontend/src/app/content/rendering/native-document-renderer.component.html`
- `frontend/src/app/content/rendering/native-document-renderer.component.css`
- `frontend/src/app/content/rendering/native-render-state.ts`
- `frontend/src/app/content/rendering/native-document-renderer.component.spec.ts`
- `frontend/src/app/content/rendering/native-render-state.spec.ts`
- `frontend/src/app/content/rendering/native-renderer.fixtures.ts`
- this evidence file

No shared contract, dependency, route, CI, backend, environment, Git history, or
external system was changed by the renderer lane. The main-owned, test-only
`frontend/tsconfig.spec.json` flag described above is a supporting change outside this
lane's artifact ownership.

## Residual verification and integration work

### Component visual follow-up

The lead checked the original #183 screenshot acceptance before merge. An isolated
production Angular component harness now supplies [five real Chrome captures and
reproduction sources](./visual/README.md):320/390/1440, keyboard focus and invalid
state. All viewport/semantic/overflow assertions passed; the lead inspected the
images and matched production source hashes. An initial focus harness also called
`.focus()` and was rejected as confounded keyboard evidence. That hook was removed
and all captures rerun: one real CDP Tab alone focuses the exact anchor. No production
route was added; route screenshots, physical devices, real AT and IME remain separate.

Independent rereview after the fixes: no confirmed blockers for this renderer-only
slice. A separate reviewer ran the 22 renderer tests on Node 22.23.2 / Chrome 153,
port 9877, with exit 0, and confirmed the exact whitespace, list, URL and scaling
regressions. Its 10,000-node observation was 126.70 ms; this does not establish an SLO.
The lead read all source, tests and evidence and matched the reviewed source hashes.
This is agent review evidence, not a separate human GitHub approval.

- The owning route must supply a server-validated immutable document and retain the
  screen-level `main`/`h1` contract. API loading/error/retry and authorization remain
  outside this renderer.
- Validate the level-7 heading fallback with the supported VoiceOver/Safari and
  TalkBack/browser matrix before product release. No real IME or assistive-technology
  session was run here.
- Capture route-level screenshots and measure the actual lazy production chunk only
  after the renderer has a real reader route; fabricating a temporary product route
  would not verify the intended integration.
- Full repository/backend/container gates and protected delivery belong to the lead.

## Sources

- [Native document v1 contract](../../../../../contracts/content/native-v1/README.md)
- [Native LearningItem content format](../../../../architecture/learning-content-format-v2.md)
- [Accepted paper experience direction](../../../../frontend/design-and-experience-2026-09.md)
- [Angular security](https://angular.dev/best-practices/security)
- [Angular signal inputs](https://angular.dev/guide/components/inputs)
- [Angular ChangeDetectionStrategy](https://angular.dev/api/core/ChangeDetectionStrategy)
- [TypeScript `resolveJsonModule`](https://www.typescriptlang.org/tsconfig/resolveJsonModule.html)
- [WAI ARIA12 heading technique](https://www.w3.org/WAI/WCAG21/Techniques/aria/ARIA12)
