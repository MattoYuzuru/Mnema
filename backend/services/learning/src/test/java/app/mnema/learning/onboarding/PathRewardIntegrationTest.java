package app.mnema.learning.onboarding;

import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.usage.EntitlementSource;
import app.mnema.learning.usage.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The path reward over the real application, servlet stack and PostgreSQL (OB-402, ADR-ONB-005), with {@code learning.onboarding.reward.enabled} on: when it is issued (a trusted
 * account with every visible milestone done, an enabled campaign, once under any race), what the view says through its life, and that a personal code redeems for its recipient only.
 * Every instant that matters is the database's: the tests move a reward into the past with an UPDATE, never by waiting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z", "learning.onboarding.reward.enabled=true",
        "learning.promo.ip-attempts-per-hour=1000", "spring.datasource.hikari.maximum-pool-size=32"})
class PathRewardIntegrationTest extends PathRewardSupport {
    private static final String CODE = "[A-Z2-9&&[^ILO]]{4}-[A-Z2-9&&[^ILO]]{4}-[A-Z2-9&&[^ILO]]{4}";

    @Autowired private OnboardingService onboarding;
    @Autowired private MilestoneRecorder recorder;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntitlementSource entitlements;

    private <T> List<T> together(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) results.add(future.get());
        pool.shutdown();
        return results;
    }

    /**
     * Redeems through the real endpoint, right after the issuance: the code is valid from a minute before it (PersonalPromoCodes.CLOCK_SKEW), so the difference between the
     * database's and the application's clocks cannot refuse it.
     */
    private HttpResponse<String> redeem(UUID account, String code) {
        return HttpIdentityFixture.send(port, "POST", "/promo-codes/redemptions", HttpIdentityFixture.reader(account), "{\"code\":\"" + code + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private Instant databaseNow() {
        return jdbc.sql("SELECT statement_timestamp()").query(Timestamp.class).single().toInstant();
    }

    // ---- exactly once ----

    @Test
    void concurrentReadsOfAnAccountAtOneHundredPercentIssueExactlyOneCode() throws Exception {
        UUID account = trusted();
        complete(account);
        discountCampaign(10);
        double issued = outcome("issued");
        Instant before = databaseNow();

        List<JsonNode> views = together(6, () -> view(account));

        Instant after = databaseNow();
        assertThat(codes(account)).as("promo codes").isOne();
        assertThat(rewards(account)).as("rewards").isOne();
        assertThat(announcements(account)).as("notifications").isOne();
        assertThat(outcome("issued") - issued).isEqualTo(1.0);
        // the loser of the lock finds the winner's reward, so every answer carries the same code
        assertThat(views).extracting(view -> view.path("reward").path("code").stringValue(null)).doesNotContainNull().containsOnly(views.getFirst().path("reward").path("code").stringValue(null));
        JsonNode reward = views.getFirst().path("reward");
        assertThat(reward.path("state").stringValue(null)).isEqualTo("ACTIVE");
        assertThat(reward.path("code").stringValue(null)).matches(CODE);
        assertThat(Instant.parse(reward.path("issuedAt").stringValue(null))).isBetween(before.minusSeconds(1), after);
        assertThat(Duration.between(Instant.parse(reward.path("issuedAt").stringValue(null)), Instant.parse(reward.path("expiresAt").stringValue(null)))).isEqualTo(Duration.ofDays(7));
        assertThat(jdbc.sql("SELECT params->>'expiresAt' FROM app_learning.notification WHERE owner_id=:a AND dedupe_key='path:reward'").param("a", account).query(String.class).single())
                .isEqualTo(reward.path("expiresAt").stringValue(null));
        assertThat(jdbc.sql("SELECT severity||'/'||route FROM app_learning.notification WHERE owner_id=:a AND dedupe_key='path:reward'").param("a", account).query(String.class).single())
                .isEqualTo("INFO/PATH");
        // the row of the code and of the reward agree: the same end, a start a minute early (clock skew), the campaign's terms, one redemption
        assertThat(jdbc.sql("""
                SELECT c.valid_from = r.issued_at - interval '1 minute' AND c.valid_until = r.expires_at AND c.max_redemptions = 1 AND c.once_per_account AND c.channel = 'path-reward' AND c.enabled
                       AND c.type = 'DISCOUNT_PERCENT' AND c.percent = 10 AND c.campaign_id = r.campaign_id AND c.recipient_id = r.account_id AND c.created_at = r.issued_at
                  FROM app_learning.onboarding_reward r JOIN app_learning.promo_code c ON c.code_id = r.promo_code_id WHERE r.account_id=:a
                """).param("a", account).query(Boolean.class).single()).isTrue();
    }

    @Test
    void theRaceOfTheLastTwoMilestonesFollowedByReadsIssuesOneCode() throws Exception {
        UUID account = trusted();
        completeExcept(account, MilestoneKey.CAPTURE_NOTE, MilestoneKey.TWO_DAYS);
        partial(account, MilestoneKey.CAPTURE_NOTE);
        partial(account, MilestoneKey.TWO_DAYS);
        discountCampaign(10);
        assertThat(view(account).path("reward").isNull()).as("not complete yet").isTrue();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> writers = new ArrayList<>();
        for (MilestoneKey key : new MilestoneKey[] {MilestoneKey.CAPTURE_NOTE, MilestoneKey.TWO_DAYS}) {
            writers.add(pool.submit(() -> {
                go.await();
                new TransactionTemplate(transactions).executeWithoutResult(status -> recorder.reach(account, key, 2));
                return null;
            }));
        }
        go.countDown();
        for (Future<?> writer : writers) writer.get();
        pool.shutdown();

        List<JsonNode> views = together(4, () -> view(account));

        assertThat(views).extracting(view -> view.path("path").path("done").intValue()).containsOnly(views.getFirst().path("path").path("total").intValue());
        assertThat(codes(account)).isOne();
        assertThat(rewards(account)).isOne();
        assertThat(announcements(account)).isOne();
        assertThat(views).extracting(view -> view.path("reward").path("state").stringValue(null)).containsOnly("ACTIVE");
    }

    @Test
    void aReplayedTourAndANewCampaignNeverIssueAgain() {
        UUID account = trusted();
        complete(account);
        UUID first = discountCampaign(10);
        String code = view(account).path("reward").path("code").stringValue(null);
        String command = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"rowVersion\":\"0\",\"action\":\"REPLAY\",\"stepKey\":\"choose\"}";

        HttpResponse<String> replay = HttpIdentityFixture.send(port, "PUT", "/onboarding/journey", HttpIdentityFixture.reader(account), command, NONE);

        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        // the answer of a command shows the reward the account has
        assertThat(json(replay.body()).path("reward").path("code").stringValue(null)).isEqualTo(code);
        jdbc.sql("UPDATE app_learning.promo_campaign SET enabled=false WHERE campaign_id=:id").param("id", first).update();
        discountCampaign(40);
        JsonNode again = view(account);
        assertThat(again.path("reward").path("code").stringValue(null)).isEqualTo(code);
        assertThat(again.path("reward").path("offer").path("percent").intValue()).isEqualTo(10);
        assertThat(codes(account)).isOne();
        assertThat(rewards(account)).isOne();
        assertThat(announcements(account)).isOne();
    }

    // ---- when it is not issued ----

    @Test
    void anUntrustedOrUnknownAccountIsNotIssuedAndALaterTrustedReadIssuesOne() {
        UUID account = trusted();
        complete(account);
        discountCampaign(10);

        HttpIdentityFixture.signals(account, false, false);
        assertThat(view(account).path("reward").isNull()).as("not trusted").isTrue();
        HttpIdentityFixture.withoutSignals(account);
        assertThat(view(account).path("reward").isNull()).as("unknown (an old Identity)").isTrue();
        assertThat(codes(account)).isZero();
        assertThat(rewards(account)).isZero();
        assertThat(announcements(account)).isZero();

        HttpIdentityFixture.signals(account, true, false);
        JsonNode view = view(account);

        assertThat(view.path("reward").path("state").stringValue(null)).isEqualTo("ACTIVE");
        assertThat(codes(account)).isOne();
        assertThat(rewards(account)).isOne();
        assertThat(announcements(account)).isOne();
    }

    @Test
    void withoutAnEnabledCampaignNothingIsIssuedAndTheNextReadAfterTheOwnerEnablesOneIssues() {
        UUID account = trusted();
        complete(account);
        double none = outcome("no_campaign");

        assertThat(view(account).path("reward").isNull()).as("no campaign").isTrue();
        assertThat(outcome("no_campaign") - none).isEqualTo(1.0);
        UUID created = campaign("DISCOUNT_PERCENT", null, null, 10, false);
        assertThat(view(account).path("reward").isNull()).as("a campaign the owner has not switched on").isTrue();
        assertThat(codes(account)).isZero();
        assertThat(rewards(account)).isZero();

        jdbc.sql("UPDATE app_learning.promo_campaign SET enabled=true WHERE campaign_id=:id").param("id", created).update();

        assertThat(view(account).path("reward").path("state").stringValue(null)).isEqualTo("ACTIVE");
        assertThat(codes(account)).isOne();
    }

    @Test
    void aQualifyingAccountWithoutACampaignIsRefusedByPlainReadsWithoutALockOrATransactionOfItsOwn() {
        UUID account = trusted();
        complete(account);
        double none = outcome("no_campaign");

        StatementCounting.start();
        JsonNode view = onboarding.read(account, false, null, Boolean.TRUE);
        List<String> statements = StatementCounting.stop();

        assertThat(view.path("reward").isNull()).isTrue();
        assertThat(outcome("no_campaign") - none).isEqualTo(1.0);
        assertThat(statements.stream().filter(sql -> sql.contains("pg_advisory") || sql.contains("FOR SHARE") || sql.contains("INSERT INTO")).toList())
                .as(statements.toString()).isEmpty();
    }

    @Test
    void aCampaignTheOwnerSwitchesOffWhileTheIssuerWaitsForItsRowIssuesNothing() throws Exception {
        UUID account = trusted();
        complete(account);
        UUID campaign = discountCampaign(10);
        double none = outcome("no_campaign");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var owner = transactions.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
        boolean committed = false;
        try {
            // the owner's switch-off is in flight (not committed): the issuer's unlocked check still sees the campaign enabled, and its locked read must wait for the owner and then see it off
            jdbc.sql("UPDATE app_learning.promo_campaign SET enabled=false WHERE campaign_id=:id").param("id", campaign).update();
            Future<JsonNode> read = pool.submit(() -> view(account));
            Instant limit = Instant.now().plusSeconds(4);
            // asked from a thread without the owner's transaction: inside it pg_stat_activity would stay the snapshot of the first look
            while (java.util.concurrent.CompletableFuture.supplyAsync(() -> jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%FOR SHARE%'")
                    .query(Long.class).single()).get() == 0) {
                assertThat(Instant.now()).as("the issuer waits for the campaign row").isBefore(limit);
                Thread.sleep(20);
            }
            assertThat(read.isDone()).isFalse();
            transactions.commit(owner);
            committed = true;

            assertThat(read.get(10, java.util.concurrent.TimeUnit.SECONDS).path("reward").isNull()).isTrue();
        } finally {
            if (!committed) transactions.rollback(owner);
            pool.shutdownNow();
        }
        assertThat(outcome("no_campaign") - none).isEqualTo(1.0);
        assertThat(codes(account)).isZero();
        assertThat(rewards(account)).isZero();
        assertThat(announcements(account)).isZero();
    }

    @Test
    void anAccountThatIsNotAtOneHundredPercentOfTheVisibleMilestonesIsNotIssued() {
        UUID account = trusted();
        completeExcept(account, MilestoneKey.OWN_EXERCISES);
        partial(account, MilestoneKey.OWN_EXERCISES);
        discountCampaign(10);
        double notComplete = outcome("not_complete");

        JsonNode view = view(account);

        assertThat(view.path("reward").isNull()).isTrue();
        assertThat(view.path("path").path("done").intValue()).isLessThan(view.path("path").path("total").intValue());
        assertThat(codes(account)).isZero();
        assertThat(rewards(account)).isZero();
        // the view's own count is what decides: the issuer is not even asked, so it counts nothing
        assertThat(outcome("not_complete") - notComplete).isZero();

        achieve(account, MilestoneKey.OWN_EXERCISES);
        assertThat(view(account).path("reward").path("state").stringValue(null)).isEqualTo("ACTIVE");
    }

    @Test
    void theHiddenMilestonesDoNotCountSoTheVisibleOnesAreTheWholePath() {
        UUID account = trusted();
        complete(account);
        discountCampaign(10);
        int hidden = visibility.hidden().size();

        JsonNode view = view(account);

        // the default configuration hides the capability milestones (AI, speech, the catalogue) and the rhythm: none of them is stored for this account
        assertThat(hidden).isPositive();
        assertThat(view.path("path").path("hidden")).hasSize(hidden);
        assertThat(view.path("path").path("done").intValue()).isEqualTo(MilestoneKey.values().length - hidden);
        assertThat(view.path("path").path("total").intValue()).isEqualTo(MilestoneKey.values().length - hidden);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.account_milestone WHERE account_id=:a").param("a", account).query(Long.class).single())
                .isEqualTo(MilestoneKey.values().length - hidden);
        assertThat(view.path("reward").path("state").stringValue(null)).isEqualTo("ACTIVE");
    }

    @Test
    void theViewOfAnAccountWithNoMilestonesIsNeverAnEmptyPathReward() {
        UUID account = trusted();
        discountCampaign(10);

        JsonNode view = view(account);

        assertThat(view.path("reward").isNull()).isTrue();
        assertThat(codes(account)).isZero();
    }

    // ---- what the view says ----

    @Test
    void theViewShowsTheCodeThenTheRedemptionAndTheCodeIsRedeemedByItsRecipientOnly() {
        UUID account = trusted();
        UUID stranger = trusted();
        complete(account);
        discountCampaign(15);
        JsonNode active = view(account).path("reward");
        String code = active.path("code").stringValue(null);
        assertThat(active.path("offer").path("type").stringValue(null)).isEqualTo("DISCOUNT_PERCENT");
        assertThat(active.path("offer").path("percent").intValue()).isEqualTo(15);
        assertThat(active.path("offer").path("plan").isNull()).isTrue();
        assertThat(active.path("offer").path("days").isNull()).isTrue();

        HttpResponse<String> refused = redeem(stranger, code);
        HttpResponse<String> unknown = redeem(stranger, "ZZZZ-ZZZZ-ZZZZ");

        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(json(refused.body()).path("code").stringValue(null)).isEqualTo("PROMO_INVALID");
        // indistinguishable from an unknown code
        assertThat(refused.statusCode()).isEqualTo(unknown.statusCode());
        assertThat(json(refused.body())).isEqualTo(json(unknown.body()));
        assertThat(view(account).path("reward").path("state").stringValue(null)).isEqualTo("ACTIVE");

        HttpResponse<String> redeemed = redeem(account, code);

        assertThat(redeemed.statusCode()).as(redeemed.body()).isEqualTo(200);
        assertThat(json(redeemed.body()).path("percent").intValue()).isEqualTo(15);
        // the discount lasts until the end of the offer
        assertThat(jdbc.sql("SELECT d.valid_until = r.expires_at AND d.percent = 15 FROM app_learning.promo_discount d JOIN app_learning.onboarding_reward r ON r.account_id = d.owner_id WHERE d.owner_id=:a")
                .param("a", account).query(Boolean.class).single()).isTrue();
        JsonNode done = view(account).path("reward");
        assertThat(done.path("state").stringValue(null)).isEqualTo("REDEEMED");
        assertThat(done.path("code").isNull()).isTrue();
        assertThat(done.path("offer").path("percent").intValue()).isEqualTo(15);
        assertThat(done.path("expiresAt")).isEqualTo(active.path("expiresAt"));
        // redeemed stays redeemed after the offer's time is up
        jdbc.sql("UPDATE app_learning.onboarding_reward SET expires_at = statement_timestamp() - interval '1 second', issued_at = issued_at - interval '8 days' WHERE account_id=:a")
                .param("a", account).update();
        assertThat(view(account).path("reward").path("state").stringValue(null)).isEqualTo("REDEEMED");
    }

    @Test
    void aTierRewardGivesThePlanForTheDaysOfTheCampaign() {
        UUID account = trusted();
        complete(account);
        tierCampaign("PLUS", 30);
        JsonNode reward = view(account).path("reward");
        assertThat(reward.path("offer").path("type").stringValue(null)).isEqualTo("TIER_DAYS");
        assertThat(reward.path("offer").path("plan").stringValue(null)).isEqualTo("PLUS");
        assertThat(reward.path("offer").path("days").intValue()).isEqualTo(30);
        assertThat(reward.path("offer").path("percent").isNull()).isTrue();

        HttpResponse<String> redeemed = redeem(account, reward.path("code").stringValue(null));

        assertThat(redeemed.statusCode()).as(redeemed.body()).isEqualTo(200);
        Instant until = Instant.parse(json(redeemed.body()).path("validUntil").stringValue(null));
        assertThat(Duration.between(databaseNow(), until)).isBetween(Duration.ofDays(30).minusSeconds(30), Duration.ofDays(30).plusSeconds(30));
        assertThat(entitlements.current(account, databaseNow()).plan()).isEqualTo(Plan.PLUS);
        assertThat(view(account).path("reward").path("state").stringValue(null)).isEqualTo("REDEEMED");
    }

    @Test
    void afterTheEndOfTheOfferTheViewSaysExpiredWithoutACodeAndTheRecipientGetsInvalid() {
        UUID account = trusted();
        complete(account);
        discountCampaign(10);
        String code = view(account).path("reward").path("code").stringValue(null);
        // eight days later by the database's clock: the code and the reward move together
        jdbc.sql("UPDATE app_learning.promo_code SET valid_from = valid_from - interval '8 days', valid_until = valid_until - interval '8 days' WHERE recipient_id=:a").param("a", account).update();
        jdbc.sql("UPDATE app_learning.onboarding_reward SET issued_at = issued_at - interval '8 days', expires_at = expires_at - interval '8 days' WHERE account_id=:a")
                .param("a", account).update();

        JsonNode reward = view(account).path("reward");
        HttpResponse<String> late = redeem(account, code);

        assertThat(reward.path("state").stringValue(null)).isEqualTo("EXPIRED");
        assertThat(reward.path("code").isNull()).isTrue();
        assertThat(reward.path("offer").path("percent").intValue()).isEqualTo(10);
        assertThat(late.statusCode()).isEqualTo(422);
        assertThat(json(late.body()).path("code").stringValue(null)).isEqualTo("PROMO_INVALID");
        // an expired reward is not replaced: the account has had its one
        assertThat(codes(account)).isOne();
        assertThat(view(account).path("reward").path("state").stringValue(null)).isEqualTo("EXPIRED");
        assertThat(codes(account)).isOne();
    }

    @Test
    void theViewOfAnAccountWithAnExistingRewardReadsItWithOneStatement() {
        UUID account = trusted();
        complete(account);
        discountCampaign(10);
        view(account);

        StatementCounting.start();
        onboarding.read(account, false, null, Boolean.TRUE);
        List<String> statements = StatementCounting.stop();

        assertThat(statements.stream().filter(sql -> sql.contains("onboarding_reward")).toList()).as(statements.toString()).hasSize(1);
        assertThat(statements.stream().filter(sql -> sql.contains("pg_advisory_xact_lock")).toList()).as("no issuing transaction").isEmpty();
    }
}
