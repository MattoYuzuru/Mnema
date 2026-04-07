# Mnema — Agent Guide (Engineering + UX)

## 1) Context & Tech Stack
You are an expert full-stack developer working on **Mnema**, a web-based Anki analogue with enhanced features.

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

## 4) UI Design System: Liquid Glass + UX-by-the-book
### 4.1 Liquid Glass requirement
- For new UI and refactors, follow the **Liquid Glass** aesthetic aligned with Apple’s Human Interface Guidelines:
    - depth + translucency used sparingly and meaningfully
    - clear layering/hierarchy (foreground vs background separation)
    - subtle blur, vibration-free motion, careful contrast
    - avoid “cheap glass”: excessive blur, low contrast text, noisy backgrounds

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
    - backend lint
    - backend tests
    - frontend lint
    - frontend tests
  If any of these cannot be run, explicitly state what was blocked and why.

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
4) call out any important UX/design adjustments (Liquid Glass + usability)
5) list follow-ups as actionable TODOs only when truly needed
