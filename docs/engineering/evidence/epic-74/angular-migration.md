# Epic #74 Angular 18 → 22 migration evidence

Issue: #175. Branch: `epic-74/angular-22`. Rebased source boundary:
`700325c586212d0ec28a632b7e931d0591191501`.

Status: the bounded frontend migration is implemented on Angular 22 and its clean
frontend gate passes. The application build, its Docker copy directory, and its
third-party notice output are preserved at `frontend/dist/mnema-frontend`. A stale
webpack filename assertion was surfaced to the lead, fixed in main-owned root
scripts, and independently rerun successfully from this worktree.

## Environment and boundaries

- macOS arm64; Node 22.23.2 and npm 10.9.8 from
  `/tmp/mnema-143-resume/node22/node-v22.23.2-darwin-arm64/bin`.
- Owned changes are limited to `frontend/**` and this evidence file. No backend,
  editor, shared-contract, CI, root-license, production, GitHub, commit, branch, or
  deployment mutation was made.
- Existing Karma/Jasmine tools remain pinned. The stable `@angular/build:karma`
  builder is used; the experimental unit-test builder and Vitest migration were not
  adopted.
- No `--force`, `--legacy-peer-deps`, audit fix, coverage relaxation, or threshold
  change was used. `--allow-dirty` was needed because all official major migrations
  are accumulated in one reviewable diff.
- The MIT licenses of the approved Angular and lint packages do not change Mnema's
  license. No root licensing file was edited.

Official version-specific references used:

