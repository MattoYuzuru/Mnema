package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import app.mnema.learning.storage.StorageTypes.StageBatch;
import app.mnema.learning.storage.StorageTypes.StagedRoot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static app.mnema.learning.catalog.content.storage.NativeStorageFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NativeStorageBatchesTest {
    private final UUID scope = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final NativeSnapshotCodec codec = new NativeSnapshotCodec();
    private final ImmutableStorage storage = mock(ImmutableStorage.class);
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-09-12T00:00:00Z");

    @Test
    void oneKernelCallPerStepAndExpiredFrontierCannotContinue() {
        var doc = document();
        for (int i = 2; i < 80; i++) ((com.fasterxml.jackson.databind.node.ObjectNode) doc.path("root")).withArray("content").add(node(i, "future"));
        var plan = codec.encode(scope, read(doc));
        when(clock.instant()).thenReturn(now);
        when(storage.stageBatch(any(), any())).thenAnswer(call -> ((StageBatch) call.getArgument(0)).rootObjectIds().stream()
                .map(id -> new StagedRoot(new ObjectRef(scope, id), UUID.randomUUID(), now.plusSeconds(60))).toList());
        var batches = new NativeStorageBatches(storage, clock);
        var cursor = batches.begin(plan, actor, Duration.ofMinutes(1), null);
        assertThatThrownBy(cursor::root).isInstanceOf(NativeStorageFailure.class);
        batches.stageNext(cursor);
        verify(storage, times(1)).stageBatch(argThat(batch -> batch.objects().size() == 32 && batch.rootObjectIds().size() == 32), any());
        assertThat(cursor.pins()).hasSize(32);
        assertThatThrownBy(() -> cursor.pins().clear()).isInstanceOf(UnsupportedOperationException.class);
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        assertThatThrownBy(() -> batches.stageNext(cursor)).isInstanceOfSatisfying(NativeStorageFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(NativeStorageFailure.Code.PREPARATION_EXPIRED));
        verifyNoMoreInteractions(storage);
    }

    @Test
    void unchangedReplacementGetsFreshRootPinAndRequiresExactLiveSource() {
        var doc = read(document(node(2, "paragraph")));
        var original = codec.encode(scope, doc);
        var same = codec.replace(original.snapshot(), doc);
        when(clock.instant()).thenReturn(now);
        when(storage.stageBatch(any(), any())).thenAnswer(call -> ((StageBatch) call.getArgument(0)).rootObjectIds().stream()
                .map(id -> new StagedRoot(new ObjectRef(scope, id), UUID.randomUUID(), now.plusSeconds(90))).toList());
        var batches = new NativeStorageBatches(storage, clock);
        var source = new StagedRoot(original.snapshot().root(), UUID.randomUUID(), now.plusSeconds(60));
        assertThatThrownBy(() -> batches.begin(same, actor, Duration.ofMinutes(1), null)).isInstanceOf(NativeStorageFailure.class);
        assertThatThrownBy(() -> batches.begin(same, actor, Duration.ofMinutes(1), new StagedRoot(new ObjectRef(scope, UUID.randomUUID()), UUID.randomUUID(), source.expiresAt())))
                .isInstanceOf(NativeStorageFailure.class);
        assertThatThrownBy(() -> batches.begin(same, actor, Duration.ofMinutes(1), new StagedRoot(source.root(), source.stagingPinId(), now)))
                .isInstanceOf(NativeStorageFailure.class);
        assertThatThrownBy(() -> batches.begin(original, actor, Duration.ZERO, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> batches.begin(original, actor, Duration.ofDays(2), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> batches.begin(original, actor, Duration.ofMinutes(1), source)).isInstanceOf(NativeStorageFailure.class);
        var cursor = batches.begin(same, actor, Duration.ofMinutes(1), source);
        batches.stageNext(cursor);
        assertThat(cursor.complete()).isTrue();
        assertThat(cursor.root().root()).isEqualTo(source.root());
        assertThat(cursor.root().stagingPinId()).isNotEqualTo(source.stagingPinId());
        verify(storage).stageBatch(argThat(batch -> batch.objects().isEmpty() && batch.rootObjectIds().size() == 1), any());
        assertThatThrownBy(() -> batches.stageNext(cursor)).isInstanceOf(IllegalStateException.class);
    }
}
