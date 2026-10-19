package app.mnema.learning.onboarding;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** The daily limit of a campaign (OB-402): at most {@code learning.onboarding.reward.daily-limit} codes in any 24 hours, exact under a race, and the next read tries again. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z", "learning.onboarding.reward.enabled=true",
        "learning.onboarding.reward.daily-limit=2", "spring.datasource.hikari.maximum-pool-size=32"})
class PathRewardDailyLimitIntegrationTest extends PathRewardSupport {
    @Autowired private OnboardingService onboarding;

    private UUID completed() {
        UUID account = trusted();
        complete(account);
        return account;
    }

    private long issuedBy(UUID campaign) {
        return jdbc.sql("SELECT count(*) FROM app_learning.onboarding_reward WHERE campaign_id=:c").param("c", campaign).query(Long.class).single();
    }

    @Test
    void theThirdAccountOfTheDayIsNotIssuedAndTheNextReadAfterTheWindowMovesIs() {
        UUID campaign = discountCampaign(10);
        UUID first = completed();
        UUID second = completed();
        UUID third = completed();
        double limited = outcome("limited");

        assertThat(view(first).path("reward").path("state").stringValue(null)).isEqualTo("ACTIVE");
        assertThat(view(second).path("reward").path("state").stringValue(null)).isEqualTo("ACTIVE");
        JsonNode refused = view(third);

        assertThat(refused.path("reward").isNull()).isTrue();
        assertThat(codes(third)).isZero();
        assertThat(rewards(third)).isZero();
        assertThat(issuedBy(campaign)).isEqualTo(2);
        assertThat(outcome("limited") - limited).isEqualTo(1.0);
        // asking again inside the same window changes nothing
        assertThat(view(third).path("reward").isNull()).isTrue();

        // the first reward is now 25 hours old by the database's clock: it leaves the window
        jdbc.sql("UPDATE app_learning.onboarding_reward SET issued_at = issued_at - interval '25 hours' WHERE account_id=:a").param("a", first).update();

        assertThat(view(third).path("reward").path("state").stringValue(null)).isEqualTo("ACTIVE");
        assertThat(issuedBy(campaign)).isEqualTo(3);
    }

    @Test
    void aReadPastTheLimitIsRefusedByPlainReadsWithoutALockOrATransactionOfItsOwn() {
        discountCampaign(10);
        view(completed());
        view(completed());
        UUID third = completed();
        double limited = outcome("limited");

        StatementCounting.start();
        JsonNode view = onboarding.read(third, false, null, Boolean.TRUE);
        List<String> statements = StatementCounting.stop();

        assertThat(view.path("reward").isNull()).isTrue();
        assertThat(outcome("limited") - limited).isEqualTo(1.0);
        // the unlocked count says the campaign is full: no advisory lock (neither the account's nor the campaign's), no row lock, no write
        assertThat(statements.stream().filter(sql -> sql.contains("pg_advisory") || sql.contains("FOR SHARE") || sql.contains("INSERT INTO")).toList())
                .as(statements.toString()).isEmpty();
        assertThat(codes(third)).isZero();
    }

    @Test
    void theLimitIsExactWhenManyAccountsAreReadTogether() throws Exception {
        UUID campaign = discountCampaign(10);
        List<UUID> accounts = new ArrayList<>();
        for (int index = 0; index < 5; index++) accounts.add(completed());
        double limited = outcome("limited");
        double issued = outcome("issued");
        ExecutorService pool = Executors.newFixedThreadPool(accounts.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<JsonNode>> reads = new ArrayList<>();
        for (UUID account : accounts) {
            reads.add(pool.submit(() -> {
                go.await();
                return view(account);
            }));
        }
        go.countDown();
        int active = 0;
        for (Future<JsonNode> read : reads) if (!read.get().path("reward").isNull()) active++;
        pool.shutdown();

        assertThat(active).isEqualTo(2);
        assertThat(issuedBy(campaign)).isEqualTo(2);
        assertThat(outcome("issued") - issued).isEqualTo(2.0);
        assertThat(outcome("limited") - limited).isEqualTo(3.0);
        for (UUID account : accounts) assertThat(codes(account)).isEqualTo(rewards(account));
    }
}
