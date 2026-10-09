# Mnema — Agent Operating Contract

Mnema is a greenfield learning platform built around versioned `LearningItem`s, multiple
exercise types and spaced practice. The v1 flashcard implementation (removed in #146, kept in
the `v1-apache-final` tag) is replacement input, not an architecture to preserve.
Backend: Spring Boot 4.x, Java 25, prefer Virtual Threads where appropriate (I/O-heavy
concurrency), modern Java (records, pattern matching, sealed types). Kotlin only in places
consistent with the existing codebase (follow current package/module boundaries).
Frontend: Angular (latest stable), standalone components, Signal-based state (signals,
computed/effect where idiomatic), a11y-first UI.

This file owns repository-wide instructions. Read `backend/AGENTS.md` or
`frontend/AGENTS.md` before work in that subtree; hosts differ in automatic discovery.
Detail lives in linked docs. Full wording of the
generic rules summarised below: [`docs/engineering/engineering-standards.md`](docs/engineering/engineering-standards.md).

## Where to look (read on demand)

| Need | Source |
|---|---|
| Any doc, with status (current / accepted / proposed / historical / superseded) | [`docs/README.md`](docs/README.md) |
| What is true for product, mechanics, contracts, UI, delivery; glossary | [`docs/engineering/domain-truth-map.md`](docs/engineering/domain-truth-map.md) |
| Run, build, test, local stack; machine setup (JDK 25, Colima, Node 24) | [`docs/engineering/agent-runbook.md`](docs/engineering/agent-runbook.md) |
| Modules, versions, change routes | [`docs/engineering/repository-guide.md`](docs/engineering/repository-guide.md) |
| Issues / PRs | [`docs/engineering/work-item-standard.md`](docs/engineering/work-item-standard.md) |
| Backend / frontend scoped rules | `backend/AGENTS.md`, `frontend/AGENTS.md` |

Business rules come from the canonical docs and `contracts/`, never from v1 code, old
docs or historical evidence (those are read-only history, not current behaviour).

## Hard constraints

- **Freshness:** never use deprecated APIs, legacy patterns or abandoned libraries; match the
  exact versions in use (Java 25, Spring Boot 4.x, Angular latest stable); if unsure whether an
  API/library is current, verify via official docs.
- **Greenfield rewrite:** do not add `/v2` routes, dual reads/writes, compatibility adapters or
  wrappers around v1 product code. Replace the canonical path directly and delete superseded
  code within the owning epic; temporary product downtime and incomplete product flows are
  acceptable during the rewrite.
- **Conventions:** preserve only conventions and modules that still fit the accepted target.
  “Match nearby code” is not a reason to reproduce legacy deck/card/template, service or
  scheduler boundaries. Before writing code, scan nearby code for naming, folder structure,
  module boundaries, error handling, logging format and testing conventions.
- **Design:** follow SOLID, GRASP, Clean Code; avoid overengineering — the simplest solution
  that is correct, scalable and maintainable.
- **Dependencies:** never modify `package.json`, `pom.xml`, `build.gradle` unless absolutely
  required. For a new dependency: justify why built-in/platform options are insufficient,
  propose 1–2 alternatives, and ask permission before changing dependency files.
- **Official docs:** you must consult official sources (Angular, Spring, Java, Apple HIG first,
  then vendor repositories, then RFC/W3C) for unfamiliar APIs or new framework features,
  security/auth/crypto/storage/payments/browser APIs, version-specific patterns, “most modern”
  requests, or docs that may have changed; cross-check any blog; cite briefly which doc decided what.

## Security baseline (always on)

- Apply OWASP principles: input validation, output encoding, safe auth/session patterns,
  CSRF/XSS protections where relevant, least privilege.
- Never log secrets or tokens.
- For any auth/crypto/security-sensitive change, consult official docs.

## UI direction

- Do not preserve or extend the current Liquid Glass style; it is explicitly rejected.
- The owner selected the paper/antiquity/indigo direction on 2026-09-06. Follow
  [`docs/frontend/design-and-experience-2026-09.md`](docs/frontend/design-and-experience-2026-09.md)
  (and [`mnema-brand-and-ui-contract.md`](docs/frontend/mnema-brand-and-ui-contract.md)); the
  interactive `design/prototype` is design evidence, not production architecture. Use accessible
  Angular/semantic HTML/CSS without a heavy design library; preserve the accepted direction, not
  every prototype implementation detail.
- Do not treat the current layout, visual identity or component boundaries as compatibility
  requirements.
- Shared UI elements come from the living styleguide; a new one is added there first:
  [`docs/frontend/styleguide.md`](docs/frontend/styleguide.md) (`/styleguide`, development builds only).
