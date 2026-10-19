package app.mnema.learning.onboarding;

import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static app.mnema.learning.onboarding.OnboardingFixture.JSON;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared world of the path reward tests (OB-402): the real servlet stack and PostgreSQL, an Identity whose {@code mnema_trusted} the test sets per account, milestones and
 * campaigns written as rows (a test that is not about a writer does not drive it), and counts read back. The database is shared by every class of the test JVM, so no campaign is
 * left enabled and every test uses accounts of its own. Every instant a test compares comes from the database's clock.
 */
@Import({PathRewardTestConfiguration.class, StatementCounting.class})
abstract class PathRewardSupport extends PostgresIntegrationTest {
    static final Map<String, String> NONE = Map.of();

    @LocalServerPort protected int port;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected PathVisibility visibility;
    @Autowired protected MeterRegistry meters;

    @DynamicPropertySource
    static void identity(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
    }

    @BeforeEach
    void noCampaignIsEnabledBefore() {
        jdbc.sql("UPDATE app_learning.promo_campaign SET enabled=false").update();
    }

    @AfterEach
    void noCampaignIsEnabledAfter() {
        jdbc.sql("UPDATE app_learning.promo_campaign SET enabled=false").update();
    }

    /** An account Identity calls trusted (verified e-mail, at least 3 days old); not new, so the tour is never offered to it. */
    protected static UUID trusted() {
        UUID account = UUID.randomUUID();
        HttpIdentityFixture.signals(account, true, false);
        return account;
    }

    /** Achieves every visible milestone of the account (the hidden ones stay untouched). */
    protected void complete(UUID account) {
        for (MilestoneKey key : MilestoneKey.values()) {
            if (!visibility.hidden().contains(key)) achieve(account, key);
        }
    }

    /** Achieves every visible milestone of the account except {@code skipped}, which stay as the test left them. */
    protected void completeExcept(UUID account, MilestoneKey... skipped) {
        java.util.Set<MilestoneKey> left = java.util.EnumSet.noneOf(MilestoneKey.class);
        left.addAll(java.util.List.of(skipped));
        for (MilestoneKey key : MilestoneKey.values()) {
            if (!visibility.hidden().contains(key) && !left.contains(key)) achieve(account, key);
        }
    }

    /** The milestone is achieved (an upsert: a partial record of it is completed). */
    protected void achieve(UUID account, MilestoneKey key) {
        jdbc.sql("INSERT INTO app_learning.account_milestone(account_id,milestone_key,progress,achieved_at,first_at,source) "
                        + "VALUES (:a,:k,:p,statement_timestamp(),statement_timestamp(),'LIVE') "
                        + "ON CONFLICT (account_id, milestone_key) DO UPDATE SET progress = EXCLUDED.progress, achieved_at = EXCLUDED.achieved_at")
                .param("a", account).param("k", key.name()).param("p", key.target()).update();
    }

    /** A milestone of target 2 or 3 that is started and not achieved. */
    protected void partial(UUID account, MilestoneKey key) {
        jdbc.sql("INSERT INTO app_learning.account_milestone(account_id,milestone_key,progress,achieved_at,first_at,source) "
                        + "VALUES (:a,:k,1,NULL,statement_timestamp(),'LIVE') ON CONFLICT (account_id, milestone_key) DO NOTHING")
                .param("a", account).param("k", key.name()).update();
    }

    /** An ENABLED discount campaign (the owner switched it on); returns its id. */
    protected UUID discountCampaign(int percent) {
        return campaign("DISCOUNT_PERCENT", null, null, percent, true);
    }

    /** An ENABLED tier campaign; returns its id. */
    protected UUID tierCampaign(String plan, int days) {
        return campaign("TIER_DAYS", plan, days, null, true);
    }

    protected UUID campaign(String offer, String plan, Integer days, Integer percent, boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.promo_campaign(campaign_id,type,offer_type,plan,days,percent,enabled,created_at,created_by,updated_at) "
                        + "VALUES (:id,'PATH_REWARD',:offer,:plan,:days,:percent,:enabled,statement_timestamp(),:by,statement_timestamp())")
                .param("id", id).param("offer", offer).param("plan", plan, java.sql.Types.VARCHAR).param("days", days, java.sql.Types.INTEGER)
                .param("percent", percent, java.sql.Types.INTEGER).param("enabled", enabled).param("by", UUID.randomUUID()).update();
        return id;
    }

    /** A reward row and its personal code as the issuer would leave them (a hash nobody can redeem), for a test that is about the view of an existing reward. */
    protected void insertReward(UUID account, UUID campaign) {
        UUID code = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.promo_code(code_id,code_hash,code_hint,type,percent,valid_from,valid_until,max_redemptions,once_per_account,channel,enabled,created_at,created_by,"
                        + "campaign_id,recipient_id) VALUES (:id, decode(md5(CAST(:id AS text)) || md5(CAST(:id AS text)), 'hex'), 'AB…CD', 'DISCOUNT_PERCENT', 10, "
                        + "date_trunc('second', statement_timestamp()), date_trunc('second', statement_timestamp()) + interval '7 days', 1, true, 'path-reward', true, "
                        + "date_trunc('second', statement_timestamp()), :by, :campaign, :account)")
                .param("id", code).param("by", UUID.randomUUID()).param("campaign", campaign).param("account", account).update();
        jdbc.sql("INSERT INTO app_learning.onboarding_reward(account_id,campaign_id,promo_code_id,issued_at,expires_at) "
                        + "SELECT :account, :campaign, code_id, valid_from, valid_until FROM app_learning.promo_code WHERE code_id=:id")
                .param("id", code).param("campaign", campaign).param("account", account).update();
    }

    protected HttpResponse<String> get(UUID account) {
        return HttpIdentityFixture.send(port, "GET", "/onboarding", HttpIdentityFixture.reader(account), null, NONE);
    }

    /** The view of {@code GET /api/onboarding}, which must be a 200. */
    protected JsonNode view(UUID account) {
        HttpResponse<String> response = get(account);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response.body());
    }

    protected static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    protected long codes(UUID account) {
        return count("SELECT count(*) FROM app_learning.promo_code WHERE recipient_id=:a", account);
    }

    protected long rewards(UUID account) {
        return count("SELECT count(*) FROM app_learning.onboarding_reward WHERE account_id=:a", account);
    }

    protected long announcements(UUID account) {
        return count("SELECT count(*) FROM app_learning.notification WHERE owner_id=:a AND dedupe_key='path:reward' AND kind='PATH_REWARD_READY'", account);
    }

    private long count(String sql, UUID account) {
        return jdbc.sql(sql).param("a", account).query(Long.class).single();
    }

    protected double outcome(String outcome) {
        return meters.counter("mnema_path_reward_total", "outcome", outcome).count();
    }
}
