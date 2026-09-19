# Epic #74 dependency decisions

2026-09-06. Status: all three dependency proposals approved by the owner in chat.
Package metadata was read from the npm registry. Implementation may now install
the scoped versions below; unrelated dependency changes still require approval.

## Editor research #172

Recommend direct ProseMirror for an isolated Angular adapter prototype:

| Direct package | Exact version | License |
|---|---|---|
| prosemirror-model | 1.25.11 | MIT |
| prosemirror-state | 1.4.4 | MIT |
| prosemirror-view | 1.42.3 | MIT |
| prosemirror-commands | 1.7.2 | MIT |
| prosemirror-history | 1.5.0 | MIT |
| prosemirror-keymap | 1.2.3 | MIT |
| prosemirror-schema-list | 1.5.1 | MIT |

Resolved transitive set: prosemirror-transform 1.12.1, orderedmap 2.1.1,
rope-sequence 1.3.4, w3c-keyname 2.2.8; all MIT. Lock exact resolved versions.

Platform contenteditable does not supply the required schema, transactions,
selection mapping, history and browser composition handling. ProseMirror has no
Angular wrapper/peer dependency and permits a Mnema-owned native adapter. Alternative:
Tiptap core/pm/starter-kit 3.31.3 (MIT), with more extensions and a community Angular
integration; it still needs a lossless Mnema adapter and unknown-node handling.

Risks to test before adoption: native text-node IDs versus PM text coalescing,
composition/IME, mobile selection, paste sanitation, undo/redo and unknown nodes.
Do not persist PM state. `prosemirror-view` 1.42.3 fixes the
[paste XSS advisory](https://github.com/ProseMirror/prosemirror-view/security/advisories/GHSA-c8x8-7fp4-3x9w).
[ProseMirror guide](https://prosemirror.net/docs/guide/),
[Tiptap installation](https://tiptap.dev/docs/editor/getting-started/install),
[Tiptap schema](https://tiptap.dev/docs/editor/core-concepts/schema).

## Separate Angular upgrade

Angular 18.2.14 is unsupported. Recommend current stable Angular 22 with official
one-major-at-a-time migrations in a separate child/PR, before authoring integration.
An alternative is Angular 21 LTS, but it does not meet the requested latest-stable
target; staying on 18 leaves the unsupported platform in place.

| Step | Runtime + compiler-cli | CLI + build-angular | CDK | angular-eslint | TypeScript | zone.js |
|---|---|---|---|---|---|---|
| 19 | 19.2.25 | 19.2.27 | 19.2.19 | 19.8.1 | 5.8.3 | 0.15.1 |
| 20 | 20.3.30 | 20.3.36 | 20.2.14 | 20.7.0 | 5.9.3 | 0.15.1 |
| 21 | 21.2.22 | 21.2.23 | 21.2.14 | 21.4.0 | 5.9.3 | 0.16.3 |
| 22 final | 22.1.5 | 22.1.7 | 22.1.5 | 22.2.0 | 6.0.3 | 0.16.3 |

Runtime packages: common, compiler, core, forms, platform-browser,
platform-browser-dynamic and router. Final rxjs 7.8.2 and tslib 2.8.1.
Angular/CDK/CLI/build tooling, angular-eslint and zone.js are MIT; TypeScript/RxJS
are Apache-2.0; tslib is 0BSD. Existing test tools remain unless a separately
documented compatibility requirement needs another approval.

Remove unused `@angular/animations` and `provideAnimations()` rather than introduce
a replacement animation dependency. Its only source usage is bootstrap registration;
the package is deprecated. Review migrations for builder/configuration and TypeScript
6 diagnostics. Run lint/tests/build per step and the entire repository gate on the
final candidate. Migration-resolved transitive dependencies belong in the reviewed
lockfile; no unrelated dependency refresh is authorized by this proposal.

Use the already installed Node 22.23.2/npm 10.9.8, matching CI and all migration steps.
Local default Node 26.3.0 is incompatible with Angular 18 although supported by 22.

[Angular compatibility](https://angular.dev/reference/versions),
[release/update policy](https://angular.dev/reference/releases),
[CLI update](https://angular.dev/cli/update),
[animation migration](https://angular.dev/guide/animations/migration).

## Owner answers

- ProseMirror isolated prototype: approved, not yet final editor adoption.
- Full staged Angular 18→22 migration: explicitly approved. Latest-stable Angular
  was already a product target; this approval covers the concrete dependency work.
- Learning resource-server and security-test additions: explicitly approved.
- After clarification, the owner approved backend/PostgreSQL immutable blocks/pages,
  normalized FK edges and physical fragmentation of large native text nodes.
  The measured alternative and tradeoffs remain in the storage research report.

## Additional migration prerequisite — approved

The official angular-eslint 20 migration attempted to change direct ESLint and
typescript-eslint pins. Existing ESLint 9.15.0 satisfies its peer range and remains
unchanged. Existing typescript-eslint 8.16.0 does not support the final TypeScript 6.
Recommend exact `typescript-eslint:8.58.0` (MIT), the first release admitting TS6
(`>=4.8.4 <6.1.0`); alternative 8.69.0 (MIT) is newer but not the minimal migration.
The owner explicitly approved `typescript-eslint:8.58.0` in the latest chat answer.
Earlier unapproved schematic pin changes were reverted; Angular 21 lint/51 tests/
build passed before resuming Angular22/TS6 with this approved pin.
Sources: [typescript-eslint supported versions](https://typescript-eslint.io/users/dependency-versions/)
and official npm package metadata. No unrelated lint-tool update is proposed.

Third-party MIT notices must be preserved in distribution. These dependencies do
not relicense Mnema's independent source: the root LICENSE/NOTICE remain unchanged,
including their explicit historical Apache-2.0 exception.

## Learning authentication

Learning currently has no authentication library. Private authoring requires a
resource-server boundary. Recommend adding only to `services:learning`:

- `org.springframework.boot:spring-boot-starter-oauth2-resource-server:3.5.16`
  (Apache-2.0), managed by the already selected Boot BOM; Spring Security modules
  resolve to 6.5.11 (Apache-2.0), matching Identity.
- Test-only `org.springframework.security:spring-security-test:6.5.11`
  (Apache-2.0), for real filter-chain token/scope/401/403 tests.

No platform version upgrade. Existing web/JDBC packages do not verify OAuth tokens.
Alternative: explicit Security core/web/config/oauth2 modules at the same versions
instead of the Boot starter, increasing configuration burden; a hand-written
JWT/authentication stack is rejected because it repeats security-sensitive framework
behavior. Per-request active-generation/grant verification must consume an Identity
HTTP contract; local signature validation alone does not prove revocation. The
integration slice will test wrong issuer/audience/type/scope, revoked tokens,
timeouts and no private operation after a failed authorization check. It must not
query Identity tables or rewrite the Identity backend.

Risks: security default-chain changes, Identity availability and token-revocation
boundary. Configure routes/errors/timeouts explicitly and verify existing health
checks. [Spring Security 6.5 JWT resource server](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html).
Approval: explicitly granted by the owner on 2026-09-06.
