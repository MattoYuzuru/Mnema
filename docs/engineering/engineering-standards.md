---
artifact:
  id: engineering-standards
  type: engineering-standard
  title: "Mnema engineering, UX and response standards"
  status: current
  created_at: "2026-10-01"
  updated_at: "2026-10-01"
  owners: ["project-owner"]
---

# Engineering, UX and response standards

Full wording of the generic engineering rules that used to be inline in the root
[`AGENTS.md`](../../AGENTS.md). Root `AGENTS.md` keeps a one-line form of every rule
below and stays the always-loaded contract; this file is read on demand when a task
touches the area (docs research, UI/UX, API/backend design, Angular, tests/cleanup,
the final report). The two must not diverge: change both in the same pull request,
and never weaken a rule here without owner approval. The wording below is moved
verbatim from the previous `AGENTS.md` sections 1 (stack lists), 3, 4.2, 4.3, 5, 6, 8.2–8.4 and 9
(8.1 is also kept in root `AGENTS.md`).

## Platform and language (was §1)

Backend:

- Spring Boot **4.x**, **Java 25**
- Prefer **Virtual Threads** where appropriate (I/O-heavy concurrency)
- Use modern Java: Records, Pattern Matching, sealed types, etc.

Languages:

- Primarily Java 25
- Use **Kotlin** only in places consistent with the existing codebase (follow current package/module boundaries)

Frontend:

- **Angular (latest stable)**
- Standalone components
- Signal-based state management (Signals, computed/effect patterns where idiomatic)
- A11y-first UI

Exact repository versions: [repository guide](./repository-guide.md#platform-baseline).

## Docs and web research (was §3)

### When to browse

You **must** consult official sources when:

- implementing an unfamiliar API or new framework feature
- dealing with security/auth, crypto, storage, payments, browser APIs
- choosing between patterns with version-specific differences
- the user requests “most modern / recommended way”
- you suspect docs may have changed recently

### Sources priority

Prefer, in order:

1. Official docs (Angular, Spring, Java, Apple HIG)
2. Vendor repositories (GitHub orgs of framework authors)
3. Well-known standards/specs (RFC/W3C) when relevant.
   Avoid random blogs unless nothing else exists; if used, cross-check.

### Cite what matters (briefly)

When you used docs to decide something important, include short references:

- what doc was used
- what decision it influenced (no long quotes)

## Proactive design fixes and UX principles (was §4.2, §4.3)

If the requested UI:

- has awkward button placement
- introduces confusing navigation
- overcomplicates flows
- breaks established visual rhythm/patterns

…then propose a better UX layout and explain the tradeoff.

UX principles to enforce:

- Clear primary action, predictable back/close behavior
- Progressive disclosure for advanced options
- Respect platform conventions (keyboard, focus order, hover/focus states)
- A11y: semantic elements, ARIA only when needed, proper contrast, reduced motion support
- Responsive: mobile-first layout, touch targets, safe spacing

The visual direction itself (paper/antiquity/indigo, no Liquid Glass) is not repeated
here: see [`AGENTS.md`](../../AGENTS.md#ui-direction) and the
[design contract](../frontend/design-and-experience-2026-09.md).

## Backend engineering rules (was §5)

### API contracts and error handling

- Use consistent API error format (stable schema).
- Prefer explicit validation + clear error codes/messages.
- Do not leak internals (stack traces, SQL, secrets).

### Stateless and cloud-native

- Services must remain stateless; externalize state to DB/queues/caches as appropriate.
- Be mindful of idempotency, retries, timeouts, and backpressure.

### Logging and observability

- Structured logging (key/value), consistent fields (traceId/requestId when available).
- Log at appropriate levels, avoid sensitive data.
- Add metrics/tracing hooks when needed (but don’t overinstrument).

## Frontend engineering rules (was §6)

- Use Angular best practices for the current version (standalone-first).
- Prefer Signals patterns; keep state colocated when feasible.
- Prevent regressions:
  - routing and component boundaries clean
  - avoid unbounded subscriptions (use takeUntilDestroyed / async patterns)
- Performance: avoid unnecessary change churn; be mindful of large lists (virtualization when necessary).
- Long content lists automatically append bounded cursor pages around 75% of the loaded list. Do not add «Показать ещё»
  or previous/next page buttons. Use the living [styleguide list contract](../frontend/styleguide.md#длинные-списки-и-автоподгрузка)
  for the shared component, explicit retry, context isolation, focus/selection and embedded scroll roots. Object navigation
  remains separate. Measure large-list rendering and keep offscreen work bounded without breaking accessibility.

## Quality bar (was §8)

### Tests

- Add or update tests for non-trivial changes:
  - backend: unit tests + slice/integration tests where appropriate
  - frontend: component tests for critical logic, e2e only when necessary
- Prefer deterministic tests; avoid brittle timing.
- Before presenting final results or preparing a branch for review, run the full
  project quality gate locally:
  - backend lint/static analysis, if configured in this repository
  - backend tests
  - frontend lint
  - frontend tests

  If any of these cannot be run, or if a requested quality gate is not configured in
  the repo yet, explicitly state what was blocked or missing and why.
- Before any push, re-run the full relevant quality gate on the exact branch/commit being pushed.
- If the quality gate fails because of missing coverage or tests, add or update tests
  until the configured thresholds pass; do not push with a red gate.
- Treat coverage thresholds as a hard requirement of done, not a best-effort check.

The exact commands and the local-machine setup are in the
[agent runbook](./agent-runbook.md).

### Documentation

- Document non-obvious behavior (Javadoc/KDoc, README snippets, or inline comments).
- Keep docs short and aligned with code.

### Cleanup

- Remove unused code/exports/dead branches when safe.
- If removal is risky or out-of-scope, add a **targeted TODO** with context and owner/action.

### TODO rules

- TODOs must be actionable:
  - why it exists
  - what needs doing
  - constraints/risks

  Avoid vague TODOs like “refactor later”.

## Output expectations (was §9)

When implementing:

1. briefly state approach and key decisions
2. produce production-ready code
3. mention tests added/updated
4. call out any important UX/design adjustments and accessibility implications
5. list follow-ups as actionable TODOs only when truly needed