- [Angular version compatibility](https://angular.dev/reference/versions) for the
  Angular 22 / Node 22.22.3+ / TypeScript 6.0.x / RxJS 7 compatibility boundary.
- [Angular build-system migration](https://angular.dev/tools/cli/build-system-migration)
  for the webpack deprecation, official application builder, output layout, and
  migration schematic.
- [Angular change-detection API](https://angular.dev/api/core/ChangeDetectionStrategy)
  and [advanced component configuration](https://angular.dev/guide/components/advanced-configuration)
  for Angular 22's OnPush default and the Eager compatibility strategy.
- [angular-eslint rule documentation](https://github.com/angular-eslint/angular-eslint/blob/main/packages/eslint-plugin/docs/rules/prefer-on-push-component-change-detection.md)
  for the required deliberate review of Eager components.
- [Angular animations migration](https://angular.dev/guide/animations/migration),
  [`provideAnimations` deprecation](https://angular.dev/api/platform-browser/animations/provideAnimations),
  and [`platformBrowserDynamic` deprecation](https://angular.dev/api/platform-browser-dynamic/platformBrowserDynamic)
  for removing the unused packages/bootstrap provider.

## Baseline and major checkpoints

The Angular 18 baseline installed 1,204 packages and reported 85 pre-existing audit
findings. Its exact gate was:

```text
npm run lint                                      exit 0
npm test -- --no-progress                        exit 0; 51/51 SUCCESS
npm run build -- --no-progress                   exit 0; 1.68 MB raw / 289.59 kB transfer
```

Chrome Headless 152.0.0.0 was used for every browser-test checkpoint.

### Angular 19

```text
npx --yes @angular/cli@19.2.27 update @angular/core@19.2.25 @angular/cli@19.2.27
npx --yes @angular/cli@19.2.27 update @angular/cdk@19.2.19 --allow-dirty
npx --yes @angular/cli@19.2.27 update angular-eslint@19.8.1 --allow-dirty
npm install --save-dev --save-exact typescript@5.8.3
```

The first core/CLI attempt stopped before schematics because a shared npm cache had
an `EEXIST/EACCES` rename race. Its manifest-only output was reverted by patch and
the same command succeeded with the isolated task cache. Core removed redundant
`standalone: true` from 49 components. Three new
`@angular-eslint/no-output-native` findings were fixed by renaming internal outputs
and updating their bindings. Seven compiler-reported unused standalone imports were
removed.

```text
lint                                              exit 0
tests                                             exit 0; 51/51 SUCCESS
build                                             exit 0; 1.68 MB raw / 290.19 kB transfer
```

### Angular 20

```text
npx --yes @angular/cli@20.3.36 update @angular/core@20.3.30 @angular/cli@20.3.36 --allow-dirty
npx --yes @angular/cli@20.3.36 update @angular/cdk@20.2.14 --allow-dirty
npx --yes @angular/cli@20.3.36 update angular-eslint@20.7.0 --allow-dirty
npm install --save-dev --save-exact typescript@5.9.3
npx ng generate @angular/core:inject-migration --path src --defaults
```

The angular-eslint schematic installed 20.7.0, then failed in its task executor with
`Cannot find module '../package-manager/executor'`. It had changed direct `eslint`
and `typescript-eslint` pins to open ranges; those unapproved edits were reverted.
The official Angular `inject()` migration fixed 187 new `prefer-inject` findings.
Eight tests that directly constructed DI-managed types were updated to use
`TestBed.runInInjectionContext` with the same isolated spies/providers.

```text
lint                                              exit 0
tests                                             exit 0; 51/51 SUCCESS
build                                             exit 0; 1.69 MB raw / 292.22 kB transfer
```

### Angular 21

```text
npx --yes @angular/cli@21.2.23 update @angular/core@21.2.22 @angular/cli@21.2.23 --allow-dirty
npx --yes @angular/cli@21.2.23 update @angular/cdk@21.2.14 --allow-dirty
npx --yes @angular/cli@21.2.23 update angular-eslint@21.4.0 --allow-dirty
```

CLI/core raised the TypeScript library target, added explicit zone change detection,
and converted structural directives to block control flow. One NG8107 diagnostic
identified an unnecessary optional chain and was corrected. The rebased Angular 21
checkpoint on the current base was rerun immediately before starting Angular 22:

```text
npm ci                                           exit 0; 1,112 packages; 19 audit findings
npm run lint                                     exit 0
npm test -- --no-progress                       exit 0; 51/51 SUCCESS
npm run build -- --no-progress                  exit 0; 1.67 MB raw / 291.57 kB transfer
```

## Angular 22 migration

The first official core/CLI command stopped before changes because
angular-eslint 21.4.0 correctly peers only with CLI `<22`:

```text
npx --yes @angular/cli@22.1.7 update @angular/core@22.1.5 @angular/cli@22.1.7 --allow-dirty
exit 1: angular-eslint requires >=21.0.0 <22.0.0; CLI 22.1.7 would be installed
```

No peer check was bypassed. All already-approved Angular groups were instead passed
to one official update so npm could resolve a valid peer set and each package's
schematics could execute:

```text
npx --yes @angular/cli@22.1.7 update \
  @angular/core@22.1.5 @angular/cli@22.1.7 \
  @angular/cdk@22.1.5 angular-eslint@22.2.0 --allow-dirty
exit 0
```

Official migrations made these compatibility changes:

- CDK 22 reported no source changes.
- Core added `ChangeDetectionStrategy.Eager` to 49 existing components to preserve
  their pre-v22 behavior, added `withXhr()` where the XHR backend is observed, wrapped
  four affected safe-navigation expressions, and initially added global suppressions
  for the two safe-navigation/nullish extended diagnostics. Those suppressions were
  reviewed and removed from the final configuration as described below.
- CLI added current schematic naming defaults. Its automatic new direct
  `istanbul-lib-instrument` entry was removed because it was outside the approved
  direct set; the preserved Karma suite passes without it as a direct dependency.
- The approved exact TypeScript 6 / typescript-eslint pair was installed. Both
  `typescript-eslint@8.58.0` and its parser declare TypeScript `>=4.8.4 <6.1.0`, so
  TypeScript 6.0.3 is within the supported range, not merely accepted by npm.

The mandatory modern builder migration was then run:

```text
npx --yes @angular/cli@22.1.7 update @angular/cli \
  --name use-application-builder --allow-dirty
exit 0
```

`@angular-devkit/build-angular:browser-esbuild` was tested first but rejected as the
final state: Angular 22 and npm deprecate the owning build-angular package and its
webpack/Karma builders. The official schematic promoted the already-resolved
`@angular/build@22.1.7` MIT package from transitive to direct without introducing a
new package/version, selected stable `@angular/build:application` and stable
`@angular/build:karma`, and removed build-angular/webpack from the lock. Explicit
`outputPath.browser: ""` keeps browser artifacts directly in
`dist/mnema-frontend`; no SSR output is needed.

The exact final direct versions are:

```text
@angular/core/common/compiler/forms/platform-browser/router  22.1.5 (MIT)
@angular/cdk                                                 22.1.5 (MIT)
@angular/cli and @angular/build                              22.1.7 (MIT)
@angular/compiler-cli                                        22.1.5 (MIT)
angular-eslint                                               22.2.0 (MIT)
typescript-eslint                                            8.58.0 (MIT)
typescript                                                   6.0.3 (Apache-2.0)
rxjs                                                         7.8.2 (Apache-2.0)
tslib                                                        2.8.1 (0BSD)
zone.js                                                      0.16.3 (MIT)
eslint                                                       9.15.0 (MIT; preserved approved pin)
```

Unused `@angular/animations`, `provideAnimations()`, and
`@angular/platform-browser-dynamic` were removed. A clean lock and clean install
have no resolved package entries for animations, platform-browser-dynamic,
build-angular, or `@ngtools/webpack`. The lock necessarily records
`@angular/animations` only as platform-browser's optional peer declaration; npm does
not resolve or install that package (`npm explain` reports no matching dependency).

### Lock regeneration evidence

An initial lock-only refresh inherited the previously installed optional animations
peer. Regenerating lock-only with an empty `node_modules` exposed an npm 10.9.8
Istanbul inconsistency: the next `npm ci` rejected the lock because
`istanbul-lib-instrument@5.2.1` did not satisfy 6.0.3. That lock was rejected.
A normal clean `npm install` generated the final lock; a following `npm ci` succeeded
with the exact same graph. The final install has 635 packages and reports four
development-only audit findings; `npm audit --omit=dev` reports zero runtime
vulnerabilities. On Darwin, npm lists five optional WASM support packages as
`extraneous` because their `cpu: wasm32` parents are skipped while their optional
dependencies are still unpacked; they are lock-pinned development-only artifacts,
not direct dependencies, and `npm ls` exits zero.

### Angular 22 change detection and regression proof

Angular 22 defaults new components to OnPush. The official migration marked the 49
legacy components Eager because many still assign plain mutable fields inside RxJS
subscriptions; blanket conversion could leave asynchronous UI stale. angular-eslint
22 makes `prefer-on-push-component-change-detection` recommended, so its first run
reported exactly those 49 compatibility annotations.

The rule is not disabled globally. A scoped ESLint override enumerates exactly the
49 existing Eager component files, with an actionable #74 TODO to remove each
exception as those components are superseded. Effective-config verification reports
rule level `0` for an enumerated My Study component, but level `2` for both
`features/authoring/new.component.ts` and `shared/components/new.component.ts`.
New paper/authoring components therefore retain the Signals/OnPush rule even when
created in normal feature/shared directories. A new `MyStudyComponent` regression
test proves a representative plain field populated by delayed RxJS completion is
automatically rendered. Test count is now 52 (the original 51 plus this regression).

The migration-added global `nullishCoalescingNotNullable` and
`optionalChainNotNullable` suppressions were removed from both application and test
tsconfigs. The first unsuppressed build reported 11 NG8107 warnings, all in already
narrowed `@if` branches. Those expressions now use direct member access after their
guards; the nullable preflight-cost branch was rewritten with an explicit cost guard
and strict null/undefined checks. The final production build has no compiler
warnings, so no diagnostics suppression remains.

### Measured route loading

Before route splitting, the Angular 22 application builder produced:

```text
initial                                           1.63 MB raw / 279.45 kB transfer
```

Large feature pages now use standalone `loadComponent`; landing, login/register, and
small legal pages stay eager. This changes neither URLs nor guards. No eager prefetch
strategy was added because no navigation telemetry justifies downloading every lazy
feature after startup. Final output:

```text
initial                                           683.88 kB raw / 160.76 kB transfer
change                                            -58.0% raw / -42.5% transfer
lazy route chunks                                 33 emitted
largest lazy route                                deck-profile, 267.00 kB raw / 38.39 kB transfer
```

### Builder-visible Zone.js integration

The lead's first production-bundle browser smoke exposed a build-specific regression:
`/public-decks` remained in its loading state after the mocked HTTP 200 response and
the awaited icon-resolution step. The application still imported `zone.js` from
`main.ts`, but application-builder options declared an empty `polyfills` list.

The installed Angular 22.1.7 build source explicitly determines zoneless mode from
the builder `polyfills` option (`isZonelessApp`) and tells esbuild to retain native
async/await only for zoneless output (`getFeatureSupport`). An imperative source
import is therefore too late and invisible to this build decision. `zone.js` was
moved from `main.ts` to `build.options.polyfills`, while the explicit
`provideZoneChangeDetection()` provider remains.

The corrected production build emits a separate 35.88 kB `polyfills-*.js` entry.
The named `public-decks-catalog-component` chunk was inspected directly: it contains
the downleveled generator helper around `loadDecks`, `loadMore`, and
`resolveDeckIcons`, and contains zero native `async` or `await` tokens. Lint, all 52
unit tests, the warning-free production build, and the release/security contracts
pass after this correction. The lead owns the independent repeat of the built-browser
smoke that originally found the regression.

## Final frontend verification

All commands used the exact PATH and isolated npm cache described above. The final
clean-source sequence was:

```text
node --version                                    v22.23.2
npm --version                                     10.9.8
npm ci                                            exit 0; 635 packages; 4 dev audit findings
npm run lint                                      exit 0; all files pass
npm test -- --no-progress                        exit 0; 52/52 SUCCESS; Chrome Headless 152
npm run build -- --no-progress --verbose         exit 0; no warnings; 683.88 kB / 160.76 kB initial
public-decks emitted async assertion              exit 0; native async=false, await=false
npm audit --omit=dev                              exit 0; 0 vulnerabilities
manifest/lock exact-pin assertion                 exit 0
forbidden deprecated-package lock assertion       exit 0
third-party license-file assertions               exit 0
ESLint existing/new-file config assertions        exit 0; legacy=0, new feature/shared=2
git diff --check                                  exit 0
../scripts/test-frontend-release-contract.sh       exit 0
../scripts/test-browser-security-headers.sh        exit 0
```

The production build emits a non-empty 20,593-byte
`dist/mnema-frontend/3rdpartylicenses.txt` naming Angular, CDK, RxJS, tslib, and
zone.js with their license texts. Development-only typescript-eslint is not bundled;
its installed manifest independently reports MIT.

The frontend Dockerfile continues to copy `/app/dist/mnema-frontend` to nginx, and
the required `index.html`, hashed main/styles, lazy chunks, public assets, and license
file are all directly in that directory. The browser security configuration checks
for development/staging/prod also pass before reaching their stale asset assertion.

## Coordinated release seam

The initial run found that two root-owned scripts encoded webpack-specific entry
filenames and therefore failed against the official application builder despite the
correct output directory:

```text
../scripts/test-frontend-release-contract.sh
exit 1: Frontend entry asset is not content-hashed: main.js

../scripts/test-browser-security-headers.sh
config checks: development/staging/prod PASS
exit 1: hashed main bundle was not found
```

They expected `main.<16 hex>.js`, `styles.<16 hex>.css`, and a separate runtime file.
Angular's application builder emits content-hashed `main-EHNCGIPY.js`,
`polyfills-LVNOU2XZ.js`, `styles-VT65WWZU.css`, shared chunks, and no separate
runtime file. Its schema has no
supported webpack filename-template option. The lead updated the main-owned contract
helper and both scripts to validate the actual hashed `main`/`styles`/modulepreload
references, file existence, and cache headers without requiring a separate runtime.
This agent did not edit those root files. The rerun passed all ten helper unit
cases, `frontend_release_contract=ok`, all development/staging/prod config checks,
and hosted staging/prod security-header checks. Keeping deprecated webpack tooling
solely to satisfy the old assertions was not used as a workaround.

No frontend migration blocker is known. Full repository gating, independent review
of the lead-owned root-script delta, commit/push/PR actions, and any production
action remain with the lead.

## Lead built-browser verification (2026-09-12)

The checked-in [smoke harness](angular-browser/smoke.mjs) runs actual built assets
in isolated Chrome 152 contexts with Russian locale, reduced motion, and widths
1440/390. API and external assets are mocked; no production request is sent.
It uses an already installed external Playwright runtime, not a new app dependency.

```text
PLAYWRIGHT_MODULE=/absolute/path/to/playwright/index.mjs node \
  docs/engineering/evidence/epic-74/angular-browser/smoke.mjs \
  frontend/dist/mnema-frontend /absolute/path/to/evidence
```

[Recorded results](angular-browser/results/results.json) and desktop/mobile PNGs
in the same folder cover landing, protected-profile redirect to login with return
URL, identifier-to-password keyboard focus, and lazy public-decks empty-state
rendering without another click. Both widths complete without Angular runtime
errors. This repeats the real built-output test that found the Zone.js regression;
the helper now also tests that a Zone bootstrap declares builder-visible polyfills
and that exactly one hashed polyfills entry precedes main in the emitted index.
The reviewer visually inspected desktop public and mobile landing/login/public
captures. This is migration evidence, not acceptance of the old visual direction.

The same harness with `MNEMA_BASELINE_EAGER=1` was rerun against the Angular18
build at base `700325c`: document width is **1584 at viewport 1440**, and **390 at
viewport 390**, exactly matching Angular22. Desktop horizontal overflow is thus a
confirmed pre-existing shell defect, not a migration regression. The smoke enforces
no increase beyond this measured baseline. Owner/action: #74 paper-shell replacement
must remove that overflow and replace this temporary exception with a strict
viewport-width assertion. Desktop responsive acceptance remains incomplete.

Actual Identity flows, physical mobile devices, IME and screen-reader testing are
not claimed by this mocked smoke. The new paper/authoring UI and connected E2E
remain separate #74 work. The first entry transfer estimate falls from the Angular18
baseline 289.59 kB to 160.76 kB; it is a bundle estimate, not measured user latency.

## Hosted CI fixture registry failure

PR180's first hosted frontend job passed lint,52tests,build and all release/header
checks, but its final disposable purge rehearsal failed downloading the existing
MinIO fixture from Docker Hub (`pull access denied`, exit125). Local33-step prepush
and premerge runs on `eccfd49` passed because that exact image was already cached.

Only the test's registry changes to `quay.io/minio/minio`; the immutable index digest
`sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e`
is unchanged. Host HTTPS fetch plus SHA256 verification confirms the exact index,
whose arm64 and amd64 descriptors resolve on Quay. The vendor's
[version-specific README](https://github.com/minio/minio/blob/RELEASE.2025-09-07T16-13-09Z/README.md)
names that registry. No image version/license, app dependency, production registry,
CI gate or fixture behavior changes. This preserves a historical test fixture;
it is not a new recommendation to deploy the now-archived MinIO community server.

The local Colima daemon cannot reach Quay (TCP timeout), although host HTTPS can.
Task-scoped host download/OCI import must validate every manifest/config/layer digest
and size before local execution; no global network/proxy change or unverified image
substitution is acceptable. Corrected-candidate local and hosted gates are required
again; the earlier green local runs do not make the failed hosted job green.

The host-only recovery verified the original 969-byte index, the 2081-byte arm64
manifest `sha256:9966a92a734f9411e32f4f41d7d9d826fcdc0f68c4e20b70295bd4e7c11f8a2f`,
config and all 9 layer digests/sizes. A 57,579,520-byte partial-platform OCI archive
preserved the original index. `docker image load --platform linux/arm64` and an
exact Quay RepoDigest inspection passed. The inert `minio --version` reports
`RELEASE.2025-09-07T16-13-09Z`, AGPLv3. No shared container or network setting changed.
