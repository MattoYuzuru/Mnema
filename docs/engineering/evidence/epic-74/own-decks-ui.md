# Epic 74 own-decks UI implementation evidence

Date: 2026-09-12; integration updated 2026-09-19
Issue: #194
Checkpoint base: `a45435e577e7f6e25093e804398e6f1c6794663b`
Integration sources: own-decks `17044725c71ca7b5cc828bf9d200ea0f90f351d0`, browser Identity `8edcc50ec16b73399a5d65ff230b688ab9caadbf`

## Implemented boundary

- Replaced the legacy glass shell implementation with a small paper/indigo shell: one `main`, a skip link, visible keyboard focus, responsive wrapping, authenticated own-deck navigation, and no invented Study/editor destinations.
- Added standalone OnPush list, create, and detail pages ready for the main-owned lazy routes `/decks`, `/decks/new`, and `/decks/:deckId`.
- Added one typed Learning API client over `learningApiBaseUrl`, exact shared-fixture parsing, strong ETag/decimal-version handling, private/no-store checks, bounded opaque cursors, and exact metadata validation.
- Added a component-scoped Signals store. It rejects stale list/detail/write epochs, admits only one unresolved command, retains the exact command for an unknown-result retry, refreshes every replay acknowledgement with GET, and treats 412 as a two-choice conflict without discarding the draft.
- The shell awaits the #192 logout contract. A rejected server logout opens `/login`, where the auth slice owns the explicit unconfirmed/retry state; the shell does not claim revocation.

The 2026-09-19 integration wires the canonical guarded lazy routes, preserves the
Identity callback/runtime configuration, removes the superseded Deck route components
without aliases, replaces the fake data-fetching Home page, and maps global/themed
surfaces to the accepted opaque paper direction. Incompatible legacy Study/template
actions no longer navigate to removed Deck routes.

Dirty metadata and an unresolved exact command now survive navigation, reload and the
Identity 401 round trip in bounded tab-scoped `sessionStorage`. Recovery is versioned,
limited to five contexts/64 KiB/24 hours, bound to the verified `accountId`, rejects
malformed or cross-account state, and never stores a bearer credential. A recovered
command is not sent automatically; only the user's explicit retry replays it.

## Contract and UX details

- Title validation follows Java `String.isBlank`, 200 Unicode code points, and 800 UTF-8 bytes. Description is limited to 4096 UTF-8 bytes, and the serialized command envelope to 8192 bytes. Authored whitespace and line breaks are neither trimmed nor interpreted.
- Canonical UUIDv4/v7 commands and registered-version entity UUIDs accept either hex case and are normalized at the client boundary, matching the Java API.
- Fresh writes require the exact response ETag. Replays require `Idempotency-Replayed: true`, omit ETag, and cannot enter completed UI state until detail GET succeeds.
- Network/503 unknown outcomes offer only a same-command retry. Ordinary create/save admission cannot replace a pending, unknown-outcome, replay-refresh, conflict-refresh, or 409 command; 409 alone offers the visibly distinct new-command action. 412 loads the current server representation and requires an explicit server-version or reapply-my-prior-draft choice.
- Inputs are read-only while a command needs reconciliation, with the reason visible next to the recovery action. This prevents edited local text from being overwritten when an older command is retried. A new edit after a completed write clears the older acknowledgement.
- Title controls are compact two-row text areas because the accepted contract permits line breaks. A real DOM input regression proves that `\n` reaches the typed form value rather than being stripped by `input[type=text]`.
- Cursor navigation retains one server page (at most 20 Deck records) and a fixed window of at most 10 opaque cursors. Previous/next loads replace the visible page without detail N+1 or a full scan; refresh returns to the newest page after the retained back window is exhausted.
- Pages use static Angular interpolation, semantic headings/forms/lists, 44px controls, Georgia headings and system-sans reading/control text, visible focus, `overflow-wrap`, `white-space: pre-wrap`, and reduced-motion fallback. No private list prefetch or editor/media import was added.
- Owned CSS uses the accepted paper `#f4f0e5`, sheet `#fbf8ef`, ink `#281378`, body `#342e44`, muted `#625c70`, line `#c9c0ce`, and soft `#e8e1ed` tokens. The shell has no graph texture or sticky desktop header.

Angular's current [Signals guide](https://angular.dev/guide/signals), [typed reactive forms guide](https://angular.dev/guide/forms/reactive-forms), [HTTP testing guide](https://angular.dev/guide/http/testing), and [`takeUntilDestroyed`](https://angular.dev/api/core/rxjs-interop/takeUntilDestroyed) reference informed the local-state, form, transport-test, and route-subscription choices.

## Verification

Runtime:

```text
node v22.23.2
npm 10.9.8
Chrome Headless 153.0.0.0
```

Dependency restoration used the unchanged lockfile:

```text
PATH=/tmp/mnema-epic175-node22-recovery.a4W4JI/runtime/node-v22.23.2-darwin-arm64/bin:$PATH npm ci
PASS: 635 locked packages installed; package/lock files unchanged.
Observed existing audit state: 4 findings (2 low, 2 moderate); no audit fix or dependency change was made.
```

