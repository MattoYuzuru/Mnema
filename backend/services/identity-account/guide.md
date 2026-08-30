# Identity & Account runtime

`services:identity-account` is the sole greenfield owner of account identity,
profile, local credentials, federated bindings, moderation state and account
avatar ownership. It is a standalone deployable and has no Gradle or source
dependency on the legacy `auth` or `user` applications.

## Runtime and database contract

- The fresh Flyway root is `classpath:db/identity/migration`; it owns only
  `app_identity` and never scans either legacy migration chain.
- `account.account_id` is the stable account identifier. It is preserved during
  account-only transfer and is never reassigned.
- Email uniqueness is enforced on stored `lower(btrim(email))`; username
  uniqueness uses the same normalization when a username exists.
- `local_credential`, `external_identity`, and `account_avatar` all belong to
  `account` and cascade only inside this aggregate.
- Federated provider keys are normalized lowercase identifiers. Provider
  subjects are opaque, case-sensitive strings and are unique only together with
  the provider key.
- Account status is one of `ACTIVE`, `SUSPENDED`, `PENDING_DELETION`, `PURGING`,
  or `PURGED`. No retention duration is encoded in this schema.
- An owned avatar asset requires an object key, content type, byte size and
  SHA-256. A source URL without a copied object remains representable for the
  account-only transfer decision in #144; it never becomes learning media.

PostgreSQL constraints are named so duplicate normalized email and duplicate
provider/subject failures have stable database evidence (`23505` plus the
constraint name). Application error mapping and all account/profile endpoints
belong to #142.

## Stable issuer and subject

`MNEMA_IDENTITY_ISSUER` is required to denote one absolute HTTPS issuer without
user info, query or fragment; startup fails when it is absent or invalid. The
issuer is never derived from request headers. `sub` is the lowercase canonical UUID text
of `account_id`, so it is locally unique, at most 36 ASCII characters and never
changes when email, username or profile fields change.

Consumers identify a person by the exact `(iss, sub)` pair. They must not use
email, username or display name as a stable identifier. This follows
[OpenID Connect Core 1.0, sections 2 and 5.7](https://openid.net/specs/openid-connect-core-1_0.html).
When #142 installs Spring Authorization Server, it must pass the same configured
issuer to `AuthorizationServerSettings`; Spring documents request-derived issuer
resolution only as the fallback when no issuer is configured
([configuration model](https://docs.spring.io/spring-authorization-server/reference/configuration-model.html)).

## Preservation boundary

The exhaustive field-level source classification is in
[`legacy-field-classification.md`](legacy-field-classification.md). The privacy
denylist is structural: this module contains no session, authorization, consent,
grant, token, registered-client, signing-key or transient-challenge table.

PostgreSQL 18 `UNIQUE`, `CHECK`, and foreign-key constraints provide the data
boundary described above; see the official
[constraint](https://www.postgresql.org/docs/18/ddl-constraints.html) and
[unique-index](https://www.postgresql.org/docs/18/indexes-unique.html)
documentation. Integration tests use a real fail-closed PostgreSQL 18 container.
