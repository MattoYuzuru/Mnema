---
artifact:
  id: security-triage
  type: operational-contract
  title: "Security automation triage and exceptions"
  status: current
  created_at: "2026-08-29"
  updated_at: "2026-08-29"
  owners: ["project-owner"]
---

# Security automation triage and exceptions

GitHub security automation is evidence, not a substitute for validation. Advisory details,
vulnerable paths and exception rationale stay in GitHub's private security surfaces; public issues
and PRs contain only sanitized status, counts and links.

## Trust boundary

The shipping scope is the frontend image and backend service images promoted by the immutable
release manifest. The dependency graph, CodeQL Default Setup and dependency review cover repository
source and manifests; they do not prove the deployed image digest is clean. SBOM, provenance and
shipping-image scans remain the separate #122 boundary.

An alert is classified as confirmed, probable, design risk, missing evidence, intentional behavior
or unverified hypothesis only after checking reachability, affected revision, scope, mitigating
controls and impact. Scanner severity is not proof of exploitability.

## Baseline

An alert already present on `main`, with no affected dependency or code path introduced by the PR,
belongs to the baseline. It remains visible and triaged in GitHub Security, but does not force an
unrelated PR to repair legacy application debt. Baseline High/Critical findings in shipping scope
still require a private follow-up with an owner; Angular/npm migration remains #74.

Dependabot performs weekly bounded version updates for GitHub Actions, Gradle and production Docker
locations. Routine npm version PRs are disabled with `open-pull-requests-limit: 0`; npm security
updates remain grouped and visible. Gradle and Docker major updates require an explicit migration
task, while GitHub Action majors remain a separate group and must pass the immutable-pin policy and
the full PR quality gate.

## New regression

A dependency added or updated by a PR is rejected when dependency review reports a High or Critical
advisory in runtime, development or unknown scope. A new CodeQL result on changed code is also a red
security gate for autonomous merge even while CodeQL is not a ruleset-required check. The reviewer
validates the finding and either fixes it or records a temporary exception; unrelated baseline
alerts are not copied into the PR.

## Temporary exception

Exceptions are stored in a private security issue or advisory. High/Critical exceptions expire in
at most 30 days unless the project owner records a shorter product-specific deadline. The record
must contain:

- `Owner`: accountable GitHub user or team.
- `Rationale`: why immediate remediation is riskier or not currently possible.
- `Expiry`: ISO date after which the exception no longer authorizes merge or release.
- `Compensating controls`: concrete containment, monitoring or exposure reduction.
- `Follow-up`: private work item and the evidence required to close it.

An exception is rejected when any field is missing, the affected revision/scope is unclear, or it
would silently disable repository-wide scanning. Expired exceptions block the affected release.

## Operations and rollback

- Dependabot alerts and security updates are repository settings; configuration lives in
  `.github/dependabot.yml`.
- CodeQL uses Default Setup with the `default` query suite for Java/Kotlin and
  JavaScript/TypeScript. Java/Kotlin autobuild uses the repository's bounded Gradle/Kotlin daemon
  memory settings so CodeQL tracing does not fall back by silently dropping the language. It is
  intentionally not a custom workflow or required ruleset check during baseline stabilization.
- Dependency review is diff-scoped and read-only; it has no pull-request write permission.
- If automation creates unexpected npm/application-major churn, keep alerts enabled and restore the
  npm version PR limit to zero. If CodeQL setup itself is unstable, disable only Default Setup after
  preserving sanitized diagnostics; never disable secret scanning or push protection as rollback.

Official references: [Dependabot supported ecosystems](https://docs.github.com/en/code-security/reference/supply-chain-security/supported-ecosystems-and-repositories),
[Dependabot options](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference),
[dependency review configuration](https://docs.github.com/en/code-security/how-tos/secure-your-supply-chain/manage-your-dependency-security/configure-dependency-review-action),
and [CodeQL setup types](https://docs.github.com/en/code-security/concepts/code-scanning/setup-types).
