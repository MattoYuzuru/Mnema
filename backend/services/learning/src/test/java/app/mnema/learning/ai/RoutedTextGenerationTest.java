package app.mnema.learning.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The failure policy of architecture section 4, driven by scripted adapters, a fake clock and a recording sleeper. */
class RoutedTextGenerationTest {
    /** Jitter is always the maximum of its range, so waits are predictable. */
    private static final RandomGenerator MAX_JITTER = new RandomGenerator() {
        @Override public long nextLong() { return Long.MAX_VALUE; }

        @Override public long nextLong(long bound) { return bound - 1; }
    };

    private final AiTestSupport.MutableClock clock = new AiTestSupport.MutableClock(Instant.parse("2026-10-02T10:00:00Z"));
    private final List<Duration> sleeps = new ArrayList<>();
    private final RecordingJournal journal = new RecordingJournal();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ScriptedAdapter deepseek = new ScriptedAdapter("deepseek");
    private final ScriptedAdapter gigachat = new ScriptedAdapter("gigachat");
    private long spent;
    private AiProperties properties = properties(16);

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        Thread.interrupted();
    }

    private static AiProperties properties(int textPermits) {
        AiProperties base = AiTestSupport.properties("", AiTestSupport.routes(List.of("deepseek:deepseek-flash", "gigachat:GigaChat-2"),
                List.of("deepseek:deepseek-v4-pro"), List.of("deepseek:deepseek-flash")), Map.of());
        return new AiProperties(base.provider(), base.routes(), base.providers(), base.models(), base.transport(), base.retry(),
                base.breaker(), new AiProperties.Permits(textPermits, 4, 2, 1, 4, 16, 4, Duration.ofMillis(50)), base.budget(),
                base.userKey(), base.prompt());
    }

    private RoutedTextGeneration router(Sleeper sleeper) {
        Map<String, TextAdapter> adapters = Map.of("deepseek", deepseek, "gigachat", gigachat);
        var breakers = new BreakerRegistry(clock, properties.breaker());
        var budget = new AiBudget(properties.budget(), (capability, since) -> spent, clock);
        this.breakers = breakers;
        return new RoutedTextGeneration(new AiRouting(properties, adapters), breakers, budget, journal,
                new AiTelemetry(meters), properties, clock, sleeper, MAX_JITTER);
    }

    private BreakerRegistry breakers;

    private static AiFailure failure(AiResult<TextResponse> result) {
        assertThat(result).isInstanceOf(AiResult.Failed.class);
        return ((AiResult.Failed<TextResponse>) result).failure();
    }

    private static TextResponse ok(AiResult<TextResponse> result) {
        assertThat(result).isInstanceOf(AiResult.Ok.class);
        return ((AiResult.Ok<TextResponse>) result).value();
    }

    private Sleeper recording() {
        return duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        };
    }

    @Test
    void aSuccessIsJournaledMeteredAndCostedWithoutRetries() {
        deepseek.thenOk("готово");
        TextResponse response = ok(router(recording()).generate(AiTestSupport.request()));

        assertThat(response.text()).isEqualTo("готово");
        assertThat(deepseek.calls()).hasSize(1);
        assertThat(deepseek.calls().get(0).model()).isEqualTo("deepseek-flash");
        assertThat(journal.intents).hasSize(1);
        assertThat(journal.intents.get(0).requestHash()).matches("[0-9a-f]{64}");
        assertThat(journal.intents.get(0).capability()).isEqualTo(AiCapability.TEXT);
        assertThat(journal.outcomes).hasSize(1);
        assertThat(journal.outcomes.get(0).outcome()).isEqualTo("OK");
        assertThat(journal.outcomes.get(0).costMicros()).isEqualTo(500);
        assertThat(journal.outcomes.get(0).usage()).isEqualTo(new Usage(100, 40, 60, 20));
        assertThat(meters.get("mnema_ai_calls_total").tag("outcome", "OK").counter().count()).isEqualTo(1);
        assertThat(meters.get("mnema_ai_cost_micros_total").counter().count()).isEqualTo(500);
        assertThat(meters.get("mnema_ai_call_seconds").timer().count()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aRateLimitWaitsForRetryAfterAndRetriesTheSameCandidate() {
        deepseek.then(new AiFailure.RateLimited(Duration.ofSeconds(2))).thenOk("после паузы");
        TextResponse response = ok(router(recording()).generate(AiTestSupport.request()));

        assertThat(response.text()).isEqualTo("после паузы");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
        assertThat(deepseek.calls()).hasSize(2);
        assertThat(gigachat.calls()).isEmpty();
        assertThat(journal.outcomes).extracting(CallJournal.Outcome::outcome).containsExactly("RATE_LIMITED", "OK");
    }

    @Test
    void aPersistentRateLimitIsRetriedSixTimesThenFallsBackAndCountsAsOneBreakerFailure() {
        deepseek.then(new AiFailure.RateLimited(Duration.ZERO), 7);
        gigachat.thenOk("запасной");
        RoutedTextGeneration router = router(recording());
        TextResponse response = ok(router.generate(AiTestSupport.request()));

        assertThat(response.route().provider()).isEqualTo("gigachat");
        assertThat(deepseek.calls()).hasSize(7);
        // full jitter is capped by backoff-cap (100 ms in tests): 10, 20, 40, 80, 100, 100 ms at maximum jitter
        assertThat(sleeps).hasSize(6);
        assertThat(sleeps.get(0)).isEqualTo(Duration.ofMillis(10));
        assertThat(sleeps.get(5)).isEqualTo(Duration.ofMillis(100));
        // seven throttled answers are one failed ladder, not seven failures: the breaker is still closed
        assertThat(breakers.of("deepseek", AiCapability.TEXT).isOpen()).isFalse();
    }

    @Test
    void aRetryAfterBeyondTheWaitCapOrTheDeadlineIsHandedBackInsteadOfSlept() {
        deepseek.then(new AiFailure.RateLimited(Duration.ofMinutes(5)));
        gigachat.then(new AiFailure.RateLimited(Duration.ofMinutes(5)));
        AiFailure failure = failure(router(recording()).generate(AiTestSupport.request()));
        assertThat(failure).isEqualTo(new AiFailure.RateLimited(Duration.ofMinutes(5)));
        assertThat(sleeps).isEmpty();
        assertThat(gigachat.calls()).as("rate limited is fallbackable").hasSize(1);

        var shortDeadline = new TextRequest(AiRoute.TEXT_FAST, AiTestSupport.request().segments(), OutputContract.MBM_TEXT, 100,
                0.5, Duration.ofSeconds(5), AiTestSupport.KEY, null, null, 1);
        deepseek.then(new AiFailure.RateLimited(Duration.ofSeconds(10)));
        gigachat.then(new AiFailure.RateLimited(Duration.ofSeconds(10)));
        assertThat(failure(router(recording()).generate(shortDeadline))).isEqualTo(new AiFailure.RateLimited(Duration.ofSeconds(10)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void transientFailuresRetryUpToThreeTimesThenFallBack() {
        deepseek.then(new AiFailure.Transient("http_503"), 3);
        gigachat.thenOk("запасной");
        TextResponse response = ok(router(recording()).generate(AiTestSupport.request()));

        assertThat(response.route().provider()).isEqualTo("gigachat");
        assertThat(deepseek.calls()).hasSize(3);
        assertThat(sleeps).hasSize(2);
        assertThat(journal.outcomes).extracting(CallJournal.Outcome::outcome)
                .containsExactly("TRANSIENT", "TRANSIENT", "TRANSIENT", "OK");
    }

    @Test
    void aTransientFailureThatClearsOnRetryStaysOnTheSameCandidate() {
        deepseek.then(new AiFailure.Transient("io_error")).thenOk("со второй попытки");
        assertThat(ok(router(recording()).generate(AiTestSupport.request())).route().provider()).isEqualTo("deepseek");
        assertThat(gigachat.calls()).isEmpty();
    }

    @Test
    void aGradingAttemptIsCappedSoASlowProviderHandsOverToTheFallbackInsteadOfBeingRetried() {
        AiProperties base = AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(),
                List.of("deepseek:deepseek-flash", "gigachat:GigaChat-2")), Map.of());
        properties = new AiProperties(base.provider(), base.routes(), base.providers(), base.models(), base.transport(), base.retry(),
                base.breaker(), base.permits(), base.budget(), base.userKey(), base.prompt());
        assertThat(properties.routes().assessAttemptCap()).isEqualTo(Duration.ofSeconds(8));
        deepseek.then(new AiFailure.Timeout());
        gigachat.thenOk("ответ запасного");
        TextRequest request = new TextRequest(AiRoute.ASSESS, List.of(TextRequest.Segment.user("задача", false)), OutputContract.JSON, 100, 0.2,
                Duration.ofSeconds(20), AiTestSupport.KEY, null, null, 1);

        TextResponse response = ok(router(recording()).generate(request));

        assertThat(response.text()).isEqualTo("ответ запасного");
        assertThat(deepseek.calls()).as("one capped attempt, not three").hasSize(1);
        assertThat(deepseek.calls().getFirst().budget()).isEqualTo(Duration.ofSeconds(8));
        assertThat(gigachat.calls()).hasSize(1);
        assertThat(gigachat.calls().getFirst().budget()).as("every attempt is capped, the fallback included").isEqualTo(Duration.ofSeconds(8));
        // a deadline shorter than the cap is the budget as it is; the text routes have no cap
        assertThat(properties.routes().attemptCap(AiRoute.TEXT_FAST)).isNull();
        assertThat(properties.routes().attemptCap(AiRoute.ASSESS)).isEqualTo(Duration.ofSeconds(8));
        deepseek.then(new AiFailure.Timeout());
        TextRequest short20 = new TextRequest(AiRoute.ASSESS, List.of(TextRequest.Segment.user("задача", false)), OutputContract.JSON, 100, 0.2,
                Duration.ofSeconds(5), AiTestSupport.KEY, null, null, 1);
        ok(router(recording()).generate(short20));
        assertThat(deepseek.calls().get(1).budget()).isEqualTo(Duration.ofSeconds(5));
        assertThatThrownBy(() -> new AiProperties.Routes(List.of(), List.of(), List.of(), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anIdleTimeoutInsideTheDeadlineIsRetriedButThePassedDeadlineIsFinal() {
        deepseek.then(new AiFailure.Timeout()).thenOk("после таймаута");
        assertThat(ok(router(recording()).generate(AiTestSupport.request())).text()).isEqualTo("после таймаута");

        deepseek.thenDo(call -> {
            clock.advance(Duration.ofSeconds(30));
            return AiResult.failed(new AiFailure.Timeout());
        });
        AiFailure failure = failure(router(recording()).generate(AiTestSupport.request()));
        assertThat(failure).isEqualTo(new AiFailure.Timeout());
        assertThat(gigachat.calls()).as("no fallback once the deadline is gone").isEmpty();
    }

    @Test
    void persistentTimeoutsInsideTheDeadlineFallBack() {
        deepseek.then(new AiFailure.Timeout(), 3);
        gigachat.thenOk("запасной");
        assertThat(ok(router(recording()).generate(AiTestSupport.request())).route().provider()).isEqualTo("gigachat");
    }

    @Test
    void invalidOutputGetsOneRepairOnTheSameCandidate() {
        deepseek.then(new AiFailure.InvalidOutput("empty_content")).thenOk("исправлено");
        TextResponse response = ok(router(recording()).generate(AiTestSupport.request()));

        assertThat(response.text()).isEqualTo("исправлено");
        List<ScriptedAdapter.Call> calls = deepseek.calls();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).request().segments()).hasSize(3);
        List<TextRequest.Segment> repaired = calls.get(1).request().segments();
        assertThat(repaired).hasSize(4);
        assertThat(repaired.get(3).text()).startsWith(TextRequest.REPAIR_PREFIX).contains("empty_content");
        assertThat(repaired.get(3).cacheable()).isFalse();
        assertThat(repaired.subList(0, 3)).isEqualTo(calls.get(0).request().segments());
    }

    @Test
    void invalidAfterRepairEscalatesToTheStrongRouteThenFails() {
        deepseek.then(new AiFailure.InvalidOutput("a"), 2).thenOk("сильная модель");
        TextResponse response = ok(router(recording()).generate(AiTestSupport.request()));
        assertThat(response.text()).isEqualTo("сильная модель");
        assertThat(deepseek.calls()).extracting(ScriptedAdapter.Call::model)
                .containsExactly("deepseek-flash", "deepseek-flash", "deepseek-v4-pro");
        assertThat(gigachat.calls()).as("escalation replaces the fast fallback").isEmpty();

        ScriptedAdapter again = new ScriptedAdapter("deepseek");
        again.then(new AiFailure.InvalidOutput("b"), 4);
        var router = new RoutedTextGeneration(new AiRouting(properties, Map.of("deepseek", again, "gigachat", gigachat)),
                new BreakerRegistry(clock, properties.breaker()), new AiBudget(properties.budget(), (c, s) -> 0, clock),
                journal, new AiTelemetry(meters), properties, clock, recording(), MAX_JITTER);
        assertThat(failure(router.generate(AiTestSupport.request()))).isEqualTo(new AiFailure.InvalidOutput("b"));
        assertThat(again.calls()).hasSize(4);
    }

    @Test
    void aRouteWithoutEscalationFailsAfterTheRepair() {
        deepseek.then(new AiFailure.InvalidOutput("x"), 2);
        TextRequest assess = AiTestSupport.request().withRoute(AiRoute.ASSESS);
        assertThat(failure(router(recording()).generate(assess))).isEqualTo(new AiFailure.InvalidOutput("x"));
        assertThat(deepseek.calls()).hasSize(2);
    }

    @Test
    void escalationSkipsACandidateThatAlreadyFailed() {
        // a strong route naming the fast model again does not retry it
        properties = new AiProperties(properties.provider(), new AiProperties.Routes(properties.routes().textFast(),
                List.of("deepseek:deepseek-flash"), properties.routes().assess()), properties.providers(), properties.models(),
                properties.transport(), properties.retry(), properties.breaker(), properties.permits(), properties.budget(),
                properties.userKey(), properties.prompt());
        deepseek.then(new AiFailure.InvalidOutput("x"), 2);
        assertThat(failure(router(recording()).generate(AiTestSupport.request()))).isEqualTo(new AiFailure.InvalidOutput("x"));
        assertThat(deepseek.calls()).hasSize(2);
    }

    @Test
    void aRefusalIsNeverRetriedOrRerouted() {
        deepseek.then(new AiFailure.Refusal("content_filter"));
        assertThat(failure(router(recording()).generate(AiTestSupport.request()))).isEqualTo(new AiFailure.Refusal("content_filter"));
        assertThat(deepseek.calls()).hasSize(1);
        assertThat(gigachat.calls()).isEmpty();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void rejectedCredentialsAreNotRerouted() {
        deepseek.then(new AiFailure.NotConfigured("http_401"));
        assertThat(failure(router(recording()).generate(AiTestSupport.request()))).isEqualTo(new AiFailure.NotConfigured("http_401"));
        assertThat(gigachat.calls()).isEmpty();
    }

    @Test
    void fiveConsecutiveTransportFailuresOpenTheCircuitAndTheCandidateIsSkipped() {
        deepseek.then(new AiFailure.Transient("http_500"), 5);
        gigachat.thenOk("раз").thenOk("два").thenOk("три");
        RoutedTextGeneration router = router(recording());
        // call 1: three transient tries, fallback; call 2: two more tries open the breaker (5 in a row)
        ok(router.generate(AiTestSupport.request()));
        ok(router.generate(AiTestSupport.request()));
        assertThat(breakers.of("deepseek", AiCapability.TEXT).isOpen()).isTrue();
        int before = deepseek.calls().size();

        TextResponse third = ok(router.generate(AiTestSupport.request()));
        assertThat(third.route().provider()).isEqualTo("gigachat");
        assertThat(deepseek.calls()).as("an open circuit means no call").hasSize(before);

        // after the open period one probe goes through and closes the breaker
        clock.advance(Duration.ofSeconds(31));
        assertThat(breakers.of("deepseek", AiCapability.TEXT).isOpen()).isFalse();
        assertThat(ok(router.generate(AiTestSupport.request())).route().provider()).isEqualTo("deepseek");
        assertThat(breakers.of("deepseek", AiCapability.TEXT).state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void whenEveryCircuitIsOpenTheAnswerIsCircuitOpenWithoutACall() {
        properties = new AiProperties(properties.provider(), new AiProperties.Routes(List.of("deepseek:deepseek-flash"),
                List.of(), List.of()), properties.providers(), properties.models(), properties.transport(), properties.retry(),
                properties.breaker(), properties.permits(), properties.budget(), properties.userKey(), properties.prompt());
        deepseek.then(new AiFailure.Transient("http_500"), 5);
        RoutedTextGeneration router = router(recording());
        router.generate(AiTestSupport.request());
        router.generate(AiTestSupport.request());
        int calls = deepseek.calls().size();
        assertThat(failure(router.generate(AiTestSupport.request()))).isEqualTo(new AiFailure.CircuitOpen());
        assertThat(deepseek.calls()).hasSize(calls);
        assertThat(journal.intents).hasSize(calls);
    }

    @Test
    void noUsableAdapterIsNotConfiguredAndASpentBudgetCallsNothing() {
        deepseek.unconfigured();
        gigachat.unconfigured();
        assertThat(failure(router(recording()).generate(AiTestSupport.request()))).isEqualTo(new AiFailure.NotConfigured("no_adapter"));

        ScriptedAdapter healthy = new ScriptedAdapter("deepseek");
        spent = 10_000_000;
        var router = new RoutedTextGeneration(new AiRouting(properties, Map.of("deepseek", healthy, "gigachat", gigachat)),
                new BreakerRegistry(clock, properties.breaker()), new AiBudget(properties.budget(), (c, s) -> spent, clock),
                journal, new AiTelemetry(meters), properties, clock, recording(), MAX_JITTER);
        assertThat(failure(router.generate(AiTestSupport.request()))).isEqualTo(new AiFailure.BudgetExhausted());
        assertThat(healthy.calls()).isEmpty();
        assertThat(journal.intents).isEmpty();
    }

    @Test
    void theCostOfCompletedCallsCountsAgainstTheBudgetBetweenRefreshes() {
        ScriptedAdapter expensive = new ScriptedAdapter("deepseek");
        expensive.thenDo(call -> AiResult.ok(new TextResponse("дорого", TextResponse.FinishReason.STOP, Usage.ZERO,
                10_000_000, null, new TextResponse.RouteUsed("deepseek", "deepseek-flash"))));
        var router = new RoutedTextGeneration(new AiRouting(properties, Map.of("deepseek", expensive, "gigachat", gigachat)),
                new BreakerRegistry(clock, properties.breaker()), new AiBudget(properties.budget(), (c, s) -> 0, clock),
                journal, new AiTelemetry(meters), properties, clock, recording(), MAX_JITTER);
        ok(router.generate(AiTestSupport.request()));
        assertThat(failure(router.generate(AiTestSupport.request()))).isEqualTo(new AiFailure.BudgetExhausted());
    }

    @Test
    void whenTheIntentCannotBeWrittenTheProviderIsNotCalled() {
        journal.failBegin = true;
        deepseek.thenOk("не должен быть вызван");
        gigachat.thenOk("и этот тоже");
        assertThat(failure(router(recording()).generate(AiTestSupport.request()))).isEqualTo(new AiFailure.Transient("journal_unavailable"));
        assertThat(deepseek.calls()).isEmpty();
        assertThat(breakers.of("deepseek", AiCapability.TEXT).state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void anAdapterBugBecomesATransientFailureNotAnException() {
        deepseek.thenDo(call -> {
            throw new IllegalStateException("boom");
        });
        gigachat.thenOk("запасной");
        TextResponse response = ok(router(recording()).generate(AiTestSupport.request()));
        assertThat(response.route().provider()).isEqualTo("deepseek");
        assertThat(journal.outcomes.get(0).outcome()).isEqualTo("TRANSIENT");
    }

    @Test
    void anInterruptedBackoffEndsTheCallAsTransient() {
        Sleeper interrupted = duration -> {
            throw new InterruptedException();
        };
        deepseek.then(new AiFailure.RateLimited(Duration.ofSeconds(1)));
        assertThat(failure(router(interrupted).generate(AiTestSupport.request()))).isEqualTo(new AiFailure.Transient("interrupted"));
        assertThat(Thread.interrupted()).isTrue();

        deepseek.then(new AiFailure.Transient("io_error"));
        assertThat(failure(router(interrupted).generate(AiTestSupport.request()))).isEqualTo(new AiFailure.Transient("interrupted"));
        assertThat(Thread.interrupted()).isTrue();

        Thread.currentThread().interrupt();
        assertThat(failure(router(recording()).generate(AiTestSupport.request()))).isEqualTo(new AiFailure.Transient("interrupted"));
    }

    @Test
    void whenAllPermitsAreTakenALateCallerIsRateLimitedNotQueuedForever() throws Exception {
        properties = properties(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        deepseek.thenDo(call -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return ScriptedAdapter.success("deepseek", call.model(), "медленный");
        });
        RoutedTextGeneration router = router(recording());
        var holder = new AtomicInteger();
        Thread first = Thread.ofVirtual().start(() -> holder.set(router.generate(AiTestSupport.request()) instanceof AiResult.Ok<TextResponse> ? 1 : 2));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(failure(router.generate(AiTestSupport.request()))).isEqualTo(new AiFailure.RateLimited(Duration.ofSeconds(1)));
        release.countDown();
        first.join();
        assertThat(holder.get()).isEqualTo(1);
        // the permit came back
        assertThat(ok(router.generate(AiTestSupport.request())).text()).isEqualTo("ok");
    }

    @Test
    void streamedTextAbandonedByARetryIsAnnouncedToTheListener() {
        List<String> events = new ArrayList<>();
        StreamListener listener = new StreamListener() {
            @Override public void onDelta(String text) { events.add("delta:" + text); }

            @Override public void onRestart() { events.add("restart"); }
        };
        deepseek.thenDo(call -> {
            call.request().listener().onDelta("начало");
            return AiResult.failed(new AiFailure.Transient("stream_truncated"));
        }).thenDo(call -> {
            call.request().listener().onDelta("всё заново");
            return ScriptedAdapter.success("deepseek", call.model(), "всё заново");
        });
        ok(router(recording()).generate(AiTestSupport.request(listener)));
        assertThat(events).containsExactly("delta:начало", "restart", "delta:всё заново");
    }

    @Test
    void aProviderCallInsideATransactionIsAProgrammingError() {
        deepseek.thenOk("не должен быть вызван");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> router(recording()).generate(AiTestSupport.request()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        assertThat(deepseek.calls()).isEmpty();
    }

    @Test
    void internalFailuresAreLoggedByStageAndExceptionClassNeverByMessage() {
        var appender = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(RoutedTextGeneration.class);
        appender.start();
        logger.addAppender(appender);
        try {
            journal.failBegin = true;
            router(recording()).generate(AiTestSupport.request());
            journal.failBegin = false;
            deepseek.thenDo(call -> {
                throw new IllegalStateException("секрет в сообщении");
            });
            router(recording()).generate(AiTestSupport.request());
        } finally {
            logger.detachAppender(appender);
        }
        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).anyMatch(line -> line.startsWith("ai_call_internal_failure stage=journal_begin error_type=IllegalStateException provider=deepseek"));
        assertThat(lines).anyMatch(line -> line.startsWith("ai_call_internal_failure stage=adapter error_type=IllegalStateException"));
        assertThat(String.join("\n", lines)).doesNotContain("секрет").doesNotContain("database down");
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    void aStreamListenerThatThrowsFailsTheCallWithoutRetryOrFallback() {
        StreamListener broken = text -> {
            throw new IllegalArgumentException("consumer bug");
        };
        deepseek.thenDo(call -> {
            call.request().listener().onDelta("текст");
            return ScriptedAdapter.success("deepseek", call.model(), "не должен дойти");
        });
        AiFailure failure = failure(router(recording()).generate(AiTestSupport.request(broken)));
        assertThat(failure).isEqualTo(new AiFailure.Refusal("listener_failed"));
        assertThat(deepseek.calls()).hasSize(1);
        assertThat(gigachat.calls()).isEmpty();
        assertThat(breakers.of("deepseek", AiCapability.TEXT).state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(journal.outcomes.get(0).outcome()).isEqualTo("REFUSAL");
    }

    @Test
    void aFailingRestartCallbackDoesNotBreakTheCall() {
        StreamListener listener = new StreamListener() {
            @Override public void onDelta(String text) { }

            @Override public void onRestart() { throw new IllegalStateException("restart bug"); }
        };
        deepseek.thenDo(call -> {
            call.request().listener().onDelta("начало");
            return AiResult.failed(new AiFailure.Transient("stream_truncated"));
        }).thenOk("со второй попытки");
        assertThat(ok(router(recording()).generate(AiTestSupport.request(listener))).text()).isEqualTo("со второй попытки");
    }

    @Test
    void aProbeSlotIsNotLostWhenTheAdapterThrowsAnError() {
        RoutedTextGeneration router = router(recording());
        var breaker = breakers.of("deepseek", AiCapability.TEXT);
        for (int index = 0; index < 5; index++) breaker.onFailure(breaker.tryAcquire());
        clock.advance(Duration.ofSeconds(31));
        deepseek.thenDo(call -> {
            throw new AssertionError("an Error is not a provider failure");
        });
        assertThatThrownBy(() -> router.generate(AiTestSupport.request())).isInstanceOf(AssertionError.class);
        assertThat(breaker.tryAcquire()).as("the half-open probe slot was given back").isNotEqualTo(CircuitBreaker.REFUSED);
    }

    @Test
    void aFailingTelemetryOrJournalFinishNeverStrandsTheBreaker() {
        deepseek.thenOk("ok").thenOk("ok");
        var failingFinish = new RecordingJournal() {
            @Override public void finish(UUID callId, Outcome outcome) { throw new IllegalStateException("finish bug"); }
        };
        var router = new RoutedTextGeneration(new AiRouting(properties, Map.of("deepseek", deepseek, "gigachat", gigachat)),
                breakers = new BreakerRegistry(clock, properties.breaker()), new AiBudget(properties.budget(), (c, s) -> 0, clock),
                failingFinish, new AiTelemetry(meters), properties, clock, recording(), MAX_JITTER);
        var breaker = breakers.of("deepseek", AiCapability.TEXT);
        for (int index = 0; index < 5; index++) breaker.onFailure(breaker.tryAcquire());
        clock.advance(Duration.ofSeconds(31));
        assertThatThrownBy(() -> router.generate(AiTestSupport.request())).isInstanceOf(IllegalStateException.class);
        assertThat(breaker.state()).as("settled before the journal ran").isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /** In-memory journal. */
    private static class RecordingJournal implements CallJournal {
        final List<Intent> intents = new ArrayList<>();
        final List<Outcome> outcomes = new ArrayList<>();
        boolean failBegin;

        @Override
        public UUID begin(Intent intent) {
            if (failBegin) throw new IllegalStateException("database down");
            intents.add(intent);
            return UUID.randomUUID();
        }

        @Override
        public void finish(UUID callId, Outcome outcome) { outcomes.add(outcome); }
    }
}
