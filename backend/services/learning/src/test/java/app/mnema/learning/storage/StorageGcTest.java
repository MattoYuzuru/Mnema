package app.mnema.learning.storage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static app.mnema.learning.storage.StorageTypes.CollectionResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class StorageGcTest {
    private final ImmutableStorage storage = mock(ImmutableStorage.class);
    private final StorageGcRepository repository = mock(StorageGcRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UUID first = new UUID(0L, 1L);
    private final UUID second = new UUID(0L, 2L);

    private StorageGc gc(int scopes, int batches) {
        return new StorageGc(storage, repository, new StorageGcSettings(true, Duration.ofMinutes(5), scopes, batches,
                Duration.ofSeconds(30)), meters);
    }

    @Test
    void aFailingScopeIsCountedAndDoesNotStopTheOtherScopes() {
        when(repository.nextScope(eq(StorageGcRepository.START), any(), any())).thenReturn(Optional.of(first));
        when(repository.nextScope(eq(first), any(), any())).thenReturn(Optional.of(second));
        when(repository.nextScope(eq(second), any(), any())).thenReturn(Optional.empty());
        when(storage.expireStaging(eq(first), any(), eq(8))).thenThrow(new IllegalStateException("boom"));
        when(storage.collectBatch(eq(second), any(), eq(8))).thenReturn(new CollectionResult(3, 2, 1));

        var pass = gc(8, 4).runOnce();

        assertThat(pass).isEqualTo(new StorageGc.Pass(2, 0, 3, 2, 1, 1));
        assertThat(meters.counter("mnema_storage_gc_errors_total").count()).isEqualTo(1);
        assertThat(meters.counter("mnema_storage_gc_objects_total", "outcome", "deleted").count()).isEqualTo(2);
        assertThat(meters.counter("mnema_storage_gc_objects_total", "outcome", "inspected").count()).isEqualTo(3);
        assertThat(meters.counter("mnema_storage_gc_objects_total", "outcome", "deferred").count()).isEqualTo(1);
        assertThat(meters.get("mnema_storage_gc_pass_seconds").timer().count()).isEqualTo(1);
    }

    @Test
    void aFullBatchContinuesUpToTheBatchBoundAndAShortBatchStops() {
        when(repository.nextScope(eq(StorageGcRepository.START), any(), any())).thenReturn(Optional.of(first));
        when(repository.nextScope(eq(first), any(), any())).thenReturn(Optional.empty());
        when(storage.collectBatch(eq(first), any(), eq(8))).thenReturn(new CollectionResult(8, 8, 0));

        var bounded = gc(8, 3).runOnce();

        assertThat(bounded.inspected()).isEqualTo(24);
        verify(storage, times(3)).collectBatch(eq(first), any(), eq(8));

        when(storage.collectBatch(eq(first), any(), eq(8))).thenReturn(new CollectionResult(8, 8, 0), new CollectionResult(2, 2, 0));
        var drained = gc(8, 16).runOnce();
        assertThat(drained.inspected()).isEqualTo(10);
    }

    @Test
    void scopesAreVisitedFromACursorThatWrapsAndNeverTwiceInOnePass() {
        when(repository.nextScope(eq(StorageGcRepository.START), any(), any())).thenReturn(Optional.of(first));
        when(repository.nextScope(eq(first), any(), any())).thenReturn(Optional.of(second));
        when(repository.nextScope(eq(second), any(), any())).thenReturn(Optional.empty());
        when(storage.collectBatch(any(), any(), eq(8))).thenReturn(new CollectionResult(0, 0, 0));
        StorageGc gc = gc(1, 1);

        gc.runOnce();
        gc.runOnce();
        gc.runOnce();

        // pass 1 visits first, pass 2 resumes after it (second), pass 3 wraps to the start (first) instead of repeating second.
        verify(storage, times(2)).collectBatch(eq(first), any(), eq(8));
        verify(storage, times(1)).collectBatch(eq(second), any(), eq(8));
    }

    @Test
    void anEmptyQueueDoesNothingAndRecordsNoWork() {
        when(repository.nextScope(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(gc(8, 16).runOnce()).isEqualTo(new StorageGc.Pass(0, 0, 0, 0, 0, 0));
        verify(storage, never()).collectBatch(any(), any(Instant.class), eq(8));
    }

    @Test
    void policyIsValidatedAtStartup() {
        Duration run = Duration.ofSeconds(30);
        assertThatThrownBy(() -> new StorageGcSettings(true, Duration.ofSeconds(-1), 8, 16, run)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageGcSettings(true, Duration.ofDays(31), 8, 16, run)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageGcSettings(true, Duration.ZERO, 0, 16, run)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageGcSettings(true, Duration.ZERO, 65, 16, run)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageGcSettings(true, Duration.ZERO, 8, 0, run)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageGcSettings(true, Duration.ZERO, 8, 257, run)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageGcSettings(true, Duration.ZERO, 8, 16, Duration.ofMillis(999))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageGcSettings(true, Duration.ZERO, 8, 16, Duration.ofMinutes(6))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageGcSettings(true, Duration.ZERO, 8, 16, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new StorageGcSettings(true, Duration.ZERO, 64, 256, Duration.ofMinutes(5)).maxScopes).isEqualTo(64);
    }

    @Test
    void killSwitchSkipsThePassAndAFailedPassIsSwallowed() {
        StorageGc pass = mock(StorageGc.class);
        var off = new StorageGcSchedule(pass, new StorageGcSettings(false, Duration.ZERO, 8, 16, Duration.ofSeconds(30)));
        off.run();
        verify(pass, never()).runOnce();

        var on = new StorageGcSchedule(pass, new StorageGcSettings(true, Duration.ZERO, 8, 16, Duration.ofSeconds(30)));
        doThrow(new IllegalStateException("database down")).when(pass).runOnce();
        on.run();
        verify(pass, times(1)).runOnce();
    }
}
