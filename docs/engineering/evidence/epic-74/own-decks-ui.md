# Epic 74 own-decks UI implementation evidence

Date: 2026-09-12  
Issue: #194  
Implementation base: `a45435e577e7f6e25093e804398e6f1c6794663b`

## Implemented boundary

- Replaced the legacy glass shell implementation with a small paper/indigo shell: one `main`, a skip link, visible keyboard focus, responsive wrapping, authenticated own-deck navigation, and no invented Study/editor destinations.
- Added standalone OnPush list, create, and detail pages ready for the main-owned lazy routes `/decks`, `/decks/new`, and `/decks/:deckId`.
- Added one typed Learning API client over `learningApiBaseUrl`, exact shared-fixture parsing, strong ETag/decimal-version handling, private/no-store checks, bounded opaque cursors, and exact metadata validation.
- Added a component-scoped Signals store. It rejects stale list/detail/write epochs, admits only one unresolved command, retains the exact command for an unknown-result retry, refreshes every replay acknowledgement with GET, and treats 412 as a two-choice conflict without discarding the draft.
- The shell awaits the #192 logout contract. A rejected server logout opens `/login`, where the auth slice owns the explicit unconfirmed/retry state; the shell does not claim revocation.

No route, auth, runtime config, shared contract, dependency, global-style, backend, or CI file is owned by this slice. The visible main-owned changes to `app.config.ts` and `tsconfig.spec.json` were preserved.

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

## Remaining acceptance work (main-owned)

- Wire the three canonical lazy routes after #192 auth integration and remove the superseded route components in the owning integration diff; there are no aliases in this slice.
- Run the real Identity/Learning/PostgreSQL create → list → detail → save → reload flow, two-tab conflict, replay/unknown-result probes, and full exact-head repository gate.
- Capture actual routed 320/390/1440 and 200% browser evidence, keyboard order/focus, long Russian text, contrast, and reduced-motion behavior. No manual AT/IME result is claimed here.
- Re-measure the three own-deck lazy chunks after route wiring. The local production build validates the new shell, while unrouted feature pages are compiled and template-checked by the 33-test Karma build but intentionally do not appear in the current route bundle.
