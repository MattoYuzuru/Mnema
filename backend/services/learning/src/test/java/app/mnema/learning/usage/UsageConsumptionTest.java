package app.mnema.learning.usage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fair-use buckets (speech to text, answer checks), count caps and the notifications of bucket crossings. */
class UsageConsumptionTest extends UsageIntegrationTest {

    private void consume(UUID owner, Bucket bucket, long amount, String key) {
        inTx(() -> ledger.consume(owner, bucket, amount, key + ":" + owner, "call:" + key));
    }

    private UsageLimitReachedException.Block refused(UUID owner, Bucket bucket, long amount, String key) {
        try {
            consume(owner, bucket, amount, key);
        } catch (UsageLimitReachedException failure) {
            return failure.block();
        }
        throw new AssertionError("expected USAGE_LIMIT_REACHED");
    }

    @Test
    void speechToTextIsMeteredInSecondsAndLimitedPerDayAndPerMonthInWholeMinutes() {
        UUID owner = owner(Plan.FREE);
        consume(owner, Bucket.STT, 500, "stt-1");

        // 10 minutes a day on Free: 500 s used, 200 s more crosses 600 s.
        var day = refused(owner, Bucket.STT, 200, "stt-2");
        assertThat(day).isEqualTo(new UsageLimitReachedException.Block(Bucket.STT, Window.DAY, Unit.MINUTES, 10L, 9, 4,
                true, Instant.parse("2026-10-02T21:00:00Z"), true, Plan.FREE));

        // The tomorrow of that day: 3500 s of a 3600 s month are used after six more days.
        for (int d = 0; d < 6; d++) {
            clock.set("2026-10-0" + (3 + d) + "T09:00:00Z");
            consume(owner, Bucket.STT, 500, "stt-d" + d);
        }
        clock.set("2026-10-09T09:00:00Z");
        var month = refused(owner, Bucket.STT, 600, "stt-big");
        assertThat(month.window()).isEqualTo(Window.MONTH);
        assertThat(month.limit()).isEqualTo(60L);
        assertThat(month.used()).isEqualTo(59);
        assertThat(month.required()).isEqualTo(10);
        assertThat(month.renewsAt()).isEqualTo(Instant.parse("2026-10-31T21:00:00Z"));
        assertThat(month.fitsAfterRenewal()).isTrue();
        // A spent month is reported before the day, even when the day would also refuse.
        consume(owner, Bucket.STT, 100, "stt-last");
        assertThat(refused(owner, Bucket.STT, 500, "stt-more").window()).isEqualTo(Window.MONTH);
        assertThat(usage.read(owner).fairUse().stt().usedSeconds()).isEqualTo(500 + 6 * 500 + 100);
    }

