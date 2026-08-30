package app.mnema.learning.platform.idempotency;

import app.mnema.learning.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CommandReceiptServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private CommandReceiptService service;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetFixtures() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS app_learning.idempotency_effect_fixture (
                    effect_id UUID PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """).update();
        jdbcClient.sql("TRUNCATE app_learning.idempotency_effect_fixture, app_learning.command_receipt").update();
    }

    @Test
    void sameEnvelopeAndCanonicalPayloadReturnsStoredResultOnce() throws Exception {
        var identity = identity();
        var calls = new AtomicInteger();
        JsonNode firstPayload = objectMapper.readTree("{\"b\":2.0,\"a\":1}");
        JsonNode reorderedPayload = objectMapper.readTree("{\"a\":1.0,\"b\":2}");

        JsonNode first = service.execute(identity, firstPayload, () -> {
            calls.incrementAndGet();
            return objectMapper.createObjectNode().put("receipt", "stored").put("sequence", 1);
        });
        JsonNode duplicate = service.execute(identity, reorderedPayload, () -> {
            calls.incrementAndGet();
            return objectMapper.createObjectNode().put("receipt", "wrong");
        });

        assertThat(first).isEqualTo(duplicate);
        assertThat(duplicate.path("receipt").textValue()).isEqualTo("stored");
        assertThat(calls).hasValue(1);
        assertThat(receiptCount(identity.commandId())).isOne();
    }

    @Test
    void changedPayloadOwnerScopeOrTypeConflictsWithoutLeakingStoredData() {
        var original = identity();
        JsonNode payload = objectMapper.createObjectNode().put("secret", "alpha");
        service.execute(original, payload, () -> objectMapper.createObjectNode().put("token", "private"));

        assertConflict(new CommandIdentity(original.commandId(), original.actorId(), original.scope(), original.type()),
                objectMapper.createObjectNode().put("secret", "beta"));
        assertConflict(new CommandIdentity(original.commandId(), UUID.randomUUID(), original.scope(), original.type()), payload);
        assertConflict(new CommandIdentity(original.commandId(), original.actorId(), "library.item", original.type()), payload);
        assertConflict(new CommandIdentity(original.commandId(), original.actorId(), original.scope(), "replace"), payload);
        assertThat(receiptCount(original.commandId())).isOne();
    }

    @Test
    void concurrentDuplicateExecutesActionOnlyOnce() throws Exception {
        var identity = identity();
        JsonNode payload = objectMapper.createObjectNode().put("value", 1);
        var calls = new AtomicInteger();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.execute(identity, payload, () -> {
                int sequence = calls.incrementAndGet();
                firstEntered.countDown();
                await(releaseFirst);
                return objectMapper.createObjectNode().put("sequence", sequence);
            }));
            assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();

            var duplicate = executor.submit(() -> service.execute(identity, payload, () ->
                    objectMapper.createObjectNode().put("sequence", calls.incrementAndGet())
            ));
            releaseFirst.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(duplicate.get(10, TimeUnit.SECONDS));
        }

        assertThat(calls).hasValue(1);
        assertThat(receiptCount(identity.commandId())).isOne();
    }

    @Test
    void concurrentChangedPayloadConflictsWithoutExecutingTheLosingAction() throws Exception {
        var identity = identity();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var losingCalls = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.execute(
                    identity,
                    objectMapper.createObjectNode().put("value", 1),
                    () -> {
                        firstEntered.countDown();
                        await(releaseFirst);
                        return objectMapper.createObjectNode().put("winner", true);
                    }
            ));
            assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();

            var conflicting = executor.submit(() -> service.execute(
                    identity,
                    objectMapper.createObjectNode().put("value", 2),
                    () -> objectMapper.createObjectNode().put("loser", losingCalls.incrementAndGet())
            ));
            releaseFirst.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).path("winner").booleanValue()).isTrue();
            assertThatThrownBy(() -> conflicting.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IdempotencyConflictException.class);
        }

        assertThat(losingCalls).hasValue(0);
        assertThat(receiptCount(identity.commandId())).isOne();
    }

    @Test
    void failedActionRollsBackEffectsAndLeavesCommandRetryable() {
        var identity = identity();
        UUID effectId = UUID.randomUUID();
        JsonNode payload = objectMapper.createObjectNode().put("value", 1);

        assertThatThrownBy(() -> service.execute(identity, payload, () -> {
            jdbcClient.sql("""
                            INSERT INTO app_learning.idempotency_effect_fixture (effect_id, value)
                            VALUES (:effectId, 'before-failure')
                            """)
                    .param("effectId", effectId)
                    .update();
            throw new IllegalStateException("rollback fixture");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(receiptCount(identity.commandId())).isZero();
        assertThat(effectCount(effectId)).isZero();

        JsonNode retried = service.execute(identity, payload, () -> {
            jdbcClient.sql("""
                            INSERT INTO app_learning.idempotency_effect_fixture (effect_id, value)
                            VALUES (:effectId, 'committed')
                            """)
                    .param("effectId", effectId)
                    .update();
            return objectMapper.createObjectNode().put("status", "committed");
        });

        assertThat(retried.path("status").textValue()).isEqualTo("committed");
        assertThat(receiptCount(identity.commandId())).isOne();
        assertThat(effectCount(effectId)).isOne();
    }

    @Test
    void rejectsNullEnvelopeActionPayloadAndResult() {
        var identity = identity();
        JsonNode payload = objectMapper.createObjectNode();

        assertThatThrownBy(() -> service.execute(null, payload, () -> payload))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.execute(identity, payload, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.execute(identity, null, () -> payload))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.execute(identity, payload, () -> null))
                .isInstanceOf(NullPointerException.class);
        assertThat(receiptCount(identity.commandId())).isZero();
    }

    private void assertConflict(CommandIdentity identity, JsonNode payload) {
        assertThatThrownBy(() -> service.execute(identity, payload, () ->
                objectMapper.createObjectNode().put("unexpected", true)
        )).isInstanceOf(IdempotencyConflictException.class)
                .hasMessageNotContaining("alpha")
                .hasMessageNotContaining("private");
    }

    private CommandIdentity identity() {
        return new CommandIdentity(UUID.randomUUID(), UUID.randomUUID(), "catalog.deck", "publish");
    }

    private long receiptCount(UUID commandId) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM app_learning.command_receipt WHERE command_id = :commandId
                        """)
                .param("commandId", commandId)
                .query(Long.class)
                .single();
    }

    private long effectCount(UUID effectId) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM app_learning.idempotency_effect_fixture WHERE effect_id = :effectId
                        """)
                .param("effectId", effectId)
                .query(Long.class)
                .single();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrency fixture");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrency fixture was interrupted", exception);
        }
    }
}
