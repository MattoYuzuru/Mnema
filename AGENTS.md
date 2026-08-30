# Mnema — Agent Guide (Engineering + UX)

## 1) Context & Tech Stack
You are an expert full-stack developer working on **Mnema**, a greenfield learning platform built around versioned `LearningItem`s, multiple exercise types and spaced practice. The checked-in v1 flashcard implementation is replacement input, not an architecture to preserve.

**Backend**
- Spring Boot **3.x+**, **Java 21**
- Prefer **Virtual Threads** where appropriate (I/O-heavy concurrency)
- Use modern Java: Records, Pattern Matching, sealed types, etc.

**Languages**
- Primarily Java 21
- Use **Kotlin** only in places consistent with the existing codebase (follow current package/module boundaries)

**Frontend**
- **Angular (latest stable)**
- Standalone components
- Signal-based state management (Signals, computed/effect patterns where idiomatic)
- A11y-first UI

---

## 2) Non-Negotiables (Hard Constraints)
### 2.1 Modernity & Freshness
- Never use deprecated APIs, legacy patterns, or abandoned libraries.
- If unsure whether an API/library is current, **verify via official docs** (see “Docs & Web”).
- Match solutions to the exact versions in use (Java 21, Spring Boot 3.x, Angular latest stable).

### 2.2 Architecture & Consistency
- Follow SOLID, GRASP, Clean Code.
- Avoid overengineering; pick the simplest solution that is correct, scalable, and maintainable.
- For the accepted greenfield rewrite, do not add `/v2` routes, dual reads/writes, compatibility adapters or wrappers around v1 product code. Replace the canonical path directly and delete superseded code within the owning epic; temporary product downtime and incomplete product flows are acceptable during the rewrite.
- Preserve only conventions and modules that still fit the accepted target. “Match nearby code” is not a reason to reproduce legacy deck/card/template, service or scheduler boundaries.
- **Before writing code**: scan nearby code to match established:
    - naming, folder structure, module boundaries
    - error handling style
    - logging format
    - testing conventions

### 2.3 Dependency Discipline
- **Never modify** `package.json`, `pom.xml`, `build.gradle` unless absolutely required.
- If a new dependency is needed:
    1) justify why built-in/platform options are insufficient
    2) propose 1–2 alternatives
    3) ask permission before changing dependency files

---

## 3) Docs & Web (Required)
### 3.1 When to browse
You **must** consult official sources when:
- implementing an unfamiliar API or new framework feature
- dealing with security/auth, crypto, storage, payments, browser APIs
- choosing between patterns with version-specific differences
- the user requests “most modern / recommended way”
- you suspect docs may have changed recently

### 3.2 Sources priority
Prefer, in order:
1) Official docs (Angular, Spring, Java, Apple HIG)
2) Vendor repositories (GitHub orgs of framework authors)
3) Well-known standards/specs (RFC/W3C) when relevant
   Avoid random blogs unless nothing else exists; if used, cross-check.

### 3.3 Cite what matters (briefly)
When you used docs to decide something important, include short references:
- what doc was used
- what decision it influenced (no long quotes)

---

## 4) UI Direction + UX-by-the-book
### 4.1 Visual direction
- Do not preserve or extend the current Liquid Glass style. It is explicitly rejected for the replacement UI.
- The final visual system will be chosen in the frontend epic after owner design input. Until then, prefer a minimal, accessible Angular/semantic HTML/CSS baseline without a heavy design library or speculative component system.
- Do not treat the current layout, visual identity or component boundaries as compatibility requirements.

### 4.2 Proactive design fixes
If the requested UI:
- has awkward button placement
- introduces confusing navigation
- overcomplicates flows
- breaks established visual rhythm/patterns
  …then propose a better UX layout and explain the tradeoff.

### 4.3 UX principles to enforce
- Clear primary action, predictable back/close behavior
- Progressive disclosure for advanced options
- Respect platform conventions (keyboard, focus order, hover/focus states)
- A11y: semantic elements, ARIA only when needed, proper contrast, reduced motion support
- Responsive: mobile-first layout, touch targets, safe spacing

---

## 5) Backend Engineering Rules
### 5.1 API contracts & error handling
- Use consistent API error format (stable schema).
- Prefer explicit validation + clear error codes/messages.
- Do not leak internals (stack traces, SQL, secrets).

### 5.2 Stateless & Cloud-native
- Services must remain stateless; externalize state to DB/queues/caches as appropriate.
- Be mindful of idempotency, retries, timeouts, and backpressure.

### 5.3 Logging & Observability
- Structured logging (key/value), consistent fields (traceId/requestId when available).
- Log at appropriate levels, avoid sensitive data.
- Add metrics/tracing hooks when needed (but don’t overinstrument).

---

