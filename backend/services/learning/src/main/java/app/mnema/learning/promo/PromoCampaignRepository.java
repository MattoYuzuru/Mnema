package app.mnema.learning.promo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of {@code promo_campaign} (V76, OB-402). The counts and the issuance list read {@code onboarding_reward}, the audit of the issuances, by its index
 * {@code onboarding_reward_campaign (campaign_id, issued_at)} and the unique {@code promo_code_id}; no query selects an account id or a code. The 24 hours of a count are the
 * database clock, like the issuer's daily limit. No method opens a transaction: the services do.
 */
@Repository
class PromoCampaignRepository {
    /** A campaign as stored. {@code plan}, {@code days} and {@code percent} follow the shapes of its offer type. */
    record Campaign(UUID campaignId, PromoCampaignType type, PromoType offerType, Integer percent, String plan, Integer days, boolean enabled, Instant createdAt, UUID createdBy) { }

    /** A campaign with its counts: the codes it issued, the ones redeemed, and the ones issued in the last 24 hours. */
    record CampaignWithCounts(Campaign campaign, long issued, long redeemed, long issuedLast24h) { }

    /** One issued code as the owner sees it: no account, no plain code. */
    record Issuance(UUID codeId, String hint, Instant issuedAt, Instant expiresAt, Instant redeemedAt) { }

    private static final String COLUMNS = "c.campaign_id,c.type,c.offer_type,c.percent,c.plan,c.days,c.enabled,c.created_at,c.created_by";

    private static final String WITH_COUNTS = "SELECT " + COLUMNS + ","
            + "(SELECT count(*) FROM app_learning.onboarding_reward r WHERE r.campaign_id=c.campaign_id) AS issued,"
            + "(SELECT count(*) FROM app_learning.onboarding_reward r WHERE r.campaign_id=c.campaign_id "
            + "AND EXISTS (SELECT 1 FROM app_learning.promo_redemption x WHERE x.code_id=r.promo_code_id)) AS redeemed,"
            + "(SELECT count(*) FROM app_learning.onboarding_reward r WHERE r.campaign_id=c.campaign_id "
            + "AND r.issued_at > statement_timestamp() - interval '24 hours') AS issued_last24h FROM app_learning.promo_campaign c";

    private final JdbcClient jdbc;

    PromoCampaignRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Campaign campaign(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new Campaign(row.getObject("campaign_id", UUID.class), PromoCampaignType.valueOf(row.getString("type")), PromoType.valueOf(row.getString("offer_type")),
                (Integer) row.getObject("percent"), row.getString("plan"), (Integer) row.getObject("days"), row.getBoolean("enabled"),
                row.getTimestamp("created_at").toInstant(), row.getObject("created_by", UUID.class));
    }

    private static CampaignWithCounts counted(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new CampaignWithCounts(campaign(row, number), row.getLong("issued"), row.getLong("redeemed"), row.getLong("issued_last24h"));
    }

    void insert(Campaign campaign) {
        jdbc.sql("INSERT INTO app_learning.promo_campaign(campaign_id,type,offer_type,plan,days,percent,enabled,created_at,created_by,updated_at) "
                        + "VALUES (:id,:type,:offer,:plan,:days,:percent,:enabled,:created,:by,:created)")
                .param("id", campaign.campaignId()).param("type", campaign.type().name()).param("offer", campaign.offerType().name())
                .param("plan", campaign.plan(), java.sql.Types.VARCHAR).param("days", campaign.days(), java.sql.Types.INTEGER)
                .param("percent", campaign.percent(), java.sql.Types.INTEGER).param("enabled", campaign.enabled())
                .param("created", Timestamp.from(campaign.createdAt())).param("by", campaign.createdBy()).update();
    }

    /** The enabled campaign of the type (at most one: the partial unique index {@code promo_campaign_one_enabled}). */
    Optional<Campaign> enabled(PromoCampaignType type) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.promo_campaign c WHERE c.type=:type AND c.enabled").param("type", type.name())
                .query(PromoCampaignRepository::campaign).optional();
    }

    /**
     * The enabled campaign of the type, read {@code FOR SHARE}: the row stays share-locked until the end of the transaction, so a disable of it (an {@code UPDATE}) waits for the
     * caller's commit, and a disable that is already in flight is waited for and then seen (the {@code WHERE} is re-evaluated against its committed row).
     */
    Optional<Campaign> enabledForShare(PromoCampaignType type) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.promo_campaign c WHERE c.type=:type AND c.enabled FOR SHARE OF c").param("type", type.name())
                .query(PromoCampaignRepository::campaign).optional();
    }

    /** Whether a campaign of the type other than {@code campaignId} is enabled. */
    boolean otherEnabled(PromoCampaignType type, UUID campaignId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_learning.promo_campaign WHERE type=:type AND enabled AND campaign_id<>:id)")
                .param("type", type.name()).param("id", campaignId).query(Boolean.class).single();
    }

    Optional<CampaignWithCounts> find(UUID campaignId) {
        return jdbc.sql(WITH_COUNTS + " WHERE c.campaign_id=:id").param("id", campaignId).query(PromoCampaignRepository::counted).optional();
    }

    /** The newest campaigns first. */
    List<CampaignWithCounts> list(int limit) {
        return jdbc.sql(WITH_COUNTS + " ORDER BY c.created_at DESC,c.campaign_id DESC LIMIT :limit").param("limit", limit).query(PromoCampaignRepository::counted).list();
    }

    /** @return true when the state actually changed; a repeat of the current state and an unknown campaign are both false */
    boolean setEnabled(UUID campaignId, boolean enabled, Instant now) {
        return jdbc.sql("UPDATE app_learning.promo_campaign SET enabled=:enabled,updated_at=:now WHERE campaign_id=:id AND enabled<>:enabled")
                .param("enabled", enabled).param("now", Timestamp.from(now)).param("id", campaignId).update() == 1;
    }

    /**
     * A page of the issuances of the campaign, newest first by {@code (issued_at, promo_code_id)}; {@code afterIssuedAt} and {@code afterCodeId} are the last row of the previous
     * page (both null for the first page).
     */
    List<Issuance> issuances(UUID campaignId, Instant afterIssuedAt, UUID afterCodeId, int limit) {
        return jdbc.sql("SELECT r.promo_code_id,c.code_hint,r.issued_at,r.expires_at,"
                        + "(SELECT min(x.redeemed_at) FROM app_learning.promo_redemption x WHERE x.code_id=r.promo_code_id) AS redeemed_at "
                        + "FROM app_learning.onboarding_reward r JOIN app_learning.promo_code c ON c.code_id=r.promo_code_id WHERE r.campaign_id=:campaign "
                        + "AND (CAST(:afterAt AS timestamptz) IS NULL OR (r.issued_at,r.promo_code_id) < (CAST(:afterAt AS timestamptz),CAST(:afterCode AS uuid))) "
                        + "ORDER BY r.issued_at DESC,r.promo_code_id DESC LIMIT :limit")
                .param("campaign", campaignId).param("afterAt", afterIssuedAt == null ? null : Timestamp.from(afterIssuedAt), java.sql.Types.TIMESTAMP)
                .param("afterCode", afterCodeId, java.sql.Types.OTHER).param("limit", limit)
                .query((row, number) -> new Issuance(row.getObject("promo_code_id", UUID.class), row.getString("code_hint"), row.getTimestamp("issued_at").toInstant(),
                        row.getTimestamp("expires_at").toInstant(), row.getTimestamp("redeemed_at") == null ? null : row.getTimestamp("redeemed_at").toInstant()))
                .list();
    }
}
