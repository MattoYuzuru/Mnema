package app.mnema.identityaccount.transfer;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record AccountTransferBundle(int schemaVersion, String kind, List<Account> accounts) {
    public static final int SCHEMA_VERSION = 1;
    public static final String KIND = "mnema-account-transfer";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    public AccountTransferBundle {
        require(schemaVersion == SCHEMA_VERSION, "unsupported_schema");
        require(KIND.equals(kind), "unsupported_kind");
        accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
        validate(accounts);
    }

    public record Account(
            UUID accountId,
            String email,
            boolean emailVerified,
            String profileUsername,
            String displayName,
            String bio,
            String status,
            boolean admin,
            UUID adminGrantedBy,
            Instant adminGrantedAt,
            UUID bannedBy,
            Instant bannedAt,
            String banReason,
            Instant createdAt,
            Instant profileCreatedAt,
            Instant lastLoginAt,
            Credential credential,
            List<ExternalIdentity> externalIdentities,
            Avatar avatar) {
        public Account {
            require(validId(accountId), "invalid_account_id");
            require(nonBlank(email) && email.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 320,
                    "invalid_email");
            require(optionalLength(profileUsername, 50), "invalid_profile_username");
            require(optionalLength(displayName, 200), "invalid_display_name");
            require(bio == null || bio.length() <= 200, "invalid_bio");
            require(Set.of("ACTIVE", "BANNED").contains(status), "invalid_status");
            require(createdAt != null, "missing_created_at");
            require(profileCreatedAt == null || !profileCreatedAt.isBefore(createdAt), "invalid_profile_created_at");
            require(lastLoginAt == null || !lastLoginAt.isBefore(createdAt), "invalid_last_login_at");
            require(!accountId.equals(adminGrantedBy) && !accountId.equals(bannedBy), "self_moderation_actor");
            require((!admin && adminGrantedBy == null && adminGrantedAt == null)
                            || (admin && ((adminGrantedBy == null && adminGrantedAt == null)
                            || (adminGrantedBy != null && adminGrantedAt != null))),
                    "invalid_admin_metadata");
            require(("ACTIVE".equals(status) && bannedBy == null && bannedAt == null && banReason == null)
                            || ("BANNED".equals(status) && bannedAt != null),
                    "invalid_ban_metadata");
            require(!admin || "ACTIVE".equals(status), "invalid_admin_status");
            require(banReason == null || banReason.length() <= 280, "invalid_ban_reason");
            externalIdentities = List.copyOf(Objects.requireNonNull(externalIdentities, "externalIdentities"));
        }
    }

    public record Credential(String loginName, String passwordHash) {
        public Credential {
            require(optionalLength(loginName, 50), "invalid_login_name");
            require(nonBlank(passwordHash) && passwordHash.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4096,
                    "invalid_password_hash");
        }
    }

    public record ExternalIdentity(
            String provider,
            String providerSubject,
            Instant linkedAt,
            Instant lastLoginAt) {
        public ExternalIdentity {
            require(nonBlank(provider) && provider.length() <= 64
                            && provider.equals(provider.trim().toLowerCase(Locale.ROOT))
                            && provider.matches("[a-z][a-z0-9._-]{0,63}"),
                    "invalid_provider");
            require(nonBlank(providerSubject)
                            && providerSubject.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 255,
                    "invalid_provider_subject");
            require(linkedAt != null, "missing_linked_at");
            require(lastLoginAt == null || !lastLoginAt.isBefore(linkedAt), "invalid_identity_last_login_at");
        }
    }

    public record Avatar(
            UUID assetId,
            String contentType,
            long byteSize,
            String contentSha256,
            Integer width,
            Integer height,
            Instant createdAt) {
        public Avatar {
            require(validId(assetId), "invalid_asset_id");
            require(CONTENT_TYPES.contains(contentType), "invalid_avatar_content_type");
            require(byteSize >= 1 && byteSize <= 10L * 1024 * 1024, "invalid_avatar_size");
            require(contentSha256 != null && SHA256.matcher(contentSha256).matches(), "invalid_avatar_hash");
            require((width == null && height == null)
                            || (width != null && height != null && width >= 1 && width <= 1024
                            && height >= 1 && height <= 1024),
                    "invalid_avatar_dimensions");
            require(createdAt != null, "missing_avatar_created_at");
        }
    }

    private static void validate(List<Account> accounts) {
        Set<UUID> ids = new HashSet<>();
        Set<String> emails = new HashSet<>();
        Set<String> profileNames = new HashSet<>();
        Set<String> loginNames = new HashSet<>();
        Set<String> identities = new HashSet<>();
        Set<UUID> assets = new HashSet<>();
        UUID previousAccount = null;
        for (Account account : accounts) {
            require(previousAccount == null
                            || previousAccount.toString().compareTo(account.accountId().toString()) < 0,
                    "non_canonical_account_order");
            previousAccount = account.accountId();
            require(ids.add(account.accountId()), "duplicate_account_id");
            require(emails.add(normalize(account.email())), "duplicate_email");
            if (account.profileUsername() != null)
                require(profileNames.add(normalize(account.profileUsername())), "duplicate_profile_username");
            if (account.credential() != null && account.credential().loginName() != null)
                require(loginNames.add(normalize(account.credential().loginName())), "duplicate_login_name");
            String previousIdentity = null;
            for (ExternalIdentity identity : account.externalIdentities()) {
                String identityKey = identity.provider() + "\u0000" + identity.providerSubject();
                require(previousIdentity == null || previousIdentity.compareTo(identityKey) < 0,
                        "non_canonical_identity_order");
                previousIdentity = identityKey;
                require(identities.add(identity.provider() + "\u0000" + identity.providerSubject()),
                        "duplicate_external_identity");
            }
            if (account.avatar() != null) require(assets.add(account.avatar().assetId()), "duplicate_avatar_asset");
        }
        for (Account account : accounts) {
            require(account.adminGrantedBy() == null || ids.contains(account.adminGrantedBy()), "missing_admin_actor");
            require(account.bannedBy() == null || ids.contains(account.bannedBy()), "missing_ban_actor");
        }
    }

    static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    static boolean validId(UUID id) {
        return id != null && !id.equals(new UUID(0, 0)) && id.version() >= 1 && id.version() <= 8;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean optionalLength(String value, int maximum) {
        return value == null || (!value.trim().isEmpty() && value.length() <= maximum);
    }

    static void require(boolean condition, String code) {
        if (!condition) throw new AccountTransferFailure(code);
    }
}