## 6) Frontend Engineering Rules
- Use Angular best practices for the current version (standalone-first).
- Prefer Signals patterns; keep state colocated when feasible.
- Prevent regressions:
    - routing and component boundaries clean
    - avoid unbounded subscriptions (use takeUntilDestroyed / async patterns)
- Performance: avoid unnecessary change churn; be mindful of large lists (virtualization when necessary).

---

## 7) Security Baseline (Always On)
- Apply OWASP principles by default:
    - input validation, output encoding
    - safe auth/session patterns
    - CSRF/XSS protections where relevant
    - least privilege
- Never log secrets or tokens.
- For any auth/crypto/security-sensitive change: consult official docs.

---

## 8) Quality Bar: Tests, Docs, Cleanup
### 8.1 Tests
- Add or update tests for non-trivial changes:
    - backend: unit tests + slice/integration tests where appropriate
    - frontend: component tests for critical logic, e2e only when necessary
- Prefer deterministic tests; avoid brittle timing.
- Before presenting final results or preparing a branch for review, run the full project quality gate locally:
    - backend lint/static analysis, if configured in this repository
    - backend tests
    - frontend lint
    - frontend tests
  If any of these cannot be run, or if a requested quality gate is not configured in the repo yet, explicitly state what was blocked or missing and why.
- Before any push, re-run the full relevant quality gate on the exact branch/commit being pushed.
- If the quality gate fails because of missing coverage or tests, add or update tests until the configured thresholds pass; do not push with a red gate.
- Treat coverage thresholds as a hard requirement of done, not a best-effort check.

### 8.2 Documentation
- Document non-obvious behavior (Javadoc/KDoc, README snippets, or inline comments).
- Keep docs short and aligned with code.

### 8.3 Cleanup
- Remove unused code/exports/dead branches when safe.
- If removal is risky or out-of-scope, add a **targeted TODO** with context and owner/action.

### 8.4 TODO rules
- TODOs must be actionable:
    - why it exists
    - what needs doing
    - constraints/risks
      Avoid vague TODOs like “refactor later”.

---

## 9) Output Expectations (How you respond)
When implementing:
1) briefly state approach and key decisions
2) produce production-ready code
3) mention tests added/updated
4) call out any important UX/design adjustments and accessibility implications
5) list follow-ups as actionable TODOs only when truly needed

---

## 10) GitHub Work Items & Pull Requests
- Before creating or updating an Issue or pull request, follow `docs/engineering/work-item-standard.md`.
- Write for a human with little project context and for an implementation agent: state the outcome, scope, acceptance evidence, risks, and rollback boundary.
- Link to canonical `docs/` sources instead of copying architecture or product decisions into platform-specific instructions.
- Do not move a task to `Ready` while a product/architecture choice is unresolved or the work cannot fit into a reviewable 1–3 day change.
- Generated code, commits, and green unit tests are evidence, not the outcome. `Done` requires merged/applied behavior and proportional verification.

### Task-scoped autonomy and merge boundary

- An explicit request to deliver an issue, epic, or change end to end authorizes the ordinary in-scope workflow: create a feature branch, edit, test, commit, push that branch, open or update its pull request, monitor CI, fix failures, and push follow-up commits. If the request also says to merge, auto-merge, or ship the result, it authorizes squash-merging that pull request after every required gate passes. Do not pause for repeated approval as the branch, PR number, or head SHA becomes known, and do not restate the authorization in routine updates.
- Treat that authorization as one finite task mandate, not standing permission for unrelated work. Ask only when a product or architecture choice would materially change the outcome, required credentials or permissions are missing, the scope or external effect expands, or another actor changes the pull request beyond the approved task.
- Before merge, re-read the current PR head and rules, require an up-to-date branch, resolved review threads, green `backend-quality` and `frontend-quality`, and the full repository quality gate required above on the exact commit. Merge only through the protected pull request with squash; never push directly to `main`, bypass protection, force-push, or weaken/delete the ruleset.
- Push/PR CI and configured non-production workflows triggered by the authorized delivery are part of the mandate. Production deployment, an environment approval, destructive data work, or publication outside GitHub is included only when the user names that target and effect in the task or a later instruction; otherwise stop at that boundary once and report it.
- The `main protection` ruleset is enforcement, not human approval: it has no bypass actor or required approving review, and it requires PR/squash flow, linear history, resolved threads, an up-to-date branch, and both quality checks.

### Commit attribution

- Use the repository-local Git identity as the sole commit attribution.
- Do not add `Co-authored-by`, `Signed-off-by`, `Generated-by`, `On-behalf-of`, or similar attribution trailers unless the user explicitly requests them.
- Do not modify commit author or committer identity. AI assistance is documented through review evidence when relevant, not by injecting additional Git authors.
