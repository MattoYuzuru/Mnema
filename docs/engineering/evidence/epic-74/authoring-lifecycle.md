# EditingDraft and CaptureNote lifecycle — #201 evidence

Candidate implementation only; protected integration and delivery remain owned by
the lead. Wire contract: `contracts/authoring/README.md`.

V5 adds owner/deck-scoped acknowledged native EditingDraft rows and durable
CaptureNote rows. Draft context/base and CaptureNote creation/conversion provenance
are protected by relational constraints and transition triggers. Drafts use a 30-day
sliding acknowledgement expiry; captures have no expiry column or cleanup path.

Draft create/update validate native-v1 before persistence. Account-scoped advisory
locking serializes the 200-entry/20-MiB quota decision; row-version CAS prevents
concurrent tabs from silently overwriting one another. Autosave only updates the
draft row and receipt and has no dependency on LearningItem publication.

Capture conversion depends on the caller-owned `CaptureItemPublisher` port. Its
current adapter delegates to #200's canonical single-item publication. A row lock
serializes conversion of one note; the Item receipt/publication and immutable note
conversion reference share the outer transaction. Exact command/hash retry returns
the stored result, while a failed or rolled-back publication leaves the source note,
drafts, Deck head, Item projection and receipt unchanged.

Security review follows Spring Security's official
[request authorization guidance](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html):
GET/HEAD restore requires `learning.read`; every mutation requires `learning.write`;
the existing current-Identity filter remains fail closed. Transaction composition
uses Spring's official
[REQUIRED propagation semantics](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html),
and PostgreSQL row/advisory locks plus exact foreign keys follow the official
[locking](https://www.postgresql.org/docs/current/explicit-locking.html) and
[constraint](https://www.postgresql.org/docs/current/ddl-constraints.html) contracts.

No scheduler, media/transcription, AI, offline sync, frontend integration, production
deployment, latency/SLO or crash-resumable job guarantee is claimed. Capture limits
are protective engineering defaults; CaptureNote data never expires because of idle
time, but explicit owner/account/deck deletion policy may remove it.

## Local verification — 2026-09-19

- `./gradlew :services:learning:test --tests '*catalog.authoring.*' --console=plain`:
  11 tests pass with zero failures/errors/skips. This includes PostgreSQL draft
  restore, competing virtual-thread saves, capture older than the draft policy,
  conversion retry/rollback, opaque ownership and both account entry limits.
- `./gradlew :services:learning:test --console=plain`: 300 tests pass with zero
  failures/errors/skips on the fresh V1–V5 PostgreSQL 18 Testcontainers history.
  The suite includes actual HTTP security checks for read/write scope separation and
  the existing wrong/revoked token and Identity fail-closed matrix.
- `./gradlew quality --console=plain`: `BUILD SUCCESSFUL` in 25s on the exact final
  candidate (an earlier clean-cache run completed in 2m10s); Learning line coverage
  is 95.54% against the 90% threshold and every backend module baseline passes.

The checks prove transaction rollback and concurrent in-process/database behavior;
they do not constitute a process-kill, production-load, retention-operations or
latency guarantee.
