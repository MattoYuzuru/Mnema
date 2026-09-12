package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.StageBatch;
import app.mnema.learning.storage.StorageTypes.StagedRoot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static app.mnema.learning.catalog.content.storage.NativeStorageFormat.*;

/**
 * One kernel batch per integration call. Services retain no cursor state. The caller owns ACL,
 * scheduling, source/root pins, lease renewal/cleanup and final publication; no worker is installed.
 */
public final class NativeStorageBatches {
    private final ImmutableStorage storage;
    private final Clock clock;

    public NativeStorageBatches(ImmutableStorage storage) { this(storage, Clock.systemUTC()); }

    NativeStorageBatches(ImmutableStorage storage, Clock clock) {
        this.storage = Objects.requireNonNull(storage);
        this.clock = Objects.requireNonNull(clock);
    }

    /** A replacement needs an already authorized preparation pin for its exact source root. */
    public Preparation begin(NativeEncodingPlan plan, UUID actor, Duration lease, StagedRoot source) {
        Objects.requireNonNull(plan);
        UuidPolicy.requireEntityId(actor, "actorId");
        if (lease == null || lease.compareTo(Duration.ofMillis(1)) < 0 || lease.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid native preparation lease");
        }
        if (plan.sourceRoot().isPresent()) {
            require(source != null && plan.sourceRoot().orElseThrow().equals(source.root()));
            requireLive(source.expiresAt());
        } else {
            require(source == null);
        }
        return new Preparation(plan, actor, lease, source == null ? null : source.expiresAt());
    }

    /**
     * Every new object is pinned, including frontier children between calls. Recorded deadlines are
     * conservative cursor guards, not a database validation receipt. K1 retain checks the live DB
     * deadline at publication. Callers must not release/expire source or frontier pins mid-preparation.
     */
    public void stageNext(Preparation preparation) {
        Objects.requireNonNull(preparation);
        synchronized (preparation) {
            if (preparation.complete()) throw new IllegalStateException("Native preparation already complete");
            if (preparation.deadline != null) requireLive(preparation.deadline);
            int end = Math.min(preparation.offset + BATCH_SIZE, preparation.plan.additions().size());
            var values = preparation.plan.additions().subList(preparation.offset, end);
            List<UUID> roots = values.isEmpty() ? List.of(preparation.plan.snapshot().root().objectId())
                    : values.stream().map(value -> value.objectId()).toList();
            var pinned = storage.stageBatch(new StageBatch(preparation.plan.snapshot().root().reuseScopeId(),
                    preparation.actor, values, roots), preparation.lease);
            preparation.pins.addAll(pinned);
            for (StagedRoot pin : pinned) {
                if (preparation.deadline == null || pin.expiresAt().isBefore(preparation.deadline)) preparation.deadline = pin.expiresAt();
                if (pin.root().equals(preparation.plan.snapshot().root())) preparation.root = pin;
            }
            preparation.offset = end;
        }
    }

    /** A live root pin supplied/maintained by the caller must cover the entire multi-call read. */
    public void readNext(NativeSnapshotDecoder decoder) {
        List<UUID> ids = decoder.requestedIds();
        if (ids.isEmpty()) throw new IllegalStateException("Native read already complete");
        decoder.accept(storage.readBatch(decoder.root().reuseScopeId(), ids));
    }

    private void requireLive(Instant deadline) {
        if (!deadline.isAfter(clock.instant())) throw new NativeStorageFailure(NativeStorageFailure.Code.PREPARATION_EXPIRED);
    }

    /** Bounded caller-owned state; snapshots of pins are for authorized cleanup, never access tokens. */
    public static final class Preparation {
        private final NativeEncodingPlan plan;
        private final UUID actor;
        private final Duration lease;
        private final List<StagedRoot> pins = new ArrayList<>();
        private int offset;
        private Instant deadline;
        private StagedRoot root;

        private Preparation(NativeEncodingPlan plan, UUID actor, Duration lease, Instant deadline) {
            this.plan = plan;
            this.actor = actor;
            this.lease = lease;
            this.deadline = deadline;
        }

        public synchronized boolean complete() { return root != null && offset == plan.additions().size(); }
        public synchronized List<StagedRoot> pins() { return List.copyOf(pins); }
        public synchronized StagedRoot root() {
            if (!complete()) throw new NativeStorageFailure(NativeStorageFailure.Code.INCOMPLETE);
            return root;
        }
    }
}
