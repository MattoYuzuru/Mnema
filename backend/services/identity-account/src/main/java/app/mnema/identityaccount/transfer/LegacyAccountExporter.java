package app.mnema.identityaccount.transfer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class LegacyAccountExporter {
    private final JdbcClient source;
    private final AvatarBlobStore avatars;
    private final TransactionTemplate transaction;

    LegacyAccountExporter(DataSource sourceDataSource, AvatarBlobStore avatars) {
        source = JdbcClient.create(sourceDataSource);
        this.avatars = avatars;
        transaction = new TransactionTemplate(new DataSourceTransactionManager(sourceDataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
    }

    AccountTransferArtifact export() {
        try {
            AccountTransferArtifact artifact = transaction.execute(status -> exportSnapshot());
            if (artifact == null) throw new AccountTransferFailure("source_snapshot_failed");
            return artifact;
        } catch (AccountTransferFailure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw new AccountTransferFailure("source_export_failed", exception);
        }
    }

    private AccountTransferArtifact exportSnapshot() {
        int serverVersion = source.sql("SHOW server_version_num").query(Integer.class).single();
        AccountTransferBundle.require(serverVersion >= 160000 && serverVersion < 170000,
                "unsupported_source_postgres");
        validateSourceBoundary();

        Map<UUID, AccountTransferBundle.Credential> credentials = credentials();
        Map<UUID, List<AccountTransferBundle.ExternalIdentity>> identities = identities();
        Map<UUID, SourceAvatar> sourceAvatars = sourceAvatars();
        Map<UUID, byte[]> avatarBlobs = new LinkedHashMap<>();
        List<AccountTransferBundle.Account> accounts = source.sql("""
                        SELECT a.id,a.email,a.email_verified,a.name,a.created_at,a.last_login_at,
                               p.username,p.bio,p.is_admin,p.created_at AS profile_created_at,
                               p.admin_granted_by,p.admin_granted_at,p.banned_by,p.banned_at,p.ban_reason
                        FROM auth.users a
                        JOIN app_user.users p ON p.id=a.id
                        ORDER BY a.id
                        """)
                .query((row, index) -> {
                    UUID accountId = row.getObject("id", UUID.class);
                    SourceAvatar sourceAvatar = sourceAvatars.get(accountId);
                    AccountTransferBundle.Avatar avatar = null;
                    if (sourceAvatar != null) {
                        byte[] bytes = avatars.read(sourceAvatar.storageKey());
                        String digest = AvatarBinary.sha256(bytes);
                        avatar = new AccountTransferBundle.Avatar(sourceAvatar.assetId(), sourceAvatar.contentType(),
                                sourceAvatar.byteSize(), digest, sourceAvatar.width(), sourceAvatar.height(),
                                sourceAvatar.createdAt());
                        AvatarBinary.validate(avatar, bytes);
                        avatarBlobs.put(avatar.assetId(), bytes);
                    }
                    Instant bannedAt = instant(row, "banned_at");
                    return new AccountTransferBundle.Account(
                            accountId,
                            row.getString("email"),
                            row.getBoolean("email_verified"),
                            row.getString("username"),
                            row.getString("name"),
                            row.getString("bio"),
                            bannedAt == null ? "ACTIVE" : "BANNED",
                            row.getBoolean("is_admin"),
                            row.getObject("admin_granted_by", UUID.class),
                            instant(row, "admin_granted_at"),
                            row.getObject("banned_by", UUID.class),
                            bannedAt,
                            row.getString("ban_reason"),
                            instant(row, "created_at"),
                            instant(row, "profile_created_at"),
                            instant(row, "last_login_at"),
                            credentials.get(accountId),
                            identities.getOrDefault(accountId, List.of()),
                            avatar);
                }).list();
        return new AccountTransferArtifact(
                new AccountTransferBundle(AccountTransferBundle.SCHEMA_VERSION, AccountTransferBundle.KIND, accounts),
                avatarBlobs);
    }

    private void validateSourceBoundary() {
        long accounts = count("SELECT count(*) FROM auth.users");
        long profiles = count("SELECT count(*) FROM app_user.users");
        long reconciled = count("""
                SELECT count(*) FROM auth.users a JOIN app_user.users p ON p.id=a.id
                WHERE lower(btrim(a.email))=lower(btrim(p.email))
                """);
        AccountTransferBundle.require(accounts == profiles && accounts == reconciled, "account_profile_mismatch");
        requireZero("SELECT count(*) FROM auth.users GROUP BY lower(btrim(email)) HAVING count(*)>1",
                "duplicate_account_email");
        requireZero("SELECT count(*) FROM app_user.users GROUP BY lower(btrim(username)) HAVING count(*)>1",
                "duplicate_profile_username");
        requireZero("""
                SELECT count(*) FROM auth.users WHERE username IS NOT NULL
                GROUP BY lower(btrim(username)) HAVING count(*)>1
                """, "duplicate_login_name");
        requireZero("""
                SELECT count(*) FROM auth.accounts
                GROUP BY lower(btrim(provider)),provider_sub HAVING count(*)>1
                """, "duplicate_external_identity");
        requireZero("""
                SELECT count(*) FROM auth.accounts x
                LEFT JOIN auth.users a ON a.id=x.user_id WHERE a.id IS NULL
                """, "orphan_external_identity");
        requireZero("""
                SELECT count(*) FROM app_user.users p
                LEFT JOIN auth.users a ON a.id=p.admin_granted_by
                WHERE p.admin_granted_by IS NOT NULL AND a.id IS NULL
                """, "missing_admin_actor");
        requireZero("""
                SELECT count(*) FROM app_user.users p
                LEFT JOIN auth.users a ON a.id=p.banned_by
                WHERE p.banned_by IS NOT NULL AND a.id IS NULL
                """, "missing_ban_actor");
        requireZero("""
                SELECT count(*) FROM app_user.users
                WHERE (is_admin AND banned_at IS NOT NULL)
                   OR (NOT is_admin AND (admin_granted_by IS NOT NULL OR admin_granted_at IS NOT NULL))
                   OR ((admin_granted_by IS NULL) <> (admin_granted_at IS NULL))
                   OR (banned_at IS NULL AND (banned_by IS NOT NULL OR ban_reason IS NOT NULL))
                """, "invalid_moderation_metadata");
        requireZero("""
                SELECT count(*) FROM app_user.users p
                LEFT JOIN app_media.media_assets m ON m.media_id=p.avatar_media_id
                WHERE p.avatar_media_id IS NOT NULL AND (
                    m.media_id IS NULL OR m.owner_user_id<>p.id OR m.kind::text<>'avatar'
                    OR m.status::text<>'ready' OR m.deleted_at IS NOT NULL
                    OR m.mime_type NOT IN ('image/jpeg','image/png','image/webp')
                    OR m.size_bytes IS NULL OR m.size_bytes NOT BETWEEN 1 AND 10485760
                    OR ((m.width IS NULL) <> (m.height IS NULL))
                    OR (m.width IS NOT NULL AND (m.width NOT BETWEEN 1 AND 1024 OR m.height NOT BETWEEN 1 AND 1024))
                )
                """, "invalid_avatar_metadata");
        requireZero("""
                SELECT count(*) FROM app_user.users WHERE avatar_media_id IS NOT NULL
                GROUP BY avatar_media_id HAVING count(*)>1
                """, "duplicate_avatar_asset");
    }

    private Map<UUID, AccountTransferBundle.Credential> credentials() {
        Map<UUID, AccountTransferBundle.Credential> result = new HashMap<>();
        source.sql("SELECT id,username,password_hash FROM auth.users WHERE password_hash IS NOT NULL ORDER BY id")
                .query((row, index) -> Map.entry(row.getObject("id", UUID.class),
                        new AccountTransferBundle.Credential(row.getString("username"), row.getString("password_hash"))))
                .list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private Map<UUID, List<AccountTransferBundle.ExternalIdentity>> identities() {
        Map<UUID, List<AccountTransferBundle.ExternalIdentity>> result = new HashMap<>();
        source.sql("""
                        SELECT user_id,lower(btrim(provider)) AS provider,provider_sub,created_at,last_login_at
                        FROM auth.accounts ORDER BY user_id,lower(btrim(provider)),provider_sub
                        """)
                .query((row, index) -> Map.entry(row.getObject("user_id", UUID.class),
                        new AccountTransferBundle.ExternalIdentity(row.getString("provider"),
                                row.getString("provider_sub"), instant(row, "created_at"),
                                instant(row, "last_login_at"))))
                .list().forEach(entry -> result.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(entry.getValue()));
        result.values().forEach(values -> values.sort(Comparator.comparing(AccountTransferBundle.ExternalIdentity::provider)
                .thenComparing(AccountTransferBundle.ExternalIdentity::providerSubject)));
        return result;
    }

    private Map<UUID, SourceAvatar> sourceAvatars() {
        Map<UUID, SourceAvatar> result = new HashMap<>();
        source.sql("""
                        SELECT p.id,m.media_id,m.storage_key,m.mime_type,m.size_bytes,m.width,m.height,m.created_at
                        FROM app_user.users p JOIN app_media.media_assets m ON m.media_id=p.avatar_media_id
                        ORDER BY p.id
                        """)
                .query((row, index) -> Map.entry(row.getObject("id", UUID.class), new SourceAvatar(
                        row.getObject("media_id", UUID.class), row.getString("storage_key"), row.getString("mime_type"),
                        row.getLong("size_bytes"), integer(row, "width"), integer(row, "height"),
                        instant(row, "created_at"))))
                .list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private void requireZero(String sql, String code) {
        AccountTransferBundle.require(source.sql(
                "SELECT COALESCE(sum(invalid_count),0)=0 FROM (" + sql + ") invalid(invalid_count)")
                .query(Boolean.class).single(), code);
    }

    private long count(String sql) {
        return source.sql(sql).query(Long.class).single();
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Integer integer(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private record SourceAvatar(UUID assetId, String storageKey, String contentType, long byteSize,
                                Integer width, Integer height, Instant createdAt) {
    }
}
