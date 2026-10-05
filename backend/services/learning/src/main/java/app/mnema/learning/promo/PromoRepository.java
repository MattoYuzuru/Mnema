package app.mnema.learning.promo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of the promo tables. Time is always passed in (the usage clock), never read from the database. No method opens a transaction: the services do.
 * A code is looked up by the SHA-256 of its normalized text only; no query selects or logs a plain code.
 *
 * <p>TODO(account-deletion task; owner: the epic that adds Learning's account purge, see {@code UsageRepository}): promo_attempt,
 * promo_popup_state, promo_discount and promo_redemption carry an {@code owner_id}. The first three hold no more than an account id and
 * keyed hashes and are deleted by it; promo_redemption is the redemption audit, so whether it is deleted or anonymised (owner_id to a
 * tombstone, keeping the hashes) is a retention decision for that task, together with billing (#79).
 */
@Repository
class PromoRepository {
    /** A code as stored; {@code plan}, {@code validUntil} and {@code channel} may be null. */
    record Code(UUID codeId, String hint, PromoType type, String plan, Integer days, Integer months, Integer percent,
                Instant validFrom, Instant validUntil, int maxRedemptions, boolean oncePerAccount, String channel,
                boolean enabled, Instant createdAt, UUID createdBy) { }

    /** A code with the number of redemptions it has had. */
    record CodeWithCount(Code code, long redemptions) { }

    /** The pending discount of an account. */
    record Discount(int percent, String plan, Instant validUntil) { }

    private static final String CODE_COLUMNS = "code_id,code_hint,type,plan,days,months,percent,valid_from,valid_until,"
            + "max_redemptions,once_per_account,channel,enabled,created_at,created_by";

    private final JdbcClient jdbc;

    PromoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp time(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Code code(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        Timestamp until = row.getTimestamp("valid_until");
        return new Code(row.getObject("code_id", UUID.class), row.getString("code_hint"), PromoType.valueOf(row.getString("type")),
                row.getString("plan"), (Integer) row.getObject("days"), (Integer) row.getObject("months"),
                (Integer) row.getObject("percent"), row.getTimestamp("valid_from").toInstant(),
                until == null ? null : until.toInstant(), row.getInt("max_redemptions"), row.getBoolean("once_per_account"),
                row.getString("channel"), row.getBoolean("enabled"), row.getTimestamp("created_at").toInstant(),
                row.getObject("created_by", UUID.class));
    }

    /** @return whether the code was new; false when its hash is taken */
    boolean insertCode(Code code, byte[] hash) {
        return jdbc.sql("INSERT INTO app_learning.promo_code(code_id,code_hash,code_hint,type,plan,days,months,percent,valid_from,"
                        + "valid_until,max_redemptions,once_per_account,channel,enabled,created_at,created_by) VALUES (:id,:hash,:hint,"
                        + ":type,:plan,:days,:months,:percent,:from,:until,:max,:once,:channel,:enabled,:created,:by) "
                        + "ON CONFLICT (code_hash) DO NOTHING")
                .param("id", code.codeId()).param("hash", hash).param("hint", code.hint()).param("type", code.type().name())
                .param("plan", code.plan(), java.sql.Types.VARCHAR).param("days", code.days(), java.sql.Types.INTEGER)
                .param("months", code.months(), java.sql.Types.INTEGER).param("percent", code.percent(), java.sql.Types.INTEGER)
                .param("from", time(code.validFrom())).param("until", time(code.validUntil()), java.sql.Types.TIMESTAMP)
                .param("max", code.maxRedemptions()).param("once", code.oncePerAccount())
                .param("channel", code.channel(), java.sql.Types.VARCHAR).param("enabled", code.enabled())
                .param("created", time(code.createdAt())).param("by", code.createdBy()).update() == 1;
    }

    /** Locks the code row: redemptions of one code are serialized here, so {@code max_redemptions} is exact under concurrency. */
    Optional<Code> lockByHash(byte[] hash) {
        return jdbc.sql("SELECT " + CODE_COLUMNS + " FROM app_learning.promo_code WHERE code_hash=:hash FOR UPDATE")
                .param("hash", hash).query(PromoRepository::code).optional();
    }

    long redemptionCount(UUID codeId) {
        return jdbc.sql("SELECT count(*) FROM app_learning.promo_redemption WHERE code_id=:code")
                .param("code", codeId).query(Long.class).single();
    }

    boolean redeemedBy(UUID codeId, UUID owner) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.promo_redemption WHERE code_id=:code AND owner_id=:owner)")
                .param("code", codeId).param("owner", owner).query(Boolean.class).single();
    }

    void insertRedemption(UUID redemptionId, UUID codeId, UUID owner, Instant now, PromoClient client, String snapshotId,
                          boolean oncePerAccount) {
        jdbc.sql("INSERT INTO app_learning.promo_redemption(redemption_id,code_id,owner_id,redeemed_at,ip_hash,device_hash,snapshot_id,"
                        + "once_per_account) VALUES (:id,:code,:owner,:now,:ip,:device,:snapshot,:once)")
                .param("id", redemptionId).param("code", codeId).param("owner", owner).param("now", time(now))
                .param("ip", client.ipHash(), java.sql.Types.BINARY).param("device", client.deviceHash(), java.sql.Types.BINARY)
                .param("snapshot", snapshotId, java.sql.Types.VARCHAR).param("once", oncePerAccount).update();
    }

    /** Accounts other than {@code owner} that redeemed a code from this address hash since {@code since}. */
    long otherRedeemersFromIp(byte[] ipHash, UUID owner, Instant since) {
        return jdbc.sql("SELECT count(DISTINCT owner_id) FROM app_learning.promo_redemption WHERE ip_hash=:ip AND redeemed_at>:since "
                        + "AND owner_id<>:owner").param("ip", ipHash).param("since", time(since)).param("owner", owner)
                .query(Long.class).single();
    }

    /** Keeps the larger percentage; an expired discount is replaced by any new one. */
    void upsertDiscount(UUID owner, int percent, String plan, Instant validUntil, UUID codeId, Instant now) {
        jdbc.sql("INSERT INTO app_learning.promo_discount(owner_id,percent,plan,valid_until,code_id,created_at) "
                        + "VALUES (:owner,:percent,:plan,:until,:code,:now) ON CONFLICT (owner_id) DO UPDATE SET percent=EXCLUDED.percent,"
                        + "plan=EXCLUDED.plan,valid_until=EXCLUDED.valid_until,code_id=EXCLUDED.code_id,created_at=EXCLUDED.created_at "
                        + "WHERE app_learning.promo_discount.valid_until<=:now OR app_learning.promo_discount.percent<EXCLUDED.percent")
                .param("owner", owner).param("percent", percent).param("plan", plan, java.sql.Types.VARCHAR)
                .param("until", time(validUntil)).param("code", codeId).param("now", time(now)).update();
    }

    Optional<Discount> pendingDiscount(UUID owner, Instant now) {
        return jdbc.sql("SELECT percent,plan,valid_until FROM app_learning.promo_discount WHERE owner_id=:owner AND valid_until>:now")
                .param("owner", owner).param("now", time(now))
                .query((row, number) -> new Discount(row.getInt("percent"), row.getString("plan"),
                        row.getTimestamp("valid_until").toInstant())).optional();
    }

    /** A page of codes, newest first; {@code after} is the last code of the previous page (null for the first page). */
    List<CodeWithCount> list(UUID after, int limit) {
        return jdbc.sql("SELECT " + CODE_COLUMNS + ",(SELECT count(*) FROM app_learning.promo_redemption r WHERE r.code_id=c.code_id) "
                        + "AS redemptions FROM app_learning.promo_code c WHERE (CAST(:after AS uuid) IS NULL OR (c.created_at,c.code_id) < "
                        + "(SELECT a.created_at,a.code_id FROM app_learning.promo_code a WHERE a.code_id=CAST(:after AS uuid))) "
                        + "ORDER BY c.created_at DESC,c.code_id DESC LIMIT :limit")
                .param("after", after, java.sql.Types.OTHER).param("limit", limit)
                .query((row, number) -> new CodeWithCount(code(row, number), row.getLong("redemptions"))).list();
    }

    Optional<CodeWithCount> find(UUID codeId) {
        return jdbc.sql("SELECT " + CODE_COLUMNS + ",(SELECT count(*) FROM app_learning.promo_redemption r WHERE r.code_id=c.code_id) "
                        + "AS redemptions FROM app_learning.promo_code c WHERE code_id=:id")
                .param("id", codeId).query((row, number) -> new CodeWithCount(code(row, number), row.getLong("redemptions"))).optional();
    }

    boolean setEnabled(UUID codeId, boolean enabled) {
        return jdbc.sql("UPDATE app_learning.promo_code SET enabled=:enabled WHERE code_id=:id")
                .param("enabled", enabled).param("id", codeId).update() == 1;
    }

    // ---------------------------------------------------------------- attempts

    /** Locks the counters of an account (and of an address): attempts of one key are counted one at a time. */
    void lockKey(String key) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) lock").param("key", key)
                .query(Integer.class).single();
    }

    void purgeAttempts(UUID owner, Instant before) {
        jdbc.sql("DELETE FROM app_learning.promo_attempt WHERE owner_id=:owner AND attempted_at<:before")
                .param("owner", owner).param("before", time(before)).update();
    }

    /** Attempts of the account since {@code since}, and the time of the oldest of them (null when none). */
    long attemptsOfOwner(UUID owner, Instant since) {
        return jdbc.sql("SELECT count(*) FROM app_learning.promo_attempt WHERE owner_id=:owner AND attempted_at>:since")
                .param("owner", owner).param("since", time(since)).query(Long.class).single();
    }

    Optional<Instant> oldestAttemptOfOwner(UUID owner, Instant since) {
        return jdbc.sql("SELECT min(attempted_at) FROM app_learning.promo_attempt WHERE owner_id=:owner AND attempted_at>:since")
                .param("owner", owner).param("since", time(since)).query(Timestamp.class).optional().map(Timestamp::toInstant);
    }

    long attemptsOfIp(byte[] ipHash, Instant since) {
        return jdbc.sql("SELECT count(*) FROM app_learning.promo_attempt WHERE ip_hash=:ip AND attempted_at>:since")
                .param("ip", ipHash).param("since", time(since)).query(Long.class).single();
    }

    Optional<Instant> oldestAttemptOfIp(byte[] ipHash, Instant since) {
        return jdbc.sql("SELECT min(attempted_at) FROM app_learning.promo_attempt WHERE ip_hash=:ip AND attempted_at>:since")
                .param("ip", ipHash).param("since", time(since)).query(Timestamp.class).optional().map(Timestamp::toInstant);
    }

    /** @return the id of the new attempt; its address is set by {@link #assignAddress} once the account is eligible */
    long insertAttempt(UUID owner, Instant now) {
        return jdbc.sql("INSERT INTO app_learning.promo_attempt(owner_id,attempted_at) VALUES (:owner,:now) RETURNING attempt_id")
                .param("owner", owner).param("now", time(now)).query(Long.class).single();
    }

    void assignAddress(long attemptId, byte[] ipHash) {
        jdbc.sql("UPDATE app_learning.promo_attempt SET ip_hash=:ip WHERE attempt_id=:id")
                .param("ip", ipHash, java.sql.Types.BINARY).param("id", attemptId).update();
    }

    /** Deletes up to {@code limit} attempts older than {@code before}; the retention sweep calls it in bounded batches. */
    int purgeAttemptsBefore(Instant before, int limit) {
        return jdbc.sql("DELETE FROM app_learning.promo_attempt WHERE attempt_id IN "
                        + "(SELECT attempt_id FROM app_learning.promo_attempt WHERE attempted_at<:before LIMIT :limit)")
                .param("before", time(before)).param("limit", limit).update();
    }
}
