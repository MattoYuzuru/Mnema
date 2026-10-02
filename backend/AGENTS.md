# Backend scope

Adds to the root [`AGENTS.md`](../AGENTS.md); does not repeat it. Modules, versions and change
routes: [repository guide](../docs/engineering/repository-guide.md). Commands and machine setup:
[agent runbook](../docs/engineering/agent-runbook.md).

## Build and test

- Run Gradle from `backend/` with the wrapper and **JDK 25** (`JAVA_HOME`); the workstation default
  `java` may be newer. Gate: `./gradlew clean quality` (compile, tests, JaCoCo, the floors in
  `coverage-baseline.json`).
- Java sources compile with `-Xlint:all -Werror`: a new warning fails the build. Tests run with Mockito
  as a `-javaagent` (declared in `backend/build.gradle.kts`); do not rely on dynamic agent loading.
- JSON is Jackson 3 (`tools.jackson`). `JsonNode` accessors are strict: `stringValue()`, `intValue()`,
  `booleanValue()` and `asString()` throw `JsonNodeException` on a mismatched node, and `JacksonException`
  is unchecked, so parser `catch` blocks must name it. Use the `...(default)` overloads
  (`stringValue(null)`, `intValue(0)`) where absence must stay lenient, and keep request parsers
  answering HTTP 400, never 500 (`JsonRequestBoundaryTest`, `MalformedJsonBodies`).
- PostgreSQL-backed tests use Testcontainers and fail closed. A Docker/socket failure is an
  environment problem, never a reason to accept skipped tests (Colima variables are in the runbook).

## Boundaries

- Only `services/identity-account` and `services/learning` are compiled (`settings.gradle.kts`).
  Removed v1 services (core, media, import, ai, user, auth) exist only in Git history and the
  `v1-apache-final` tag; do not restore or reuse their entities, migrations or scheduler/review code.
- Learning never reads Identity tables: it validates bearer claims and calls Identity `/userinfo`.
- Reuse the platform contracts already in Learning instead of inventing parallel ones: UUID
  identity, canonical JSON, global command receipts (idempotency), row-version CAS, RFC 9457
  Problem Details, owner ACL, immutable revisions, deck-local item identity.
- Migrations are ordered Flyway files under `src/main/resources/db/<runtime>/migration`. Add the next
  version after the current head; never edit an applied migration.
- API paths live under `/api`; there is no `/v2` or v1 alias.

## Study domain invariants

- The wire contract and fixtures are [`contracts/study`](../contracts/study/README.md); change code,
  fixtures and contract together. Vocabulary: [domain truth map](../docs/engineering/domain-truth-map.md).
- Content, exercise revision, attempt/evaluation/evidence and `StudyState` stay separate. Only an
  explicitly `ASSESSED` objective receives scheduler evidence, and only `SCHEDULED` sessions write
  canonical state; replay and practice are feedback-only.
- Learner presentations never contain answer keys, bindings or accepted answers; the server
  records hints and transcript reveals.
- A green unit test does not prove per-objective credit, deterministic replay or session snapshot
  isolation; keep or add real-database tests for those.
