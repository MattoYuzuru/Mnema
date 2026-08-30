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
- Username uses `app_user.users.username`, falling back to `auth.users.username`;
  two non-null values must match after normalization.
- `auth.users.created_at` is account creation time. The separately created
  `app_user.users.created_at` row timestamp has no independent product meaning.
- `app_user.users.avatar_url` wins as `account_avatar.source_url`; the auth
  profile URL is a fallback. An `avatar_media_id` becomes the owned asset ID only
  when #144 also supplies its exact object metadata and checksum.
- A row with `banned_at` becomes `SUSPENDED`; a row without it becomes `ACTIVE`.
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
| `auth.accounts` | `email` | PRESERVE | Provider metadata in `external_identity.provider_email`. |
| `auth.accounts` | `email_verified` | PRESERVE | Provider-scoped verification evidence. |
| `auth.accounts` | `name` | PRESERVE | `external_identity.display_name`; not stable identity. |
| `auth.accounts` | `picture_url` | PRESERVE | `external_identity.picture_url`; not an owned avatar asset. |
| `auth.accounts` | `created_at` | PRESERVE | Federated-link creation time. |
| `auth.accounts` | `last_login_at` | PRESERVE | Federated-link last-login time. |
| `auth.accounts` | `user_id` | PRESERVE | Resolve to canonical `account.account_id`. |
| `auth.users` | `id` | PRESERVE | Canonical stable `account.account_id` and OIDC `sub` source. |
| `auth.users` | `email` | PRESERVE | Canonical `account.email`; normalize for uniqueness. |
| `auth.users` | `email_verified` | PRESERVE | Canonical account-email verification state. |
| `auth.users` | `name` | PRESERVE | `account.display_name`. |
| `auth.users` | `picture_url` | PRESERVE | Fallback `account_avatar.source_url` only. |
| `auth.users` | `created_at` | PRESERVE | Canonical `account.created_at`. |
| `auth.users` | `last_login_at` | PRESERVE | `account.last_login_at`. |
| `auth.users` | `username` | PRESERVE | Fallback username subject to reconciliation rule. |
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
| `app_user.users` | `id` | PRESERVE | Must equal canonical account ID or reconciliation stops. |
| `app_user.users` | `email` | PRESERVE | Must match canonical normalized email; never creates a second identity. |
| `app_user.users` | `username` | PRESERVE | Preferred `account.username`. |
| `app_user.users` | `bio` | PRESERVE | `account.bio`. |
| `app_user.users` | `is_admin` | PRESERVE | Active account authorization state. |
| `app_user.users` | `avatar_url` | PRESERVE | Preferred `account_avatar.source_url`. |
| `app_user.users` | `created_at` | DELETE | Duplicate projection-row timestamp, not account creation. |
| `app_user.users` | `updated_at` | PRESERVE | `account.updated_at` profile timestamp. |
| `app_user.users` | `avatar_media_id` | PRESERVE | `account_avatar.asset_id`, conditional on exact asset metadata. |
| `app_user.users` | `admin_granted_by` | PRESERVE | `account.admin_granted_by`; must resolve to preserved account. |
| `app_user.users` | `admin_granted_at` | PRESERVE | `account.admin_granted_at`. |
| `app_user.users` | `banned_by` | PRESERVE | `account.suspended_by`; must resolve to preserved account. |
| `app_user.users` | `banned_at` | PRESERVE | `account.suspended_at` and `SUSPENDED` state. |
| `app_user.users` | `ban_reason` | PRESERVE | `account.suspension_reason`. |

Auth migrations V2–V6 only mutate registered-client rows or nullability and add
no source fields. No legacy migration is changed by this contract.