    @Test
    void fairUseFitsIsAReadThatAgreesWithConsumeWithoutWritingOrRollingBack() {
        UUID owner = owner(Plan.FREE);
        // Free: 5 answer checks a day, 50 a month
        assertThat(ledger.fairUseFits(owner, Bucket.ASSESSMENT, 5)).isTrue();
        assertThat(ledger.fairUseFits(owner, Bucket.ASSESSMENT, 6)).isFalse();
        consume(owner, Bucket.ASSESSMENT, 4, "fits-1");
        assertThat(ledger.fairUseFits(owner, Bucket.ASSESSMENT, 1)).isTrue();
        assertThat(ledger.fairUseFits(owner, Bucket.ASSESSMENT, 2)).as("the day would be exceeded").isFalse();
        // it is asked inside the caller's transaction and a refusal there does not mark it rollback-only
        assertThat(inTx(() -> ledger.fairUseFits(owner, Bucket.ASSESSMENT, 2))).isFalse();
        assertThat(usage.read(owner).fairUse().assessment().used()).as("nothing was counted").isEqualTo(4);
        // tomorrow the day window is fresh again
        clock.set("2026-10-03T09:00:00Z");
        assertThat(ledger.fairUseFits(owner, Bucket.ASSESSMENT, 5)).isTrue();
        // a plan without the bucket never fits; the bar and the burst are not consumable
        assertThat(ledger.fairUseFits(owner(Plan.FREE), Bucket.PODCASTS, 1)).isFalse();
        assertThatThrownBy(() -> ledger.fairUseFits(owner, Bucket.CREDITS, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.fairUseFits(owner, Bucket.ASSESSMENT, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.fairUseFits(owner, null, 1)).isInstanceOf(NullPointerException.class);
        // a paid plan with room for many
        UUID plus = owner(Plan.PLUS);
        assertThat(ledger.fairUseFits(plus, Bucket.ASSESSMENT, 40)).isTrue();
        assertThat(ledger.fairUseFits(plus, Bucket.ASSESSMENT, 41)).isFalse();
    }

    @Test
    void aRepeatedKeyChangesNothingAndARefusalWritesNothing() {
        UUID owner = owner(Plan.FREE);
        consume(owner, Bucket.ASSESSMENT, 3, "a-1");
        assertThat(inTx(() -> ledger.consume(owner, Bucket.ASSESSMENT, 3, "a-1:" + owner, null)).replayed()).isTrue();
        assertThat(usage.read(owner).fairUse().assessment().usedToday()).isEqualTo(3);

        UsageLimitReachedException.Block block = refused(owner, Bucket.ASSESSMENT, 3, "a-2");
        assertThat(block.window()).isEqualTo(Window.DAY);
        assertThat(block.limit()).isEqualTo(5L);
        assertThat(block.used()).isEqualTo(3);
        assertThat(usage.read(owner).fairUse().assessment().used()).isEqualTo(3);
        assertThat(repository.ledgerByKey("a-2:" + owner)).isEmpty();
        assertThat(jdbc.sql("SELECT bucket||':'||units||':'||credits FROM app_learning.usage_ledger_entry "
                + "WHERE idempotency_key=:k").param("k", "a-1:" + owner).query(String.class).single())
                .isEqualTo("ASSESSMENT:3:0");
    }

    @Test
    void plansWithoutABucketRefuseItWithOfferedFalseAndNoRenewal() {
        UUID free = owner(Plan.FREE);
        var block = refused(free, Bucket.PODCASTS, 1, "pod-free");
        assertThat(block).isEqualTo(new UsageLimitReachedException.Block(Bucket.PODCASTS, Window.MONTH, Unit.COUNT, 0L, 0, 1,
                false, null, false, Plan.FREE));
        assertThat(refused(free, Bucket.HIGH_FACTCHECK, 1, "fc-free").offered()).isFalse();
        assertThat(refused(free, Bucket.SMART_PLAN, 1, "sp-free").offered()).isFalse();
        assertThat(refused(owner(Plan.PLUS), Bucket.QUALITY_IMAGES, 1, "qi-plus").offered()).isFalse();
    }

    @Test
    void countCapsAllowTheirMonthlyCountAndSmartPlansFollowThePlansCadence() {
        UUID plus = owner(Plan.PLUS);
        consume(plus, Bucket.PODCASTS, 1, "pod-1");
        consume(plus, Bucket.PODCASTS, 1, "pod-2");
        var capped = refused(plus, Bucket.PODCASTS, 1, "pod-3");
        assertThat(capped).isEqualTo(new UsageLimitReachedException.Block(Bucket.PODCASTS, Window.MONTH, Unit.COUNT, 2L, 2, 1,
                true, Instant.parse("2026-10-31T21:00:00Z"), true, Plan.PLUS));
        assertThat(usage.read(plus).caps().podcasts().used()).isEqualTo(2);

        UUID pro = owner(Plan.PRO);
        consume(pro, Bucket.SMART_PLAN, 1, "plan-1");
        var weekly = refused(pro, Bucket.SMART_PLAN, 1, "plan-2");
        assertThat(weekly.window()).isEqualTo(Window.WEEK);
        assertThat(weekly.renewsAt()).isEqualTo(Instant.parse("2026-10-04T21:00:00Z"));
        clock.set("2026-10-04T21:00:00Z");
        consume(pro, Bucket.SMART_PLAN, 1, "plan-3");
        assertThat(usage.read(pro).caps().smartPlan().window()).isEqualTo("WEEK");
    }

    @Test
    void speechToTextWithoutAMonthlyLimitHasOnlyTheVelocityLimit() {
        UUID max = owner(Plan.MAX);
        consume(max, Bucket.STT, 7_000, "max-1");
        var velocity = refused(max, Bucket.STT, 300, "max-2");
        assertThat(velocity.window()).isEqualTo(Window.DAY);
        assertThat(velocity.limit()).isEqualTo(120L);
        assertThat(velocity.fitsAfterRenewal()).isTrue();
        UsageView.SttView stt = usage.read(max).fairUse().stt();
        assertThat(stt.limit()).isNull();
        assertThat(stt.limitToday()).isNull();
        assertThat(stt.velocityPerDay()).isEqualTo(120L);
        assertThat(stt.usedTodayVelocity()).isEqualTo(117L);
        assertThat(stt.warn()).isFalse();
    }

    @Test
    void consumeRejectsTheBarAndNonsenseAmounts() {
        UUID owner = owner(Plan.PLUS);
        assertThatThrownBy(() -> consume(owner, Bucket.CREDITS, 1, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consume(owner, Bucket.DAILY_BURST, 1, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consume(owner, Bucket.STT, 0, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inTx(() -> ledger.consume(owner, Bucket.STT, 1, "bad key", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lowUsageFiresOncePerThresholdAndExhaustionOncePerWindowInTheDebitsTransaction() {
        UUID owner = owner(Plan.PLUS);
        Reservation held = reserve(owner, 360);
        settle(owner, held, "debit:low:1", 280);
        assertThat(notifications(owner, "USAGE_LOW")).isZero();

        settle(owner, held, "debit:low:2", 9);
        assertThat(notifications(owner, "USAGE_LOW")).isOne();
        assertThat(lowParams(owner, 80)).contains("\"percent\": 80").contains("\"remaining\": 71")
                .contains("\"bucket\": \"CREDITS\"").contains("\"plan\": \"PLUS\"").contains("2026-10-31T21:00:00Z");

        // Still inside the same band: no new notice.
        settle(owner, held, "debit:low:3", 4);
        assertThat(notifications(owner, "USAGE_LOW")).isOne();
        // 324 of 360 is exactly 90%.
        settle(owner, held, "debit:low:4", 31);
        assertThat(notifications(owner, "USAGE_LOW")).isEqualTo(2);
        assertThat(notifications(owner, "USAGE_EXHAUSTED")).isZero();

        settle(owner, held, "debit:low:5", 36);
        assertThat(notifications(owner, "USAGE_LOW")).isEqualTo(3);
        assertThat(notifications(owner, "USAGE_EXHAUSTED")).isOne();
        assertThat(jdbc.sql("SELECT params::text FROM app_learning.notification WHERE owner_id=:o AND kind='USAGE_EXHAUSTED'")
                .param("o", owner).query(String.class).single()).contains("\"window\": \"MONTH\"")
                .contains("\"renewsAt\": \"2026-10-31T21:00:00Z\"");
        // The same settles again are repeats and publish nothing.
        settle(owner, held, "debit:low:5", 36);
        assertThat(notifications(owner, "USAGE_LOW") + notifications(owner, "USAGE_EXHAUSTED")).isEqualTo(4);
    }

    @Test
    void oneLargeDebitPublishesOnlyTheHighestThresholdItCrossed() {
        UUID owner = owner(Plan.PLUS);
        Reservation held = reserve(owner, 340);
        settle(owner, held, "debit:big:1", 340);
        assertThat(notifications(owner, "USAGE_LOW")).isOne();
        assertThat(jdbc.sql("SELECT dedupe_key FROM app_learning.notification WHERE owner_id=:o AND kind='USAGE_LOW'")
                .param("o", owner).query(String.class).single()).isEqualTo("usage:CREDITS:2026-10:low:90");
    }

    @Test
    void theFreeWeeklyWindowExhaustsAgainEveryWeek() {
        UUID owner = owner(Plan.FREE);
        Reservation week1 = reserve(owner, 13);
        settle(owner, week1, "debit:w1:1", 13);
        assertThat(notifications(owner, "USAGE_EXHAUSTED")).isOne();
        assertThat(jdbc.sql("SELECT dedupe_key||' '||params::text FROM app_learning.notification "
                + "WHERE owner_id=:o AND kind='USAGE_EXHAUSTED'").param("o", owner).query(String.class).single())
                .startsWith("usage:CREDITS:WEEK:2026-09-30T21:00:00Z:exhausted").contains("\"window\": \"WEEK\"")
                .contains("\"renewsAt\": \"2026-10-04T21:00:00Z\"");

        clock.set("2026-10-05T09:00:00Z");
        Reservation week2 = reserve(owner, 13);
        settle(owner, week2, "debit:w2:1", 13);
        assertThat(notifications(owner, "USAGE_EXHAUSTED")).isEqualTo(2);
        // The low-usage notices are keyed by the month: week two repeats none of week one's.
        assertThat(notifications(owner, "USAGE_LOW")).isOne();
    }

    @Test
    void theLastFreeWindowExhaustsAgainEvenThoughItsLabelIsTheMonth() {
        UUID owner = owner(Plan.FREE);
        settle(owner, reserve(owner, 13), "debit:last:1", 13);
        assertThat(notifications(owner, "USAGE_EXHAUSTED")).isOne();

        // 19 October 00:00 in Moscow: the fourth portion opens and the whole bar (50) is unlocked.
        clock.set("2026-10-18T21:00:00Z");
        settle(owner, reserve(owner, 37), "debit:last:2", 37);

        assertThat(notifications(owner, "USAGE_EXHAUSTED")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT dedupe_key FROM app_learning.notification WHERE owner_id=:o AND kind='USAGE_EXHAUSTED' "
                + "ORDER BY seq").param("o", owner).query(String.class).list()).containsExactly(
                "usage:CREDITS:WEEK:2026-09-30T21:00:00Z:exhausted", "usage:CREDITS:MONTH:2026-10-18T21:00:00Z:exhausted");
        assertThat(jdbc.sql("SELECT params::text FROM app_learning.notification WHERE owner_id=:o AND kind='USAGE_EXHAUSTED' "
                + "ORDER BY seq DESC LIMIT 1").param("o", owner).query(String.class).single())
                .contains("\"window\": \"MONTH\"").contains("\"renewsAt\": \"2026-10-31T21:00:00Z\"");
    }

    @Test
    void aConsumptionReplayMustBeTheSameConsumption() {
        UUID owner = owner(Plan.PLUS);
        UUID other = owner(Plan.PLUS);
        String key = "pod-replay:" + owner;
        inTx(() -> ledger.consume(owner, Bucket.PODCASTS, 1, key, null));

        assertThat(inTx(() -> ledger.consume(owner, Bucket.PODCASTS, 1, key, null)).replayed()).isTrue();
        assertThatThrownBy(() -> inTx(() -> ledger.consume(other, Bucket.PODCASTS, 1, key, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inTx(() -> ledger.consume(owner, Bucket.HIGH_FACTCHECK, 1, key, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inTx(() -> ledger.consume(owner, Bucket.PODCASTS, 2, key, null)))
                .isInstanceOf(IllegalArgumentException.class);
        // A debit of the bar is not a consumption either.
        Reservation held = reserve(owner, 5);
        settle(owner, held, "debit:not-a-consumption:" + owner, 5);
        assertThatThrownBy(() -> inTx(() -> ledger.consume(owner, Bucket.PODCASTS, 1, "debit:not-a-consumption:" + owner, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(usage.read(owner).caps().podcasts().used()).isOne();
        assertThat(usage.read(other).caps().podcasts().used()).isZero();
    }

    @Test
    void concurrentConsumptionsOfOneCapNeverExceedIt() throws Exception {
        UUID owner = owner(Plan.PLUS);
        int racers = 10;
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(racers)) {
            for (int i = 0; i < racers; i++) {
                String key = "pod-race-" + i + ":" + owner;
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        inTx(() -> ledger.consume(owner, Bucket.PODCASTS, 1, key, null));
                        return true;
                    } catch (UsageLimitReachedException refused) {
                        return false;
                    }
                }));
            }
            go.countDown();
            long granted = 0;
            for (var result : results) if (result.get()) granted++;
            assertThat(granted).isEqualTo(2);
        }
        assertThat(usage.read(owner).caps().podcasts().used()).isEqualTo(2);
        assertThat(countOf("usage_ledger_entry", owner)).isEqualTo(2);
    }

    @Test
    void fairUseBucketsNotifyWhenTheMonthlyLimitIsCrossedAndTheDayRunsOut() {
        UUID owner = owner(Plan.FREE);
        // 50 answers a month and 5 a day: four a day for nine days is 36 of 50.
        for (int day = 0; day < 9; day++) {
            clock.set(String.format("2026-10-%02dT09:00:00Z", 2 + day));
            consume(owner, Bucket.ASSESSMENT, 4, "as-" + day);
        }
        assertThat(notifications(owner, "USAGE_LOW")).isZero();
        clock.set("2026-10-11T09:00:00Z");
        consume(owner, Bucket.ASSESSMENT, 4, "as-9");
        // 40 of 50 is the 80% crossing; the day (4 of 5) is not exhausted.
        assertThat(notifications(owner, "USAGE_LOW")).isOne();
        assertThat(notifications(owner, "USAGE_EXHAUSTED")).isZero();

        clock.set("2026-10-12T09:00:00Z");
        consume(owner, Bucket.ASSESSMENT, 5, "as-10");
        // 45 of 50 crosses 90%, and the fifth answer of the day exhausts the day window, not the month.
        assertThat(notifications(owner, "USAGE_LOW")).isEqualTo(2);
        assertThat(notifications(owner, "USAGE_EXHAUSTED")).isOne();
        assertThat(jdbc.sql("SELECT params::text FROM app_learning.notification WHERE owner_id=:o AND kind='USAGE_EXHAUSTED'")
                .param("o", owner).query(String.class).single()).contains("\"bucket\": \"ASSESSMENT\"")
                .contains("\"window\": \"DAY\"");
    }

    @Test
    void aPlanWithoutARefreshableBucketNeverNotifiesForALimitOfZero() {
        UUID owner = owner(Plan.FREE);
        assertThatThrownBy(() -> consume(owner, Bucket.PODCASTS, 1, "none")).isInstanceOf(UsageLimitReachedException.class);
        assertThat(notifications(owner, "USAGE_LOW") + notifications(owner, "USAGE_EXHAUSTED")).isZero();
    }

    @Test
    void oldCountersAreTrimmedByMaintenance() {
        UUID owner = owner(Plan.FREE);
        consume(owner, Bucket.ASSESSMENT, 1, "old");
        clock.set("2027-02-15T09:00:00Z");
        UsageMaintenance maintenance = new UsageMaintenance(repository, clock);
        assertThat(maintenance.purgeOldCounters()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_counter WHERE owner_id=:o").param("o", owner)
                .query(Long.class).single()).isZero();
    }

    private String lowParams(UUID owner, int percent) {
        return jdbc.sql("SELECT params::text FROM app_learning.notification WHERE owner_id=:o AND kind='USAGE_LOW' "
                + "AND dedupe_key LIKE :k").param("o", owner).param("k", "%:low:" + percent).query(String.class).single();
    }
}
