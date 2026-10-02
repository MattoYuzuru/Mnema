package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AiBudgetTest {
    private final AiTestSupport.MutableClock clock = new AiTestSupport.MutableClock(Instant.parse("2026-10-02T10:00:00Z"));
    private final AtomicLong spent = new AtomicLong();
    private final List<Instant> sinceArguments = new ArrayList<>();
    private final AiBudget budget = new AiBudget(AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()),
            java.util.Map.of()).budget(), (capability, since) -> {
        sinceArguments.add(since);
        return spent.get();
    }, clock);

    @Test
    void theLimitIsExhaustedOnceTheJournalSumReachesIt() {
        spent.set(9_999_999);
        assertThat(budget.exhausted(AiCapability.TEXT)).isFalse();
        spent.set(10_000_000);
        clock.advance(Duration.ofSeconds(11));
        assertThat(budget.exhausted(AiCapability.TEXT)).isTrue();
    }

    @Test
    void theSumIsCachedForTheTtlAndBumpedByLocalCompletions() {
        spent.set(9_000_000);
        assertThat(budget.exhausted(AiCapability.TEXT)).isFalse();
        spent.set(0);
        assertThat(budget.exhausted(AiCapability.TEXT)).as("cached").isFalse();
        assertThat(sinceArguments).hasSize(1);
        budget.record(AiCapability.TEXT, 1_000_000);
        assertThat(budget.exhausted(AiCapability.TEXT)).as("local completion counted before the next refresh").isTrue();
        budget.record(AiCapability.ASSESS, 5);
        budget.record(AiCapability.TEXT, 0);
    }

    @Test
    void theDayStartsAtMidnightInTheConfiguredZoneAndANewDayRefreshes() {
        budget.exhausted(AiCapability.TEXT);
        // 10:00 UTC is 13:00 in Moscow, so the day began at 00:00 Moscow = 21:00 UTC the evening before
        assertThat(sinceArguments.get(0)).isEqualTo(Instant.parse("2026-10-01T21:00:00Z"));
        clock.advance(Duration.ofHours(12));
        budget.exhausted(AiCapability.TEXT);
        assertThat(sinceArguments.get(1)).isEqualTo(Instant.parse("2026-10-02T21:00:00Z"));
    }

    @Test
    void aZeroLimitMeansNoLimitAndAFailingSourceFailsOpen() {
        var unlimited = new AiBudget(new AiProperties.Budget("UTC", 0, 0, 0, 0, 0, 0, 0, Duration.ofSeconds(1)),
                (capability, since) -> Long.MAX_VALUE, clock);
        assertThat(unlimited.exhausted(AiCapability.TEXT)).isFalse();
        var broken = new AiBudget(AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()),
                java.util.Map.of()).budget(), (capability, since) -> {
            throw new IllegalStateException("database down");
        }, clock);
        assertThat(broken.exhausted(AiCapability.TEXT)).isFalse();
    }

    @Test
    void aFailedRefreshKeepsTheLastKnownValueOfTheSameDay() {
        var calls = new AtomicLong();
        var flaky = new AiBudget(AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()),
                java.util.Map.of()).budget(), (capability, since) -> {
            if (calls.incrementAndGet() > 1) throw new IllegalStateException("database down");
            return 10_000_000;
        }, clock);
        assertThat(flaky.exhausted(AiCapability.TEXT)).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(flaky.exhausted(AiCapability.TEXT)).isTrue();
    }

    @Test
    void aRefreshRunsOutsideAnyLockAndOtherCallersKeepReadingTheStaleValueMeanwhile() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var slow = new AiBudget(AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()),
                java.util.Map.of()).budget(), (capability, since) -> {
            if (calls.incrementAndGet() == 1) return 1_000;
            started.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return 10_000_000;
        }, clock);
        assertThat(slow.exhausted(AiCapability.TEXT)).isFalse();
        clock.advance(Duration.ofSeconds(11));

        Thread refresher = Thread.ofVirtual().start(() -> slow.exhausted(AiCapability.TEXT));
        assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // a concurrent reader neither waits for the JDBC call nor starts a second one: it serves the stale same-day value
        assertThat(slow.exhausted(AiCapability.TEXT)).isFalse();
        slow.record(AiCapability.TEXT, 1);
        assertThat(calls).hasValue(2);
        release.countDown();
        refresher.join();
        assertThat(slow.exhausted(AiCapability.TEXT)).as("the refreshed value is served afterwards").isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void aFailedRefreshIsLoggedByExceptionClassOnly() {
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AiBudget.class);
        appender.start();
        logger.addAppender(appender);
        try {
            var broken = new AiBudget(AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()),
                    java.util.Map.of()).budget(), (capability, since) -> {
                throw new IllegalStateException("jdbc url with password");
            }, clock);
            assertThat(broken.exhausted(AiCapability.TEXT)).isFalse();
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .isEqualTo("ai_budget_refresh_failed capability=text error_type=IllegalStateException"));
    }
}
