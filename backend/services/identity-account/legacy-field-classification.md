# Legacy Identity & Account field classification

This allowlist is the input contract for #144, not an export/import
implementation. `PRESERVE` means the value is eligible for account-only transfer;
`RECREATE` means the replacement needs the concept but derives a fresh value or
configuration; `DELETE` means the value must not enter the replacement.

## Reconciliation rules

- `auth.users.id` is the canonical stable `account_id`. An `app_user.users.id`
  mismatch is a stop condition, not an ID adapter.
- `auth.users.email` is canonical; `app_user.users.email` must match after
  `lower(btrim(...))` or reconciliation stops.
- `auth.users.username` is the local credential login name;
  `app_user.users.username` is the independently mutable profile handle. They
  stay in separate target columns and are never reconciled as duplicates.
- `auth.users.created_at` is account creation time. The later
  `app_user.users.created_at` is preserved separately as profile creation time;
  target `updated_at` is recreated when #144 imports the profile.
- Only `app_user.users.avatar_media_id` can select an avatar, and only when #144
  also proves owner/kind/status, copies the exact object, and supplies complete
  metadata plus a computed checksum. Every provider/profile URL is denied.
- A row with `banned_at` becomes `BANNED`; a row without it becomes `ACTIVE`.
  Admin and moderation actor UUIDs must resolve to preserved accounts or
  reconciliation stops.
- Duplicate normalized account email, normalized username, or exact normalized
  provider plus opaque provider subject stops import. No merge-by-email is
  permitted for conflicting accounts.

## Source fields

