package app.mnema.identityaccount.transfer;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class AccountTransferImporter {
    private final JdbcClient target;
    private final AvatarBlobStore avatars;
    private final TransactionTemplate transaction;
    private final AccountTransferCodec codec;

    AccountTransferImporter(DataSource targetDataSource, AvatarBlobStore avatars, AccountTransferCodec codec) {
        target = JdbcClient.create(targetDataSource);
        this.avatars = avatars;
        this.codec = codec;
        transaction = new TransactionTemplate(new DataSourceTransactionManager(targetDataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    AccountTransferEvidence importAndReconcile(AccountTransferArtifact artifact) {
        validateTargetBoundary();
        List<String> receipts = prepareAvatarReceipts(artifact);
        try {
            for (AccountTransferBundle.Account account : artifact.bundle().accounts()) {
                if (account.avatar() == null) continue;
                String key = storageKey(account);
                avatars.putExact(key, artifact.avatar(account.avatar().assetId()));
            }
            transaction.executeWithoutResult(status -> {
                insertAccounts(artifact.bundle());
                assertExactTargetShape(artifact.bundle());
                for (String key : receipts)
                    target.sql("DELETE FROM app_identity.avatar_cleanup WHERE storage_key=:key")
                            .param("key", key).update();
            });
            return reconcile(artifact);
        } catch (RuntimeException failure) {
            compensateAvatars(receipts, failure);
            if (failure instanceof AccountTransferFailure transferFailure) throw transferFailure;
            throw new AccountTransferFailure("target_import_failed", failure);
        }
    }

    AccountTransferEvidence reconcile(AccountTransferArtifact expected) {
        try {
            validateTargetBoundary();
            AccountTransferArtifact actual = readTarget();
            AccountTransferBundle.require(expected.bundle().equals(actual.bundle()), "target_projection_mismatch");
            for (AccountTransferBundle.Account account : expected.bundle().accounts()) {
                if (account.avatar() == null) continue;
                byte[] expectedBytes = expected.avatar(account.avatar().assetId());
                byte[] actualBytes = actual.avatar(account.avatar().assetId());
                AccountTransferBundle.require(java.security.MessageDigest.isEqual(expectedBytes, actualBytes),
                        "target_avatar_mismatch");
            }
            byte[] projection = codec.canonicalProjection(expected.bundle());
            int credentials = 0;
            int identities = 0;
            int avatarCount = 0;
            long avatarBytes = 0;
            ByteArrayOutputStream avatarSet = new ByteArrayOutputStream();
            for (AccountTransferBundle.Account account : expected.bundle().accounts()) {
                if (account.credential() != null) credentials++;
                identities += account.externalIdentities().size();
                if (account.avatar() != null) {
                    avatarCount++;
                    avatarBytes += account.avatar().byteSize();
                    avatarSet.writeBytes(account.avatar().assetId().toString().getBytes(StandardCharsets.US_ASCII));
                    avatarSet.write(0);
                    avatarSet.writeBytes(account.avatar().contentSha256().getBytes(StandardCharsets.US_ASCII));
                    avatarSet.write(0);
                }
            }
            return new AccountTransferEvidence(1, "account-transfer-reconciliation", "reconciled",
                    expected.bundle().accounts().size(), credentials, identities, avatarCount, avatarBytes,
                    AvatarBinary.sha256(projection), AvatarBinary.sha256(avatarSet.toByteArray()));
        } catch (AccountTransferFailure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw new AccountTransferFailure("target_reconciliation_failed", exception);
        }
    }

    private void validateTargetBoundary() {
        try {
            int serverVersion = target.sql("SHOW server_version_num").query(Integer.class).single();
            AccountTransferBundle.require(serverVersion >= 180000 && serverVersion < 190000,
                    "unsupported_target_postgres");
            requireZero("app_identity.spring_session", "target_contains_sessions");
            requireZero("app_identity.spring_session_attributes", "target_contains_sessions");
            requireZero("app_identity.oauth2_authorization", "target_contains_grants");
            requireZero("app_identity.oauth2_authorization_consent", "target_contains_grants");
            requireZero("app_identity.ownership_challenge", "target_contains_challenges");
            requireZero("app_identity.rate_limit", "target_contains_transient_state");
        } catch (AccountTransferFailure failure) {
            throw failure;
        } catch (DataAccessException exception) {
            throw new AccountTransferFailure("target_schema_unavailable", exception);
        }
    }

    private List<String> prepareAvatarReceipts(AccountTransferArtifact artifact) {
        List<String> keys = new ArrayList<>();
        transaction.executeWithoutResult(status -> {
            for (AccountTransferBundle.Account account : artifact.bundle().accounts()) {
                if (account.avatar() == null) continue;
                String key = storageKey(account);
                keys.add(key);
                target.sql("""
                                INSERT INTO app_identity.avatar_cleanup(storage_key,account_id,asset_id)
                                VALUES(:key,:account,:asset) ON CONFLICT(storage_key) DO NOTHING
                                """)
                        .param("key", key).param("account", account.accountId())
                        .param("asset", account.avatar().assetId()).update();
            }
        });
        return keys;
    }

    private void insertAccounts(AccountTransferBundle bundle) {
        for (AccountTransferBundle.Account account : bundle.accounts()) {
            target.sql("""
                            INSERT INTO app_identity.account(
                                account_id,email,email_verified,profile_username,display_name,bio,status,is_admin,
                                created_at,profile_created_at,updated_at,last_login_at)
                            VALUES(:id,:email,:verified,:username,:display,:bio,:status,:admin,
                                :created,:profileCreated,statement_timestamp(),:lastLogin)
                            ON CONFLICT(account_id) DO NOTHING
                            """)
                    .param("id", account.accountId()).param("email", account.email())
                    .param("verified", account.emailVerified()).param("username", account.profileUsername())
                    .param("display", account.displayName()).param("bio", account.bio())
                    .param("status", "ACTIVE").param("admin", account.admin())
                    .param("created", offset(account.createdAt())).param("profileCreated", offset(account.profileCreatedAt()))
                    .param("lastLogin", offset(account.lastLoginAt())).update();
        }
        for (AccountTransferBundle.Account account : bundle.accounts()) {
            target.sql("""
                            UPDATE app_identity.account SET status=:status,
                                admin_granted_by=:adminBy,admin_granted_at=:adminAt,
                                banned_by=:bannedBy,banned_at=:bannedAt,ban_reason=:reason
                            WHERE account_id=:id
                            """)
                    .param("status", account.status()).param("adminBy", account.adminGrantedBy())
                    .param("adminAt", offset(account.adminGrantedAt()))
                    .param("bannedBy", account.bannedBy()).param("bannedAt", offset(account.bannedAt()))
                    .param("reason", account.banReason()).param("id", account.accountId()).update();
            if (account.credential() != null) insertCredential(account);
            for (AccountTransferBundle.ExternalIdentity identity : account.externalIdentities())
                insertIdentity(account.accountId(), identity);
            if (account.avatar() != null) insertAvatar(account);
        }
    }

    private void insertCredential(AccountTransferBundle.Account account) {
        target.sql("""
                        INSERT INTO app_identity.local_credential(
                            account_id,login_name,password_hash,failed_login_attempts,locked_until,created_at,updated_at)
                        VALUES(:id,:login,:hash,0,NULL,:created,:created)
                        ON CONFLICT(account_id) DO NOTHING
                        """)
                .param("id", account.accountId()).param("login", account.credential().loginName())
                .param("hash", account.credential().passwordHash()).param("created", offset(account.createdAt())).update();
    }

    private void insertIdentity(UUID accountId, AccountTransferBundle.ExternalIdentity identity) {
        UUID identityId = UUID.nameUUIDFromBytes(
                ("mnema:external:" + identity.provider() + "\u0000" + identity.providerSubject())
                        .getBytes(StandardCharsets.UTF_8));
        target.sql("""
                        INSERT INTO app_identity.external_identity(
                            identity_id,account_id,provider,provider_subject,linked_at,last_login_at)
                        VALUES(:identity,:account,:provider,:subject,:linked,:lastLogin)
                        ON CONFLICT(provider,provider_subject) DO NOTHING
                        """)
                .param("identity", identityId).param("account", accountId).param("provider", identity.provider())
                .param("subject", identity.providerSubject()).param("linked", offset(identity.linkedAt()))
                .param("lastLogin", offset(identity.lastLoginAt())).update();
    }

    private void insertAvatar(AccountTransferBundle.Account account) {
        AccountTransferBundle.Avatar avatar = account.avatar();
        target.sql("""
                        INSERT INTO app_identity.account_avatar(
                            account_id,asset_id,storage_key,content_type,byte_size,content_sha256,width,height,created_at)
                        VALUES(:account,:asset,:key,:type,:size,:hash,:width,:height,:created)
                        ON CONFLICT(account_id) DO NOTHING
                        """)
                .param("account", account.accountId()).param("asset", avatar.assetId())
                .param("key", storageKey(account)).param("type", avatar.contentType()).param("size", avatar.byteSize())
                .param("hash", HexFormat.of().parseHex(avatar.contentSha256())).param("width", avatar.width())
                .param("height", avatar.height()).param("created", offset(avatar.createdAt())).update();
    }

    private void assertExactTargetShape(AccountTransferBundle expected) {
        AccountTransferArtifact actual = readTarget();
        AccountTransferBundle.require(expected.equals(actual.bundle()), "target_projection_mismatch");
        AccountTransferBundle.require(count("app_identity.account") == expected.accounts().size(), "target_account_count_mismatch");
        AccountTransferBundle.require(count("app_identity.local_credential") == expected.accounts().stream()
                .filter(account -> account.credential() != null).count(), "target_credential_count_mismatch");
        AccountTransferBundle.require(count("app_identity.external_identity") == expected.accounts().stream()
                .mapToLong(account -> account.externalIdentities().size()).sum(), "target_identity_count_mismatch");
        AccountTransferBundle.require(count("app_identity.account_avatar") == expected.accounts().stream()
                .filter(account -> account.avatar() != null).count(), "target_avatar_count_mismatch");
    }

    private AccountTransferArtifact readTarget() {
        Map<UUID, AccountTransferBundle.Credential> credentials = targetCredentials();
        Map<UUID, List<AccountTransferBundle.ExternalIdentity>> identities = targetIdentities();
        Map<UUID, AccountTransferBundle.Avatar> avatarMetadata = targetAvatars();
        Map<UUID, byte[]> avatarBlobs = new LinkedHashMap<>();
        List<AccountTransferBundle.Account> accounts = target.sql("""
                        SELECT account_id,email,email_verified,profile_username,display_name,bio,status,is_admin,
                               admin_granted_by,admin_granted_at,banned_by,banned_at,ban_reason,
                               created_at,profile_created_at,last_login_at,security_generation,row_version
                        FROM app_identity.account ORDER BY account_id
                        """)
                .query((row, index) -> {
                    AccountTransferBundle.require(row.getLong("security_generation") == 0 && row.getLong("row_version") == 0,
                            "target_account_generation_mismatch");
                    UUID accountId = row.getObject("account_id", UUID.class);
                    AccountTransferBundle.Avatar avatar = avatarMetadata.get(accountId);
                    if (avatar != null) {
                        byte[] bytes = avatars.read("account-avatar/" + accountId + "/" + avatar.assetId());
                        AvatarBinary.validate(avatar, bytes);
                        avatarBlobs.put(avatar.assetId(), bytes);
                    }
                    return new AccountTransferBundle.Account(accountId, row.getString("email"),
                            row.getBoolean("email_verified"), row.getString("profile_username"),
                            row.getString("display_name"), row.getString("bio"), row.getString("status"),
                            row.getBoolean("is_admin"), row.getObject("admin_granted_by", UUID.class),
                            instant(row, "admin_granted_at"), row.getObject("banned_by", UUID.class),
                            instant(row, "banned_at"), row.getString("ban_reason"), instant(row, "created_at"),
                            instant(row, "profile_created_at"), instant(row, "last_login_at"),
                            credentials.get(accountId), identities.getOrDefault(accountId, List.of()), avatar);
                }).list();
        return new AccountTransferArtifact(
                new AccountTransferBundle(AccountTransferBundle.SCHEMA_VERSION, AccountTransferBundle.KIND, accounts),
                avatarBlobs);
    }

    private Map<UUID, AccountTransferBundle.Credential> targetCredentials() {
        Map<UUID, AccountTransferBundle.Credential> result = new HashMap<>();
        target.sql("""
                        SELECT account_id,login_name,password_hash,failed_login_attempts,locked_until
                        FROM app_identity.local_credential ORDER BY account_id
                        """)
                .query((row, index) -> {
                    AccountTransferBundle.require(row.getInt("failed_login_attempts") == 0
                            && row.getObject("locked_until") == null, "target_credential_state_mismatch");
                    return Map.entry(row.getObject("account_id", UUID.class),
                            new AccountTransferBundle.Credential(row.getString("login_name"), row.getString("password_hash")));
                }).list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private Map<UUID, List<AccountTransferBundle.ExternalIdentity>> targetIdentities() {
        Map<UUID, List<AccountTransferBundle.ExternalIdentity>> result = new HashMap<>();
        target.sql("""
                        SELECT account_id,provider,provider_subject,linked_at,last_login_at
                        FROM app_identity.external_identity ORDER BY account_id,provider,provider_subject
                        """)
                .query((row, index) -> Map.entry(row.getObject("account_id", UUID.class),
                        new AccountTransferBundle.ExternalIdentity(row.getString("provider"),
                                row.getString("provider_subject"), instant(row, "linked_at"),
                                instant(row, "last_login_at"))))
                .list().forEach(entry -> result.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(entry.getValue()));
        result.values().forEach(values -> values.sort(Comparator.comparing(AccountTransferBundle.ExternalIdentity::provider)
                .thenComparing(AccountTransferBundle.ExternalIdentity::providerSubject)));
        return result;
    }

    private Map<UUID, AccountTransferBundle.Avatar> targetAvatars() {
        Map<UUID, AccountTransferBundle.Avatar> result = new HashMap<>();
        target.sql("""
                        SELECT account_id,asset_id,storage_key,content_type,byte_size,content_sha256,width,height,created_at
                        FROM app_identity.account_avatar ORDER BY account_id
                        """)
                .query((row, index) -> {
                    UUID accountId = row.getObject("account_id", UUID.class);
                    UUID assetId = row.getObject("asset_id", UUID.class);
                    AccountTransferBundle.require(row.getString("storage_key").equals(
                            "account-avatar/" + accountId + "/" + assetId), "target_avatar_key_mismatch");
                    return Map.entry(accountId, new AccountTransferBundle.Avatar(assetId,
                            row.getString("content_type"), row.getLong("byte_size"),
                            HexFormat.of().formatHex(row.getBytes("content_sha256")), integer(row, "width"),
                            integer(row, "height"), instant(row, "created_at")));
                }).list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private void requireZero(String table, String code) {
        AccountTransferBundle.require(count(table) == 0, code);
    }

    private long count(String table) {
        return target.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private void compensateAvatars(List<String> receipts, RuntimeException original) {
        for (String key : receipts) {
            try {
                boolean referenced = target.sql(
                                "SELECT EXISTS(SELECT 1 FROM app_identity.account_avatar WHERE storage_key=:key)")
                        .param("key", key).query(Boolean.class).single();
                if (!referenced) avatars.deleteExact(key);
                transaction.executeWithoutResult(status ->
                        target.sql("DELETE FROM app_identity.avatar_cleanup WHERE storage_key=:key")
                                .param("key", key).update());
            } catch (RuntimeException cleanup) {
                original.addSuppressed(cleanup);
            }
        }
    }

    private static String storageKey(AccountTransferBundle.Account account) {
        return "account-avatar/" + account.accountId() + "/" + account.avatar().assetId();
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(java.time.ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Integer integer(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }
}
