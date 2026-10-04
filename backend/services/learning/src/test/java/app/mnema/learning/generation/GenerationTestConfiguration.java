package app.mnema.learning.generation;

import app.mnema.learning.ai.AiFailure;
import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.OpaqueUserKey;
import app.mnema.learning.ai.SpeechSynthesis;
import app.mnema.learning.ai.StreamListener;
import app.mnema.learning.ai.TextGeneration;
import app.mnema.learning.ai.TextRequest;
import app.mnema.learning.ai.TextResponse;
import app.mnema.learning.ai.Usage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test doubles around the real provider layer (which runs on the deterministic Stub): a decorator of {@link TextGeneration}
 * that records every call and what the calling thread holds (a transaction, a borrowed connection) at the call and at
 * every streamed delta, and that simulates failures the Stub cannot, selected by markers in the prompt:
 * {@code [[fake:always-invalid-mbm]]} (every answer, repairs included, is a document the compiler rejects),
 * {@code [[fake:invalid-once]]} (the first answer is rejected by the compiler, the repair is answered by the Stub),
 * {@code [[fake:transient]]} (a transport failure the router already gave up on; the Stub's own transient marker would
 * open the shared circuit breaker),
 * {@code [[fake:block]]} (the call waits until released or interrupted: a long provider call to cancel),
 * {@code [[fake:hold-edit]]} (the same for an edit call only, with its own latches),
 * {@code [[fake:crash-once]]} (the first call of each step throws, as a worker that dies mid-step) and
 * {@code [[fake:audio]]} (a valid document with one {@code ::audio} directive). For exercise requests (JSON output):
 * {@code [[fake:not-json]]} (every answer is prose), {@code [[fake:fenced]]} (the Stub's answer inside a code fence) and
 * {@code [[fake:length-once]]} (the first answer is cut off by the output limit, the repair is answered by the Stub),
 * {@code [[fake:no-variants]]} (every variant number of the first answer is 1, so exercises of one mechanic repeat their question) and
 * {@code [[fake:fail-on-repair]]} (a repair call is a transport failure the router gave up on). {@link Scripted#outage} makes every call a
 * transport failure (a provider that is down) until a test clears it, to fail an artifact and retry it.
 */
@TestConfiguration(proxyBeanMethods = false)
class GenerationTestConfiguration {
    static final String INVALID_DOCUMENT = "# Заголовок\n\n::unknown{x=\"1\"} текст\n";
    static final String MULTI_DOCUMENT = "# Заголовок\n\nАбзац один.\n\nАбзац два.\n\n- пункт\n- пункт\n\nКонец.\n";
    static final String AUDIO_DOCUMENT = "# Глагол 行く\n\nПервый абзац.\n\n::audio{slot=\"a1\" lang=\"ja\" title=\"Произношение\"} 行く\n";

    /** One recorded provider call; {@code userKey} tells whose it is, as the context and its dispatcher are shared by every test. */
    record Call(OpaqueUserKey userKey, UUID stepId, int attempt, app.mnema.learning.ai.AiRoute route, boolean repair, String prompt,
                boolean transactionAtCall, int connectionsAtCall) { }

    /** What the calling thread held at each streamed delta of the call made with {@code userKey}. */
    record DeltaObservation(OpaqueUserKey userKey, boolean transaction, int connections) { }

    /** Per-thread count of connections borrowed from the pool and not yet closed. */
    static final class Connections {
        private static final ThreadLocal<AtomicInteger> HELD = ThreadLocal.withInitial(AtomicInteger::new);

        private Connections() { }

        static int held() { return HELD.get().get(); }
    }

    static final class Scripted implements TextGeneration {
        private final TextGeneration real;
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final List<DeltaObservation> deltas = new CopyOnWriteArrayList<>();
        volatile boolean outage;
        volatile CountDownLatch blockedEntered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        /** The latches of {@code [[fake:hold-edit]]}: an edit call that waits while a draft call, held by {@code [[fake:block]]}, runs on. */
        volatile CountDownLatch editEntered = new CountDownLatch(1);
        volatile CountDownLatch editRelease = new CountDownLatch(1);
        private final Set<UUID> crashed = ConcurrentHashMap.newKeySet();

        Scripted(TextGeneration real) { this.real = real; }

        void reset() {
            outage = false;
            calls.clear();
            deltas.clear();
            crashed.clear();
            release = new CountDownLatch(1);
            blockedEntered = new CountDownLatch(1);
            editRelease = new CountDownLatch(1);
            editEntered = new CountDownLatch(1);
        }

        List<Call> callsOf(String marker) {
            return calls.stream().filter(call -> call.prompt().contains(marker)).toList();
        }

        @Override
        public AiResult<TextResponse> generate(TextRequest request) {
            String prompt = String.join("\n", request.segments().stream().map(TextRequest.Segment::text).toList());
            boolean repair = request.segments().stream().anyMatch(segment -> segment.text().startsWith(TextRequest.REPAIR_PREFIX));
            calls.add(new Call(request.userKey(), request.stepId(), request.attempt(), request.route(), repair, prompt,
                    TransactionSynchronizationManager.isActualTransactionActive(), Connections.held()));
            if (prompt.contains("[[fake:crash-once]]" ) && crashed.add(request.stepId())) {
                throw new IllegalStateException("simulated worker crash");
            }
            if (prompt.contains("[[fake:hold-edit]]") && prompt.contains("<task kind=\"edit\">")) {
                editEntered.countDown();
                try {
                    editRelease.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return AiResult.failed(new AiFailure.Transient("interrupted"));
                }
            }
            if (prompt.contains("[[fake:block]]")) {
                blockedEntered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return AiResult.failed(new AiFailure.Transient("interrupted"));
                }
            }
            if (outage) return AiResult.failed(new AiFailure.Transient("fake_outage"));
            if (prompt.contains("[[fake:transient]]")) return AiResult.failed(new AiFailure.Transient("fake_transient"));
            if (prompt.contains("[[fake:always-invalid-mbm]]")) return ok(request, INVALID_DOCUMENT);
            if (prompt.contains("[[fake:invalid-once]]") && !repair) return ok(request, INVALID_DOCUMENT);
            if (prompt.contains("[[fake:audio]]")) return ok(request, AUDIO_DOCUMENT);
            if (prompt.contains("[[fake:not-json]]")) return ok(request, "это не json");
            if (prompt.contains("[[fake:length-once]]") && !repair) {
                return AiResult.ok(new TextResponse("{\"exercises\":[{\"mechanic\":\"CHO", TextResponse.FinishReason.LENGTH,
                        new Usage(100, 0, 100, 50), 0, "fake", new TextResponse.RouteUsed("stub", "stub")));
            }
            if (repair && prompt.contains("[[fake:fail-on-repair]]")) return AiResult.failed(new AiFailure.Transient("fake_repair_outage"));
            if (!repair && prompt.contains("[[fake:no-variants]]")) {
                AiResult<TextResponse> answer = real.generate(request);
                return answer instanceof AiResult.Ok<TextResponse> success
                        ? ok(request, success.value().text().replaceAll("\\(вариант [0-9]+\\)", "(вариант 1)")) : answer;
            }
            if (prompt.contains("[[fake:fenced]]")) {
                AiResult<TextResponse> answer = real.generate(request);
                return answer instanceof AiResult.Ok<TextResponse> success ? ok(request, "```json\n" + success.value().text() + "\n```") : answer;
            }
            if (prompt.contains("[[fake:multiblock]]")) return ok(request, MULTI_DOCUMENT);
            StreamListener original = request.listener();
            StreamListener probe = new StreamListener() {
                @Override public void onDelta(String text) {
                    deltas.add(new DeltaObservation(request.userKey(), TransactionSynchronizationManager.isActualTransactionActive(), Connections.held()));
                    if (original != null) original.onDelta(text);
                }

                @Override public void onRestart() {
                    if (original != null) original.onRestart();
                }
            };
            return real.generate(request.withListener(probe));
        }

        private AiResult<TextResponse> ok(TextRequest request, String text) {
            if (request.listener() != null) {
                for (int start = 0; start < text.length(); start += 16) {
                    deltas.add(new DeltaObservation(request.userKey(), TransactionSynchronizationManager.isActualTransactionActive(), Connections.held()));
                    request.listener().onDelta(text.substring(start, Math.min(text.length(), start + 16)));
                }
            }
            return AiResult.ok(new TextResponse(text, TextResponse.FinishReason.STOP, new Usage(100, 0, 100, 50), 0, "fake",
                    new TextResponse.RouteUsed("stub", "stub")));
        }
    }

    @Bean
    @Primary
    Scripted scriptedText(@Qualifier("textGeneration") TextGeneration real) {
        return new Scripted(real);
    }

    /** An executor for a media kind, so a test can see that a step of a REVIEW session is claimed (the real ones come with AI-09). */
    static final class VideoExecutor implements StepExecutor {
        final List<UUID> claimed = new CopyOnWriteArrayList<>();
        private final StepRepository steps;

        VideoExecutor(StepRepository steps) { this.steps = steps; }

        @Override public String kind() { return "VIDEO_GENERATE"; }

        @Override public app.mnema.learning.ai.AiCapability capability() { return app.mnema.learning.ai.AiCapability.VIDEO; }

        @Override
        public void execute(StepClaim claim, StepControl control) {
            claimed.add(claim.stepId());
            steps.finish(claim.stepId(), "SUCCEEDED", null, null);
        }
    }

    @Bean
    VideoExecutor testVideoExecutor(StepRepository steps) {
        return new VideoExecutor(steps);
    }

    /** A text-to-speech adapter that exists only so the capability is available; nothing calls it in this task. */
    @Bean
    SpeechSynthesis testSpeechSynthesis() {
        return request -> AiResult.failed(new AiFailure.NotConfigured("test"));
    }

    /** Counts the connections each thread borrows from the pool, so a test can assert that none is held during a call. */
    @Bean
    static BeanPostProcessor connectionTracking() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource source) || bean instanceof Tracking) return bean;
                return new Tracking(source);
            }
        };
    }

    private static final class Tracking extends DelegatingDataSource {
        Tracking(DataSource target) { super(target); }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return track(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws java.sql.SQLException {
            return track(super.getConnection(username, password));
        }

        private static Connection track(Connection connection) {
            AtomicInteger held = Connections.HELD.get();
            held.incrementAndGet();
            boolean[] closed = {false};
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close") && !closed[0]) {
                            closed[0] = true;
                            held.decrementAndGet();
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }

    static List<Call> copy(List<Call> calls) {
        return Collections.unmodifiableList(new ArrayList<>(calls));
    }
}
