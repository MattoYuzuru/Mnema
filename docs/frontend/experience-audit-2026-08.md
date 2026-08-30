---
artifact:
  id: experience-audit-2026-08
  type: frontend-review
  title: "Mnema frontend, UX and exercise audit"
  status: superseded-direction
  created_at: "2026-08-15"
  updated_at: "2026-08-30"
  owners: ["project-owner"]
  evidence_revision: "8e0c83d"
  assumptions:
    - "The public mnema.app build is representative of the checked-in frontend, but it exposes no build SHA."
    - "Authenticated screens were reviewed from source, not with a production account."
---

# Frontend, UX and exercise audit

## Decision

> **Resolution update (2026-08-30):** performance, accessibility and code-structure evidence below remains useful, but the proposed Focused Study Desk/Liquid Glass direction is rejected. Epic #74 owns a full visual reset; until separate owner design input, the baseline is minimal accessible Angular/semantic HTML/CSS. The old UI has no compatibility value.

Keep Angular. The slow/fragile behavior is explained by application choices: all feature routes load eagerly, static content waits for APIs, large components combine several responsibilities, modal/focus behavior is reimplemented and production cache headers are incompatible with unhashed bundles.

Keep the Angular/performance/a11y findings as implementation evidence. Angular upgrade, exercise platform and visual system still require separate reviewable tasks, but none must preserve v1 component boundaries or presentation.

## Evidence snapshot

One Lighthouse lab run of the anonymous landing page with simulated throttling produced:

| Metric | Result |
|---|---:|
| Performance | 64 |
| Accessibility | 93 |
| Best Practices / SEO | 100 / 100 |
| FCP / LCP | 4.58 s / 4.83 s |
| TBT | 189 ms |
| CLS | 0.00029 |
| JavaScript transfer | about 401 KiB |
| `main.js` unpacked | about 1.66 MiB |
| Estimated unused JS on landing | about 196 KiB / 50% |

This is a single lab sample, not field performance. Direct fetch had about 321 ms TTFB and 425 ms total HTML time. The LCP element was animated search-placeholder text, and the critical chain continued from HTML to `main.js` to `/api/core/decks/public`. On a correctly emulated 390 px viewport, no horizontal overflow was observed and CLS was negligible.

RUM, authenticated journeys, Safari/iOS, screen-reader behavior, 200% zoom, forced colors and low-power GPU paint cost remain unverified.

## Prioritized findings

### P0 — immutable caching with mutable asset URLs

