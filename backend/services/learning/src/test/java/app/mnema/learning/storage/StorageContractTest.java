package app.mnema.learning.storage;

import app.mnema.learning.platform.concurrency.CompareAndSetExecutor;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static app.mnema.learning.storage.StorageTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StorageContractTest {
    private final UUID scope = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final JsonNodeFactory json = JsonNodeFactory.instance;

    @Test
    void payloadAndListsAreDefensiveSnapshots() {
        var payload = json.objectNode().put("value", 1);
        var edges = new ArrayList<NewEdge>();
        var object = new NewObject(id, ObjectKind.BLOCK, (short) 1, (short) 0, payload, edges);
        payload.put("value", 2);
        ((com.fasterxml.jackson.databind.node.ObjectNode) object.payload()).put("value", 3);
        assertThat(object.payload().path("value").intValue()).isOne();
        assertThatThrownBy(() -> object.edges().add(new NewEdge(0, null, new ObjectRef(scope, id))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void kindRankFanoutAndOrdinalBoundsAreExplicit() {
        assertThatThrownBy(() -> object(ObjectKind.BLOCK, 1, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> object(ObjectKind.PAGE, 0, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> object(ObjectKind.PAGE, 33, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> object(ObjectKind.PAGE, 1, List.of(new NewEdge(1, null, new ObjectRef(scope, id)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewEdge(32, null, new ObjectRef(scope, id))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewEdge(-1, null, new ObjectRef(scope, id))).isInstanceOf(IllegalArgumentException.class);
        List<NewEdge> full = IntStream.range(0, 32).mapToObj(i -> new NewEdge(i, null, new ObjectRef(scope, id))).toList();
        assertThat(object(ObjectKind.PAGE, 32, full).edges()).hasSize(32);
        assertThat(object(ObjectKind.PAGE, 1, List.of()).edges()).isEmpty();
    }

    @Test
    void identifiersOwnersAndBatchShapeRejectInvalidInput() {
        var value = object(ObjectKind.BLOCK, 0, List.of());
        assertThatThrownBy(() -> new ObjectRef(new UUID(0, 0), id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PinOwner("../bad", id, scope)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StageBatch(scope, id, List.of(value, value), List.of(id))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StageBatch(scope, id, List.of(value), List.of(id, id))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StageBatch(scope, id, List.of(value), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StageBatch(scope, id, IntStream.range(0, 65).mapToObj(i ->
                new NewObject(UUID.randomUUID(), ObjectKind.BLOCK, (short) 1, (short) 0, json.objectNode(), List.of())).toList(), List.of(id)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void engineeringDurationsRejectZeroNegativeAndUnboundedValues() {
        var settings = settings();
        assertThat(settings.requireLease(Duration.ofMillis(1))).isEqualTo(Duration.ofMillis(1));
        assertThat(settings.orphanGrace()).isEqualTo(Duration.ofMinutes(10));
        assertThat(settings.lockTimeout()).isEqualTo(Duration.ofMillis(1));
        for (Duration bad : List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofMillis(-1), Duration.ofDays(2))) {
            assertThatThrownBy(() -> settings.requireLease(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> settings.requireLease(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageSettings(Duration.ofDays(2), Duration.ofMinutes(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageSettings(Duration.ofHours(1), Duration.ofDays(8), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageSettings(Duration.ofHours(1), Duration.ofDays(1), Duration.ofSeconds(6)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void physicalEncodingRejectsUnsafeJsonAndVersionsFingerprintEnvelope() {
        var encoding = new StorageEncoding(new CanonicalJsonHasher());
        assertThatThrownBy(() -> encoding.validatePayload(json.objectNode().put("x", "\u0000"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encoding.validatePayload(json.objectNode().put("x", Double.NaN))).isInstanceOf(IllegalArgumentException.class);
        for (var invalid : List.of(json.numberNode(Double.POSITIVE_INFINITY), json.numberNode(Float.NEGATIVE_INFINITY),
                json.binaryNode(new byte[]{1}), json.pojoNode(new Object()), json.missingNode())) {
            assertThatThrownBy(() -> encoding.validatePayload(json.objectNode().set("x", invalid))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> encoding.validatePayload(json.objectNode().put("x", 9007199254740992L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encoding.validatePayload(json.objectNode().put("x", new java.math.BigDecimal("0.10000000000000001"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(encoding.validatePayload(json.objectNode().put("x", 0.1))).contains("0.1");
        assertThatThrownBy(() -> encoding.validatePayload(json.objectNode().put("x", "a".repeat(20_000)))).isInstanceOf(IllegalArgumentException.class);
        var value = object(ObjectKind.BLOCK, 0, List.of());
        assertThat(encoding.fingerprint(value)).hasSize(32).isEqualTo(encoding.fingerprint(value));
        var versioned = new NewObject(id, ObjectKind.BLOCK, (short) 2, (short) 0, value.payload(), List.of());
        assertThat(encoding.fingerprint(versioned)).isNotEqualTo(encoding.fingerprint(value));
    }

    @Test
    void retryRollsBackWholeBatchAndUsesFreshBoundedTransaction() {
        var repository = mock(StorageRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(repository.now()).thenReturn(Instant.now());
        when(repository.candidates(any(), any(), anyInt())).thenReturn(List.of());
        doThrow(new CannotAcquireLockException("synthetic contention"))
                .doThrow(new CannotAcquireLockException("synthetic contention"))
                .doNothing().when(repository).lockTimeout(any());
        var service = new ImmutableStorage(repository, new StorageEncoding(new CanonicalJsonHasher()), settings(),
                mock(CompareAndSetExecutor.class), manager);
        assertThat(service.collectBatch(scope, Instant.now(), 8).inspected()).isZero();
        verify(manager, times(2)).rollback(any());
        verify(manager, times(1)).commit(any());
        verify(manager, times(3)).getTransaction(argThat(definition -> definition.getTimeout() == 10
                && definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void contentionRetryStopsAfterThreeRolledBackAttempts() {
        var repository = mock(StorageRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> new SimpleTransactionStatus());
        doThrow(new CannotAcquireLockException("synthetic contention")).when(repository).lockTimeout(any());
        var service = new ImmutableStorage(repository, new StorageEncoding(new CanonicalJsonHasher()), settings(),
                mock(CompareAndSetExecutor.class), manager);
        assertThatThrownBy(() -> service.collectBatch(scope, Instant.now(), 8)).isInstanceOf(CannotAcquireLockException.class);
        verify(manager, times(3)).rollback(any());
        verify(manager, never()).commit(any());
    }

    private NewObject object(ObjectKind kind, int rank, List<NewEdge> edges) {
        return new NewObject(id, kind, (short) 1, (short) rank, json.objectNode(), edges);
    }
    private StorageSettings settings() {
        return new StorageSettings(Duration.ofHours(1), Duration.ofMinutes(10), Duration.ofMillis(1));
    }
}
