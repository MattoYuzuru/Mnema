package app.mnema.learning.onboarding;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of {@code onboarding_reward} (V76, OB-402): the one reward of an account, with the personal promo code it points to. {@link RewardIssuer} is the only writer. The view's read
 * is one primary-key join ({@code onboarding_reward} to {@code promo_code}, and the redemption probe on {@code promo_redemption_code}); the clock of every comparison is the
 * database's.
 *
 * <p>TODO(account-deletion task; owner: Learning's account-purge epic #409): delete the row by {@code account_id} with {@code onboarding_journey}; the promo code row stays with the
 * promo ledger (see V76 and {@code docs/product/russia-legal-launch-checklist-2026.md}).
 */
@Repository
class RewardRepository {
    /**
     * A reward joined with its code. {@code redeemed} is the existence of a redemption of the code; {@code past} is {@code statement_timestamp() >= expires_at}.
     *
     * @param codeHash the stored {@code code_hash}, to check that the code derived for the view is the stored one
     */
    record Row(UUID promoCodeId, byte[] codeHash, String offerType, Integer percent, String plan, Integer days, Instant issuedAt, Instant expiresAt, boolean redeemed, boolean past) { }

    private final JdbcClient jdbc;

    RewardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Row> find(UUID account) {
        return jdbc.sql("""
                SELECT r.promo_code_id, c.code_hash, c.type, c.percent, c.plan, c.days, r.issued_at, r.expires_at,
                       EXISTS (SELECT 1 FROM app_learning.promo_redemption x WHERE x.code_id = r.promo_code_id) AS redeemed,
                       statement_timestamp() >= r.expires_at AS past
                  FROM app_learning.onboarding_reward r JOIN app_learning.promo_code c ON c.code_id = r.promo_code_id
                 WHERE r.account_id = :account
                """).param("account", account)
                .query((row, number) -> new Row(row.getObject("promo_code_id", UUID.class), row.getBytes("code_hash"), row.getString("type"), (Integer) row.getObject("percent"),
                        row.getString("plan"), (Integer) row.getObject("days"), row.getTimestamp("issued_at").toInstant(), row.getTimestamp("expires_at").toInstant(),
                        row.getBoolean("redeemed"), row.getBoolean("past")))
                .optional();
    }

    boolean exists(UUID account) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_learning.onboarding_reward WHERE account_id = :account)").param("account", account).query(Boolean.class).single();
    }

    /** Serializes the issuers of one account until the end of the transaction. */
    void lockAccount(UUID account) {
        lock("onboarding.reward:" + account);
    }

    /** Serializes the issuers of one campaign until the end of the transaction, so that its daily limit is exact. */
    void lockCampaign(UUID campaign) {
        lock("onboarding.reward.campaign:" + campaign);
    }

    private void lock(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", key).query().listOfRows();
    }

    /** The database clock in whole seconds: the {@code issued_at} of a reward and the {@code valid_from} of its code. */
    Instant now() {
        return jdbc.sql("SELECT date_trunc('second', statement_timestamp())").query(Timestamp.class).single().toInstant();
    }

    /** The rewards of the campaign issued in the last 24 hours. */
    long issuedInLastDay(UUID campaign) {
        return jdbc.sql("SELECT count(*) FROM app_learning.onboarding_reward WHERE campaign_id = :campaign AND issued_at > statement_timestamp() - interval '24 hours'")
                .param("campaign", campaign).query(Long.class).single();
    }

    void insert(UUID account, UUID campaign, UUID promoCode, Instant issuedAt, Instant expiresAt) {
        jdbc.sql("INSERT INTO app_learning.onboarding_reward(account_id,campaign_id,promo_code_id,issued_at,expires_at) VALUES (:account,:campaign,:code,:issued,:expires)")
                .param("account", account).param("campaign", campaign).param("code", promoCode).param("issued", Timestamp.from(issuedAt)).param("expires", Timestamp.from(expiresAt))
                .update();
    }
}