The production Angular configuration does not enable output hashing ([angular.json](../../frontend/angular.json#L22)), while nginx caches all JS/CSS for one year as immutable ([nginx.conf](../../frontend/nginx.conf#L50)). The live site returned `main.js`, `runtime.js` and `styles.css` with this policy.

Impact: returning clients can keep an incompatible old application after deployment.

Acceptance criteria:

- production JS/CSS filenames contain content hashes;
- `index.html` and `app-config.js` are no-store or revalidated;
- hashed assets may be immutable;
- a deployment smoke resolves script names from the live HTML and verifies build identity.

### P0/P1 — review statistics cache is not user-scoped

`reviewStatsCache` is static and not keyed by identity ([home-page.component.ts](../../frontend/src/app/home-page.component.ts#L1014)); its read/write path retains values across component instances ([home-page.component.ts](../../frontend/src/app/home-page.component.ts#L1196)). Normal logout currently performs a hard reload, so the production leak was not reproduced through that path. The cache is nevertheless scoped incorrectly: an in-process identity transition such as expiry followed by local login can retain the prior user's due/new totals until TTL expiry.

Key all user-owned cache by stable user ID and clear it on auth identity change. Add a test that logs in as A, warms the cache, switches to B and never renders A's values.

### P1 — landing content waits for unrelated APIs

The home content below the global shell is behind `!loading` ([home-page.component.ts](../../frontend/src/app/home-page.component.ts#L26)); loading completes only after a `forkJoin` ([home-page.component.ts](../../frontend/src/app/home-page.component.ts#L1141)). Static hero content therefore waits for the public catalogue API and, for authenticated users, user/deck APIs. Review statistics load after this blocking phase.

Render the shell and value proposition immediately. Limit skeletons and error states to their data-owning sections. Remove the animated placeholder from critical rendering and respect reduced motion.

### P1 — every route is eager

All 21 component routes use static imports and `component`; the 22nd route entry is a wildcard redirect ([app.routes.ts](../../frontend/src/app/app.routes.ts#L1)). The anonymous landing bundle therefore includes admin, editors, review and AI dialogs. Angular recommends keeping the primary landing eager and lazy-loading other standalone routes in [Lazy-loaded routes](https://angular.dev/best-practices/performance/lazy-loaded-routes).

Move feature routes to `loadComponent` or lazy feature-route files, then set explicit initial/lazy bundle budgets. Verify route navigation and auth guards after every split.

### P1 — modal and drawer accessibility is structurally incomplete

The mobile drawer remains in the DOM and uses `aria-hidden` when closed ([app-shell.component.ts](../../frontend/src/app/core/layout/app-shell.component.ts#L162)). Opening it changes only a signal/body overflow ([app-shell.component.ts](../../frontend/src/app/core/layout/app-shell.component.ts#L1134)). Lighthouse reported focusable content under `aria-hidden`; manual keyboard testing showed focus remaining on the external trigger and Tab moving outside the declared dialog.

At least 14 feature files implement their own `.modal-overlay`. The shared confirmation dialog lacks complete dialog semantics, accessible naming, Escape and focus restoration ([confirmation-dialog.component.ts](../../frontend/src/app/shared/components/confirmation-dialog.component.ts#L10)).

Create one accessible dialog primitive using the already installed CDK or native `<dialog>`. It must move focus inside, contain Tab, support Escape according to action safety, restore focus, expose a name and respect reduced motion. This follows the [W3C APG modal dialog pattern](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/). Model the mobile drawer as either a real modal with the same contract or a non-modal navigation surface; do not mix both semantics.

### P1 — searches and long-running work have lifecycle bugs

- Public-card search filters only loaded pages and can stop prefetching on the first empty local result ([public-card-browser.component.ts](../../frontend/src/app/features/public-decks/public-card-browser.component.ts#L1175)). A later-page match can be reported absent. Use server search or explicitly state and correctly continue a loaded-only search.
- AI modals poll recursively with `setTimeout` and no destroy cancellation ([ai-enhance-card-modal.component.ts](../../frontend/src/app/features/decks/ai-enhance-card-modal.component.ts#L1166), [ai-import-modal.component.ts](../../frontend/src/app/features/decks/ai-import-modal.component.ts#L1502)). Closing a modal can leave callbacks/requests alive. Move job observation to a job store or use `timer`/`switchMap`/`takeUntilDestroyed`; stop MediaRecorder and timers on destroy.

### P1 — unsupported Angular line and known dependency findings

The repository pins Angular 18.2.14 ([package.json](../../frontend/package.json#L10)). Angular's [release policy](https://angular.dev/reference/releases) lists 22 as active, 20/21 as LTS and 2–19 as unsupported on the audit date. `npm audit --package-lock-only --omit=dev` reported eight high-severity production dependency findings; the pinned Angular version falls in affected ranges for, among others, the official GitHub advisories [GHSA-g93w-mfhg-p222](https://github.com/advisories/GHSA-g93w-mfhg-p222) and [GHSA-jrmj-c5cx-3cw6](https://github.com/advisories/GHSA-jrmj-c5cx-3cw6).

This establishes upgrade urgency, not a demonstrated exploit against Mnema. Upgrade one major at a time `18→19→20→21→22`, run official migrations and the full quality gate at every step. Align CI/container Node to a supported compatible release. Do not change dependency files as part of this audit; AGENTS.md requires an explicit proposal and permission.

The current webpack `browser` builder is also legacy ([angular.json](../../frontend/angular.json#L10)). Follow Angular's [build-system migration](https://angular.dev/tools/cli/build-system-migration) to the `application`/esbuild builder during the staged framework upgrade.

## Maintainability and state

All 50 components are standalone, Signals are already used in the shell/theme and shared CSS tokens exist. These are good foundations. A framework rewrite would discard them without addressing the actual bottlenecks.

The main maintainability risk is large inline components:

| Component | Approximate lines |
|---|---:|
| `deck-profile.component.ts` | 3,147 |
| `ai-import-modal.component.ts` | 2,401 |
| `ai-enhance-deck-modal.component.ts` | 1,923 |
| `ai-enhance-card-modal.component.ts` | 1,763 |
| public/private card browsers | 1,669 / 1,554 |
| `home-page.component.ts` | 1,483 |
| `app-shell.component.ts` | 1,148 |

Split by responsibility, not arbitrary line count:

```text
feature route/page
├── page shell and sections
├── feature facade/store
├── API/job orchestration
└── shared infrastructure primitive
```

Avoid a universal component or global store. Keep ephemeral UI state with its feature; centralize only true cross-route concerns such as auth identity, active jobs, dialog behavior and persistence policy.

Other confirmed cleanup targets:

- replace `.toPromise()` media calls with `firstValueFrom`;
- introduce `OnPush`/signal-friendly boundaries before evaluating zoneless;
- remove `provideAnimations()` if unused or migrate to native CSS entry/exit during the framework upgrade; Angular now marks it deprecated in [provideAnimations](https://angular.dev/api/platform-browser/animations/provideAnimations);
- make locale a signal and split the 2,479-line eager dictionary after route splitting;
- add stable tracking and windowing/virtualization for large card lists;
- update `document.documentElement.lang` when language changes;
- connect input errors with `aria-invalid` and `aria-describedby`;
- add a skip link, heading-order tests, forced-colors and global reduced-motion behavior.

### Local persistence

Drafts and preferences are written directly to `localStorage` in many features. AI drafts can contain prompts, notes, provider credential IDs and generation settings, while logout clears auth state but not every draft ([auth.service.ts](../../frontend/src/app/auth.service.ts#L121)).

Introduce a small typed `DraftStore` contract:

```text
{ schemaVersion, ownerId, entityId, updatedAt, expiresAt, payload }
```

It owns migration, quota errors, TTL and identity-change cleanup. Keep small preferences in local storage. Offline deck review and native clients are now an accepted future requirement, but their immutable manifests, media packs and pending attempt log belong in a separately versioned IndexedDB/native persistence adapter, not in ad-hoc `localStorage` keys.

## Native editor and content rendering

V2 removes the template/field builder. The frontend needs two registries with stable domain contracts:

```text
LearningItemEditorShell
├── desktop editor + live preview
├── mobile editor/preview surfaces
├── quick text/voice/AI draft entry
└── DocumentNodeEditorRegistry

LearningItemRenderer
└── DocumentNodeRendererRegistry
    ├── text/ruby/bidi
    ├── math/code/Mermaid
    ├── image/audio/video
    └── unknown-node safe fallback
```

The persisted JSON contract belongs to Mnema, not to an Angular component. Render native typed nodes; never compile user Angular templates or pass the complete document through `bypassSecurityTrustHtml`. Markdown is an authoring/interchange projection and unsupported rich nodes must survive source/preview switching. The editor dependency is chosen only after an IME/ruby/RTL/mobile/a11y/large-document spike and explicit permission to modify `package.json`. See [native content format](../architecture/learning-content-format-v2.md).

## Visual direction

No visual direction from this 2026-08-15 audit remains active. Liquid Glass, Focused Study Desk and preservation of the broad v1 identity are explicitly out. A later #74 design refinement will define the new direction; a plain high-contrast CSS baseline is acceptable meanwhile. The measured concern around global blur/`will-change` remains evidence for deleting the old `.glass` styling rather than tuning it ([global_styles.css](../../frontend/src/global_styles.css#L255)).

## Exercise platform

The current session is hard-coded as prompt → reveal → `AGAIN/HARD/GOOD/EASY` ([review-session.component.ts](../../frontend/src/app/features/decks/review-session.component.ts#L78)); the backend answer contract captures rating and response time, not an exercise attempt ([review.models.ts](../../frontend/src/app/core/models/review.models.ts#L29)).

Use a bounded registry, not a plugin/microfrontend framework:

```text
StudySessionShell
├── queue, progress, retry and attempt idempotency
├── ExercisePolicy
│   └── chooses a supported exercise from content + mastery
└── ExerciseRendererRegistry
    ├── reveal
    ├── typed-recall
    ├── cloze
    ├── multiple-choice
    ├── listening
    └── ordering
```

Contracts:

- `ExerciseDefinition` is a discriminated union with kind, prompt blocks, answer specification, hints and accessibility metadata.
- A renderer is a focused standalone component that emits one standard `ExerciseAttempt`.
- `ExerciseAttempt` includes `attemptId`, kind, answer/correctness evidence, response time and hints used.
- A scoring adapter maps evidence to scheduler rating; a correct multiple-choice guess must not automatically equal `EASY`.
- Prompt/reveal projections and answer specs reference stable document node IDs and computed content capabilities. Exercise logic must not depend on user-visible field labels or a fixed front/back shape.
- Every renderer supports semantic form/radiogroup controls, text-input-safe shortcuts, `aria-live` feedback, reduced motion and a non-gesture alternative.

Implementation sequence:

1. Build the new `StudySessionShell` against the greenfield attempt contract and delete the replaced reveal path; do not wrap it for compatibility.
2. Add typed recall and real cloze.
3. Add multiple choice as an error scaffold with deterministic distractor rules.
4. Add listening only when an audio capability is present.
5. Add ordering/image-specific modes only after attempt analytics validates need.

The session is always scoped to the deck from which it was launched; routing never mixes another deck. The domain persistence and scheduler split are proposed in [content-platform-v2.md](../architecture/content-platform-v2.md#study-state-and-exercises), with the broader mechanics in [exercise catalog](../product/exercise-catalog-v2.md).

## Test contour

The repository has 18 frontend spec files and no E2E suite, coverage threshold, accessibility gate or visual regression gate. The first test contour should cover:

1. anonymous landing/catalog independent of failed API sections;
2. auth redirect and identity-switched cache/drafts;
3. deck browse and current reveal review;
4. dialog/drawer focus, Escape and restore behavior;
5. public server search/pagination;
6. AI job cancellation on component destroy;
7. renderer contract and session state machine;
8. route smoke after lazy splitting.
9. native document fixtures for ruby/RTL/math/code/Mermaid/media/unknown nodes and mobile editor-preview draft retention.

## Delivery slices

### Slice 1 — correctness and reachability

- bundle hashing/cache policy;
- user-scoped cache and draft cleanup;
- immediate static landing render;
- drawer/dialog focus behavior;
- cancellable AI polling;
- correct public search.

### Slice 2 — frontend infrastructure

- lazy routes and bundle budgets;
- reproducible Docker build;
- accessible Dialog, DraftStore and AI Job primitives;
- list tracking/windowing;
- language, reduced motion and form linkage;
- split the first two largest feature components by responsibility.

### Slice 3 — modernization and learning platform

- sequential Angular/framework/build migration;
- state/change-detection audit, then selective signals/zoneless work;
- StudySessionShell and attempt contract;
- native editor/preview shell and typed document renderer registry;
- typed recall/cloze, then measured exercise additions;
- public-page prerender only if acquisition/SEO becomes a validated goal.

## Explicitly deferred

- React/Vue/Svelte rewrite;
- universal microfrontend/plugin runtime for exercises;
- broad signal rewrite for syntax alone;
- choosing the final visual direction without separate owner design input;
- SSR for authenticated study routes;
- animation-heavy gamification before retention evidence.
