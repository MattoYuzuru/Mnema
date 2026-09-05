package app.mnema.identityaccount.avatar;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.security.Secrets;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;

@Service
public class Avatars {
    public record Owned(UUID accountId, UUID assetId, String storageKey, String contentType, long byteSize,
                        byte[] contentSha256) {
    }

    public record Content(byte[] bytes, String type) {
    }

    private final JdbcClient jdbcClient;
    private final AccountStore accounts;
    private final AvatarStorage storage;
    private final TransactionTemplate transactions;

    public Avatars(JdbcClient jdbcClient, AccountStore accounts, AvatarStorage storage, TransactionTemplate transactions) {
        this.jdbcClient = jdbcClient;
        this.accounts = accounts;
        this.storage = storage;
        this.transactions = transactions;
    }

    public void replace(AccountAccess access, AvatarImage image) {
        accounts.require(access, false);
        UUID asset = UUID.randomUUID();
        String key = "account-avatar/" + access.accountId() + "/" + asset;
        // Commit ownership before any remote effect. A crash after PUT leaves a discoverable receipt.
        receipt(new Owned(access.accountId(), asset, key, image.contentType(), image.bytes().length,
                Secrets.digest(image.bytes())));
        transactions.executeWithoutResult(status -> {
            jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,142003))")
                    .param("key", key).query(result -> {
                        result.next();
                        return 0;
                    });
            boolean pending = jdbcClient.sql(
                            "SELECT EXISTS(SELECT 1 FROM app_identity.avatar_cleanup WHERE storage_key=:key)")
                    .param("key", key).query(Boolean.class).single();
            // A cleaner may win before this transaction acquires the key. Never PUT after it removed the intent.
            if (!pending) throw new AccountFailure(409, "avatar_upload_interrupted");
            storage.put(key, access.accountId(), asset, image);
            accounts.require(access, true);
            owned(access.accountId()).ifPresent(this::receipt);
            jdbcClient.sql("""
                            INSERT INTO app_identity.account_avatar(account_id,asset_id,storage_key,content_type,byte_size,content_sha256,width,height,created_at)
                            VALUES(:id,:asset,:key,:type,:size,:hash,:width,:height,statement_timestamp())
                            ON CONFLICT(account_id) DO UPDATE SET asset_id=:asset,storage_key=:key,content_type=:type,byte_size=:size,content_sha256=:hash,width=:width,height=:height,created_at=statement_timestamp()
                            """).param("id", access.accountId()).param("asset", asset).param("key", key)
                    .param("type", image.contentType()).param("size", image.bytes().length)
                    .param("hash", Secrets.digest(image.bytes())).param("width", image.width())
                    .param("height", image.height()).update();
            jdbcClient.sql("DELETE FROM app_identity.avatar_cleanup WHERE storage_key=:key").param("key", key).update();
        });
        retryCleanup();
    }

    public void remove(AccountAccess access) {
        transactions.executeWithoutResult(s -> {
            accounts.require(access, true);
            owned(access.accountId()).ifPresent(this::receipt);
            jdbcClient.sql("DELETE FROM app_identity.account_avatar WHERE account_id=:id").param("id", access.accountId())
                    .update();
        });
        retryCleanup();
    }

    public Content read(UUID id) {
        if (accounts.find(id, false).filter(account -> account.status().equals("ACTIVE")).isEmpty())
            throw new AccountFailure(404, "avatar_not_found");
        var avatar = owned(id).orElseThrow(() -> new AccountFailure(404, "avatar_not_found"));
        byte[] bytes = storage.get(avatar.storageKey());
        if (bytes.length != avatar.byteSize() || !MessageDigest.isEqual(Secrets.digest(bytes), avatar.contentSha256()))
            throw new AccountFailure(503, "avatar_storage_invalid");
        return new Content(bytes, avatar.contentType());
    }

    public Optional<Owned> owned(UUID id) {
        return jdbcClient.sql(
                        "SELECT account_id,asset_id,storage_key,content_type,byte_size,content_sha256 FROM app_identity.account_avatar WHERE account_id=:id")
                .param("id", id).query(Owned.class).optional();
    }

    /**
     * Exact ownership receipts are also the #157 cleanup hook; never accept caller-supplied object keys.
     */
    private void receipt(Owned owned) {
        jdbcClient.sql(
                        "INSERT INTO app_identity.avatar_cleanup(storage_key,account_id,asset_id) VALUES(:key,:id,:asset) ON CONFLICT(storage_key) DO NOTHING")
                .param("key", owned.storageKey()).param("id", owned.accountId()).param("asset", owned.assetId())
                .update();
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void retryCleanup() {
        transactions.executeWithoutResult(s -> {
            var keys = jdbcClient.sql(
                            "SELECT storage_key FROM app_identity.avatar_cleanup ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED")
                    .query(String.class).list();
            for (String key : keys) {
                boolean acquired = jdbcClient.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:key,142003))")
                        .param("key", key).query(Boolean.class).single();
                if (!acquired) continue;
                boolean referenced = jdbcClient.sql(
                                "SELECT EXISTS(SELECT 1 FROM app_identity.account_avatar WHERE storage_key=:key)")
                        .param("key", key).query(Boolean.class).single();
                if (!referenced && storage.delete(key))
                    jdbcClient.sql("DELETE FROM app_identity.avatar_cleanup WHERE storage_key=:key").param("key", key)
                            .update();
            }
        });
    }
}