- Long content lists use automatic cursor-page continuation around 75% of loaded content, with no «Показать ещё»
  or page-navigation buttons. Use the styleguide's shared auto-load component; errors keep rows and offer explicit retry.
  Object navigation (for example the generation batch pager) remains separate. See the [list rule](docs/frontend/styleguide.md#длинные-списки-и-автоподгрузка).
- UX/a11y principles (primary action, back/close, progressive disclosure, keyboard/focus, semantic
  HTML, reduced motion, mobile-first) and the duty to propose a better layout when requested UI is
  awkward, confusing or overcomplicated: [`engineering-standards.md`](docs/engineering/engineering-standards.md#proactive-design-fixes-and-ux-principles-was-42-43).

## Engineering rules (binding; full wording on demand)

Read the matching section of [`engineering-standards.md`](docs/engineering/engineering-standards.md)
before work in that area: backend (stable error schema, validation, no leaked internals, stateless
services, idempotency/retries/timeouts/backpressure, structured logging), frontend (Signals state,
no unbounded subscriptions, large lists), documentation, cleanup and actionable TODOs, and the
final-report format.

## Quality gate

Add or update tests for non-trivial changes (backend unit + slice/integration; frontend component
tests for critical logic, e2e only when necessary); prefer deterministic tests over brittle timing.

```bash
(cd backend && ./gradlew clean quality)                            # JDK 25 + Docker/Testcontainers
(cd frontend && npm ci && npm run lint && npm run test && npm run build)
python3 scripts/verify_docs.py
```

- Before presenting results or preparing a branch for review, run the full gate locally (backend
  lint/static analysis if configured, backend tests, frontend lint, frontend tests). If anything
  cannot be run or is not configured, state what was blocked or missing and why.
- Before any push, re-run the full relevant gate on the exact branch/commit being pushed.
- If the gate fails for missing coverage or tests, add or update tests until the thresholds pass;
  do not push with a red gate. Coverage thresholds are a hard requirement of done.
- Machine setup (default `java` may differ from JDK 25, Colima socket, Node 24) and the complete CI-equivalent
  command list: [`docs/engineering/agent-runbook.md`](docs/engineering/agent-runbook.md).

## GitHub work items and pull requests

- Before creating or updating an Issue or pull request, follow
  [`docs/engineering/work-item-standard.md`](docs/engineering/work-item-standard.md).
- Write for a human with little project context and for an implementation agent: state the outcome,
  scope, acceptance evidence, risks, and rollback boundary.
- Link to canonical `docs/` sources instead of copying architecture or product decisions into
  platform-specific instructions.
- Do not move a task to `Ready` while a product/architecture choice is unresolved or the work cannot
  fit into a reviewable 1–3 day change.
- Generated code, commits, and green unit tests are evidence, not the outcome. `Done` requires
  merged/applied behavior and proportional verification.

### Task-scoped autonomy and merge boundary

- Current production is https://mnema.app on the Russian VPS `mnema`
  (`135.106.175.30`, administrator access `ssh mnema`). Follow
  [production delivery](docs/operations/production-delivery.md) for development
  releases: full local/hosted gates, protected squash, then Main CI releases a runtime
  change automatically (scoped four-image publication, one `prod` Environment approval,
  automatic admission, deploy, verify and public smoke; `vps-deploy.yaml` only for manual
  status/verify/rollback). Host config/tooling is installed by the administrator beforehand.
  Use the working server/jobs within an owner-authorized production task; keep
  deployment evidence distinct from implementation completion. Kubernetes delivery
  was removed; the VPS path is the only one.
- An explicit request to deliver an issue, epic, or change end to end authorizes the ordinary
  in-scope workflow: create a feature branch, edit, test, commit, push that branch, open or update
  its pull request, monitor CI, fix failures, and push follow-up commits. If the request also says
  to merge, auto-merge, or ship the result, it authorizes squash-merging that pull request after
  every required gate passes. Do not pause for repeated approval as the branch, PR number, or head
  SHA becomes known, and do not restate the authorization in routine updates.
- Treat that authorization as one finite task mandate, not standing permission for unrelated work.
  Ask only when a product or architecture choice would materially change the outcome, required
  credentials or permissions are missing, the scope or external effect expands, or another actor
  changes the pull request beyond the approved task.
- Before merge, re-read the current PR head and rules, require an up-to-date branch, resolved review
  threads, green `backend-quality` and `frontend-quality`, and the full repository quality gate
  required above on the exact commit. Merge only through the protected pull request with squash;
  never push directly to `main`, bypass protection, force-push, or weaken/delete the ruleset.
- Push/PR CI and configured non-production workflows triggered by the authorized delivery are part
  of the mandate. Production deployment, an environment approval, destructive data work, or
  publication outside GitHub is included only when the user names that target and effect in the task
  or a later instruction; otherwise stop at that boundary once and report it.
- The `main protection` ruleset is enforcement, not human approval: it has no bypass actor or
  required approving review, and it requires PR/squash flow, linear history, resolved threads, an
  up-to-date branch, and both quality checks.

### Commit attribution

- Use the repository-local Git identity as the sole commit attribution.
- Do not add `Co-authored-by`, `Signed-off-by`, `Generated-by`, `On-behalf-of`, or similar
  attribution trailers unless the user explicitly requests them.
- Do not modify commit author or committer identity. AI assistance is documented through review
  evidence when relevant, not by injecting additional Git authors.
