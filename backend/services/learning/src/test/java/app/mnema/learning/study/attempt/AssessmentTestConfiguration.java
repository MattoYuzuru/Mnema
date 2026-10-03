package app.mnema.learning.study.attempt;

import app.mnema.learning.ai.AiFailure;
import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.AiRoute;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test doubles around the real provider layer (which runs on the deterministic Stub): a decorator of {@link TextGeneration} that
 * records every call and what the calling thread holds (a transaction, a borrowed connection), and that simulates what the Stub
 * cannot, by markers in the prompt (the learner answer): {@code [[fake:block]]} (the call waits until released: a slow provider),
 * {@code [[fake:fail]]} (a transport failure the router already gave up on) and {@code [[fake:garbage]]} (an answer that never
 * fits the schema, repairs included). Everything else is answered by the Stub ({@code [[stub:assess-*]]}).
 */
@TestConfiguration(proxyBeanMethods = false)
class AssessmentTestConfiguration {
    /** One recorded provider call. */
    record Call(UUID stepId, int attempt, AiRoute route, double temperature, boolean repair, String prompt,
                boolean transactionAtCall, int connectionsAtCall) { }

    /** Per-thread count of connections borrowed from the pool and not yet closed. */
    static final class Connections {
        private static final ThreadLocal<AtomicInteger> HELD = ThreadLocal.withInitial(AtomicInteger::new);

        private Connections() { }

        static int held() { return HELD.get().get(); }
    }

    static final class Scripted implements TextGeneration {
        private final TextGeneration real;
        final List<Call> calls = new CopyOnWriteArrayList<>();
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);

        Scripted(TextGeneration real) { this.real = real; }

        void reset() {
            release.countDown();
            calls.clear();
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        /** The calls of one attempt. */
        List<Call> callsOf(UUID attempt) { return calls.stream().filter(call -> attempt.equals(call.stepId())).toList(); }

        @Override
        public AiResult<TextResponse> generate(TextRequest request) {
            String prompt = String.join("\n", request.segments().stream().map(TextRequest.Segment::text).toList());
            boolean repair = request.segments().stream().anyMatch(segment -> segment.text().startsWith(TextRequest.REPAIR_PREFIX));
            calls.add(new Call(request.stepId(), request.attempt(), request.route(), request.temperature(), repair, prompt,
                    TransactionSynchronizationManager.isActualTransactionActive(), Connections.held()));
            if (prompt.contains("[[fake:block]]")) {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return AiResult.failed(new AiFailure.Transient("interrupted"));
                }
            }
            if (prompt.contains("[[fake:fail]]")) return AiResult.failed(new AiFailure.Transient("fake_failure"));
            if (prompt.contains("[[fake:garbage]]")) {
                return AiResult.ok(new TextResponse("{\"criteria\":[{\"id\":\"zz\",\"verdict\":\"MET\"}]}", TextResponse.FinishReason.STOP,
                        new Usage(100, 0, 100, 20), 0, "fake", new TextResponse.RouteUsed("stub", "stub")));
            }
            return real.generate(request);
        }
    }

    @Bean
    @Primary
    Scripted scriptedAssessmentText(@Qualifier("textGeneration") TextGeneration real) { return new Scripted(real); }

    /** Counts the connections each thread borrows from the pool, so a test can see that none is held during a call. */
    @Bean
    static BeanPostProcessor assessmentConnectionTracking() {
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
        public Connection getConnection() throws java.sql.SQLException { return track(super.getConnection()); }

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
}
