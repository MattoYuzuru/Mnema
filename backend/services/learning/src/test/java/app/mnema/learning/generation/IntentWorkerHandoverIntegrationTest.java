package app.mnema.learning.generation;

import app.mnema.learning.ai.AiProperties;
import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.TextResponse;
import app.mnema.learning.ai.Usage;
import app.mnema.learning.ai.UserKeys;
import app.mnema.learning.ai.prompt.PromptAssembler;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.CapabilityUnavailableException;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A keyless API and another worker share only the database: no provider work is executed by the HTTP thread. */
@SpringBootTest(properties = {"learning.runtime.roles=api", "learning.runtime.provider-credentials=worker",
        "learning.features.ai-generation.enabled=true", "spring.datasource.hikari.maximum-pool-size=4",
        "learning.generation.intent.deadline=PT1S"})
class IntentWorkerHandoverIntegrationTest extends PostgresIntegrationTest {
    @Autowired private IntentService api;
    @Autowired private IntentQueue queue;
    @Autowired private GenerationRepository repository;
    @Autowired private GenerationGate gate;
    @Autowired private GenerationSettings settings;
    @Autowired private IntentUses uses;
    @Autowired private PromptAssembler assembler;
    @Autowired private AiProperties ai;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService study;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    @Autowired private ApplicationContext context;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void keylessApiWaitsForAWorkerAndTheRequestTextDoesNotSurviveTheResponse() throws Exception {
        assertThat(context.getBeanNamesForType(IntentRunner.class)).isEmpty();
        assertThat(ai.userKey().configured()).isFalse();
        assertThat(ai.providers().values()).allMatch(provider -> provider.apiKey().isEmpty() && provider.authKey().isEmpty() && provider.clientSecret().isEmpty());
        StudyFixtures.Material material = new StudyFixtures(decks, items, exercises, study, media, jdbc).material();
        var body = Json.object().put("text", "Сделай проще");
        body.putObject("context").put("kind", "MATERIAL").put("memberKey", material.member().toString());
        AtomicInteger calls = new AtomicInteger();
        IntentService workerService = new IntentService(repository, items, exercises, assembler, request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet();
            return AiResult.ok(new TextResponse("{\"operation\":\"REVISE_ITEM\",\"instruction\":\"Сделай проще\"}", TextResponse.FinishReason.STOP,
                    new Usage(10, 0, 10, 10), 1, null, new TextResponse.RouteUsed("test-worker", "test")));
        }, new ProviderKeys(UserKeys.withSecret("worker-only-test-secret-not-production", "test"), ai), gate, uses, settings, queue,
                new StaticListableBeanFactory().getBeanProvider(IntentRunner.class), 10);
        IntentRunner worker = new IntentRunner(queue, workerService, ai);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var answer = executor.submit(() -> api.answer(material.actor(), material.deck(), body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pending(material.actor()) == 0 && System.nanoTime() < end) Thread.sleep(5);
            assertThat(pending(material.actor())).isEqualTo(1);
            assertThat(calls).hasValue(0);
            worker.sweep();
            assertThat(answer.get(5, TimeUnit.SECONDS).path("operation").stringValue()).isEqualTo("REVISE_ITEM");
            assertThat(calls).hasValue(1);
            assertThat(pending(material.actor())).isZero();
        } finally {
            worker.destroy();
        }
    }

    @Test
    void aDeadlineWithoutAWorkerFailsWithoutCallingAProviderOrKeepingText() {
        StudyFixtures.Material material = new StudyFixtures(decks, items, exercises, study, media, jdbc).material();
        var body = Json.object().put("text", "Сделай проще");
        body.putObject("context").put("kind", "MATERIAL").put("memberKey", material.member().toString());
        assertThatThrownBy(() -> api.answer(material.actor(), material.deck(), body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(CapabilityUnavailableException.class);
        assertThat(pending(material.actor())).isZero();
    }

    @Test
    void aLateResultCannotRecreateACancelledRequestAndClaimsAreExclusive() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID id = queue.submit(owner, UUID.randomUUID(), IntentSpecs.Context.material(UUID.randomUUID(), UUID.randomUUID()), "title", "request");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return queue.claim(); });
            var second = executor.submit(() -> { start.await(); return queue.claim(); });
            start.countDown();
            assertThat(java.util.stream.Stream.of(first.get(), second.get()).filter(java.util.Optional::isPresent).count()).isEqualTo(1);
        }
        queue.discard(id, owner);
        queue.complete(id, Json.object().put("operation", "UNSUPPORTED"), null);
        assertThat(queue.read(id, owner)).isEmpty();
        assertThat(pending(owner)).isZero();
    }

    @Test
    void anAccidentalCallerTransactionCannotHoldAConnectionWhilePolling() {
        UUID owner = UUID.randomUUID();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status ->
                assertThatThrownBy(() -> api.answer(owner, UUID.randomUUID(), new byte[0])).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("must not run inside a database transaction"));
        assertThat(pending(owner)).isZero();
    }

    private int pending(UUID owner) {
        return jdbc.sql("SELECT count(*) FROM app_learning.generation_intent_request WHERE owner_id=:owner").param("owner", owner).query(Integer.class).single();
    }
}