| Source table | Field | Class | Target / rationale |
|---|---|---|---|
| `auth.accounts` | `id` | RECREATE | Internal federated-row key; create `external_identity.identity_id`. |
| `auth.accounts` | `provider` | PRESERVE | Normalize into `external_identity.provider`. |
| `auth.accounts` | `provider_sub` | PRESERVE | Opaque `external_identity.provider_subject`; never case-fold. |
| `auth.accounts` | `email` | DELETE | Stale provider snapshot; canonical email comes from `auth.users`. |
| `auth.accounts` | `email_verified` | DELETE | Provider snapshot cannot elevate account-level verification. |
| `auth.accounts` | `name` | DELETE | Duplicated provider claim; account display name comes from `auth.users`. |
| `auth.accounts` | `picture_url` | DELETE | Remote provider URL is neither owned nor recoverable account media. |
| `auth.accounts` | `created_at` | PRESERVE | `external_identity.linked_at`. |
| `auth.accounts` | `last_login_at` | PRESERVE | `external_identity.last_login_at`. |
| `auth.accounts` | `user_id` | PRESERVE | Resolve to canonical `account.account_id`. |
| `auth.users` | `id` | PRESERVE | Canonical stable `account.account_id` and OIDC `sub` source. |
| `auth.users` | `email` | PRESERVE | Canonical `account.email`; normalize for uniqueness. |
| `auth.users` | `email_verified` | PRESERVE | Canonical account-email verification state. |
| `auth.users` | `name` | PRESERVE | `account.display_name`. |
| `auth.users` | `picture_url` | DELETE | Remote provider URL is not an owned avatar asset. |
| `auth.users` | `created_at` | PRESERVE | Canonical `account.created_at`. |
| `auth.users` | `last_login_at` | PRESERVE | `account.last_login_at`. |
| `auth.users` | `username` | PRESERVE | Independent `local_credential.login_name` when a local credential exists. |
| `auth.users` | `password_hash` | PRESERVE | Opaque `local_credential.password_hash`; never log or re-hash during transfer. |
| `auth.users` | `failed_login_attempts` | RECREATE | Transient risk counter starts at zero. |
| `auth.users` | `locked_until` | RECREATE | Transient lock expires with the old runtime; initialize null. |
| `auth.oauth2_registered_client` | `id` | RECREATE | Registered clients are deployment configuration, not account data. |
| `auth.oauth2_registered_client` | `client_id` | RECREATE | Re-register from replacement configuration. |
| `auth.oauth2_registered_client` | `client_id_issued_at` | RECREATE | New registration metadata. |
| `auth.oauth2_registered_client` | `client_secret` | RECREATE | Secret must never enter account export. |
| `auth.oauth2_registered_client` | `client_secret_expires_at` | RECREATE | New secret lifecycle. |
| `auth.oauth2_registered_client` | `client_name` | RECREATE | Deployment configuration. |
| `auth.oauth2_registered_client` | `client_authentication_methods` | RECREATE | Deployment configuration. |
| `auth.oauth2_registered_client` | `authorization_grant_types` | RECREATE | Deployment configuration. |
| `auth.oauth2_registered_client` | `redirect_uris` | RECREATE | Deployment configuration. |
| `auth.oauth2_registered_client` | `post_logout_redirect_uris` | RECREATE | Deployment configuration. |
| `auth.oauth2_registered_client` | `scopes` | RECREATE | Deployment configuration. |
| `auth.oauth2_registered_client` | `client_settings` | RECREATE | Deployment configuration. |
| `auth.oauth2_registered_client` | `token_settings` | RECREATE | Deployment configuration. |
| `auth.oauth2_authorization` | `id` | DELETE | Authorization/session state is explicitly denied. |
| `auth.oauth2_authorization` | `registered_client_id` | DELETE | Authorization/session state is explicitly denied. |
| `auth.oauth2_authorization` | `principal_name` | DELETE | Authorization/session state is explicitly denied. |
| `auth.oauth2_authorization` | `authorization_grant_type` | DELETE | Grant state is explicitly denied. |
| `auth.oauth2_authorization` | `authorized_scopes` | DELETE | Grant state is explicitly denied. |
| `auth.oauth2_authorization` | `attributes` | DELETE | Authorization/session state may contain sensitive transient claims. |
| `auth.oauth2_authorization` | `state` | DELETE | Transient authorization state. |
| `auth.oauth2_authorization` | `authorization_code_value` | DELETE | Authorization code secret. |
| `auth.oauth2_authorization` | `authorization_code_issued_at` | DELETE | Authorization code lifecycle. |
| `auth.oauth2_authorization` | `authorization_code_expires_at` | DELETE | Authorization code lifecycle. |
| `auth.oauth2_authorization` | `authorization_code_metadata` | DELETE | Authorization code metadata. |
| `auth.oauth2_authorization` | `access_token_value` | DELETE | Access token secret. |
| `auth.oauth2_authorization` | `access_token_issued_at` | DELETE | Token lifecycle. |
| `auth.oauth2_authorization` | `access_token_expires_at` | DELETE | Token lifecycle. |
| `auth.oauth2_authorization` | `access_token_metadata` | DELETE | Token metadata. |
| `auth.oauth2_authorization` | `access_token_type` | DELETE | Token state. |
| `auth.oauth2_authorization` | `access_token_scopes` | DELETE | Token state. |
| `auth.oauth2_authorization` | `oidc_id_token_value` | DELETE | ID token secret. |
| `auth.oauth2_authorization` | `oidc_id_token_issued_at` | DELETE | Token lifecycle. |
| `auth.oauth2_authorization` | `oidc_id_token_expires_at` | DELETE | Token lifecycle. |
| `auth.oauth2_authorization` | `oidc_id_token_metadata` | DELETE | Token metadata. |
| `auth.oauth2_authorization` | `refresh_token_value` | DELETE | Refresh token secret. |
| `auth.oauth2_authorization` | `refresh_token_issued_at` | DELETE | Token lifecycle. |
| `auth.oauth2_authorization` | `refresh_token_expires_at` | DELETE | Token lifecycle. |
| `auth.oauth2_authorization` | `refresh_token_metadata` | DELETE | Token metadata. |
| `auth.oauth2_authorization_consent` | `registered_client_id` | DELETE | Consent/grant state is explicitly denied. |
| `auth.oauth2_authorization_consent` | `principal_name` | DELETE | Consent/grant state is explicitly denied. |
| `auth.oauth2_authorization_consent` | `authorities` | DELETE | Consent/grant state is explicitly denied. |
| `app_user.users` | `id` | DELETE | Reconciliation evidence only; target ID always comes from `auth.users.id`. |
| `app_user.users` | `email` | DELETE | Reconciliation evidence only; target email always comes from `auth.users.email`. |
| `app_user.users` | `username` | PRESERVE | Independent `account.profile_username`. |
| `app_user.users` | `bio` | PRESERVE | `account.bio`. |
| `app_user.users` | `is_admin` | PRESERVE | Active account authorization state. |
| `app_user.users` | `avatar_url` | DELETE | Arbitrary URL does not prove blob ownership and may be stale or tracking. |
| `app_user.users` | `created_at` | PRESERVE | `account.profile_created_at`. |
| `app_user.users` | `updated_at` | RECREATE | Target `account.updated_at` starts at the import/write time. |
| `app_user.users` | `avatar_media_id` | PRESERVE | `account_avatar.asset_id`, conditional on exact asset metadata. |
| `app_user.users` | `admin_granted_by` | PRESERVE | `account.admin_granted_by`; must resolve to preserved account. |
| `app_user.users` | `admin_granted_at` | PRESERVE | `account.admin_granted_at`. |
| `app_user.users` | `banned_by` | PRESERVE | `account.banned_by`; must resolve to preserved account. |
| `app_user.users` | `banned_at` | PRESERVE | `account.banned_at` and `BANNED` state. |
| `app_user.users` | `ban_reason` | PRESERVE | Sensitive admin-only `account.ban_reason`; never log it. |

Auth migrations V2–V6 only mutate registered-client rows or nullability and add
no source fields. No legacy migration is changed by this contract.

## Conditional avatar source boundary

The exhaustive table above covers the 76 fields in the current `auth` and
`app_user` schemas. The only eligible row outside them is the single
`app_media.media_assets` row referenced by `avatar_media_id`. #144 must reject it
unless `owner_user_id` equals the canonical account ID, `kind = 'avatar'`,
`status = 'ready'`, `deleted_at IS NULL`, and the exact object exists.

- Preserve `media_id` as `asset_id`, `mime_type`, positive `size_bytes`, optional
  positive `width`/`height`, `created_at`, and the exact blob.
- Recreate a fresh `storage_key` and compute the required 32-byte SHA-256 from
  the copied blob; the legacy schema has no content hash.
- Delete legacy `kind`/`status` after they have served as preflight evidence,
  `duration_seconds`, `original_file_name`, `updated_at`, and `deleted_at`.
- Delete every `media_uploads` row and all upload IDs, multipart state, expected
  metadata, expiry/error fields, presigned URLs, caches, and object versions.

The target accepts only JPEG, PNG, or WebP, at most 10 MiB and 1024×1024. A
legacy avatar outside that envelope is a reconciliation stop, not a reason to
preserve arbitrary media or introduce a compatibility path.