Failure-first/test progression:

```text
ng test ... --karma-config=/tmp/mnema-194-karma.conf.cjs ...
RED 1: compile rejected three untyped DOM queries.
RED 2: 20/22 passed; fixture literals and the deliberately cancellation-resistant transport double needed correction.
GREEN checkpoint: 22/22 passed.
GREEN after UUID/blank/logout regressions: 24/24 passed, latest 0.412 s browser execution.
REVIEW RED: 3/7 store cases demonstrated second UUID/request admission for pending, unknown, and replay-refresh create.
REVIEW RED: 3/7 component cases demonstrated editable unresolved drafts, false “not created” wording, and stripped title newline.
REVIEW RED: 100 cursor pages retained 2000 Deck rows instead of one 20-row page.
REVIEW GREEN: command admission/locking/title controls 15/15; bounded pager/store/list 12/12.
FINAL GREEN: 33/33 passed, 0.453 s total / 0.443 s browser execution, including protocol/5xx unknown-outcome classification.
```

The temporary Karma config changed only the task-local port to `9877` and reproduced Angular's built-in Jasmine/Chrome plugins. It is outside the repository.

Final narrow commands:

```text
PATH=...node-v22.23.2... ./node_modules/.bin/ng test --watch=false --browsers=ChromeHeadless \
  --karma-config=/tmp/mnema-194-karma.conf.cjs \
  --include='src/app/features/own-decks/**/*.spec.ts' \
  --include='src/app/core/layout/app-shell.component.spec.ts'
PASS: 33/33, 0.453 s total / 0.443 s browser execution.

PATH=...node-v22.23.2... ./node_modules/.bin/eslint \
  'src/app/features/own-decks/**/*.{ts,html}' \
  src/app/core/layout/app-shell.component.ts src/app/core/layout/app-shell.component.html
PASS: no findings.

PATH=...node-v22.23.2... npm run build
PASS: production build in 6.566 s; current-route initial raw 650.53 kB, estimated transfer 156.13 kB.
```

The component tests import `contracts/decks/metadata.json` directly. They cover byte/code-point/envelope boundaries, UUID/version/ETag parsing, exact requests and private response headers, replay rejection/refresh, pending and unknown-result admission, exact-command save/create retry, 412 draft preservation/reapply, stale list/write epochs, a 100-page memory bound, cursor back navigation, multiline title DOM input, unresolved-draft locking, failure wording, page states, shell landmarks/focus, and logout recovery.

Integration verification on 2026-09-19 used the unchanged dependency lockfile. The
final reproducible runs used Node `22.23.2`, npm `10.9.8`, JDK 21, Chrome
`154.0.8037.45`, PostgreSQL `18.6` on arm64 and the current local Docker runtime:

```text
npm run lint
PASS: all frontend files.

ng test --watch=false --browsers=ChromeHeadless
PASS: 137/137 after resetting the shared detail/mutation test signals between cases and
covering skip-link activation without nested-route loss.

npm run build
PASS: production build; 589.27 kB initial raw / 145.90 kB estimated transfer.
Own-deck list/create/detail are separate lazy chunks (11.49/15.05/18.92 kB raw).

./backend/gradlew -p backend :services:identity-account:bootJar :services:learning:bootJar
PASS.

python3 -m unittest scripts/browser-identity/test_fixture.py -v
PASS: 11/11 fixture-safety tests.

python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend \
  --node /opt/homebrew/opt/node@22/bin/node --timeout 300
PASS: real HTTPS Chrome, Identity, Learning and PostgreSQL composition; cleanup complete.
```

The final browser run covered registration/login PKCE, an empty own-deck library,
Unicode/RTL create, canonical detail, persisted reload, metadata save, two independently
authenticated same-account writer tabs, the real stale `412`, exact local-draft locking,
explicit reapply and final persisted read. It then rechecked authenticated reload, logout
revocation and shared-cookie account isolation. Results: 299 browser requests, 53 Identity
requests, four PKCE exchanges, zero external requests and zero JavaScript runtime errors.
The sanitized result is checked in as `own-decks-browser-results.json`; screenshots are
reproducibly generated by the harness and were inspected at 1440px, 390px and 320 CSS px
rasterized at DPR 2. The latter reflowed the long Russian title without horizontal overflow;
the 390px primary action remained at least 44px. Real keyboard events verified the visible
skip link, main-content focus and title-to-description order; emulated reduced motion
suppressed authored transitions. The harness exports only synthetic content.

## Explicit coverage boundary

- Store/API unit tests cover lost acknowledgement, exact-command retry, replay-refresh
  failure, forbidden/unauthorized/unavailable errors and bounded cursor state. The real
  browser run covers the success/reload/conflict product path; the existing real Learning
  security fixture independently covers cross-owner and fail-closed Identity behavior.
- Automated semantic/focus tests, real keyboard events and routed responsive captures do
  not substitute for a human VoiceOver/TalkBack run, physical touch device or real IME
  composition session.
  Those manual/device gaps remain explicit production-editor acceptance work in #202/#203,
  not a claim made by this metadata UI slice.
- No hosted deployment, staging availability or production rollout is claimed.
