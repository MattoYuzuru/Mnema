package app.mnema.learning.storage;

import app.mnema.learning.platform.concurrency.CompareAndSetExecutor;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static app.mnema.learning.storage.StorageTypes.*;

/**
 * Internal physical storage. Callers must authorize the requested domain revision/scope and pin owner.
 * No method grants access by knowledge of a physical ID, performs native validation or starts a scheduler.
 */
@Service
public class ImmutableStorage {
    private final StorageRepository repository;
    private final StorageEncoding encoding;
    private final StorageSettings settings;
    private final CompareAndSetExecutor cas;
    private final TransactionTemplate collectionTransaction;

    public ImmutableStorage(StorageRepository repository, StorageEncoding encoding, StorageSettings settings,
                            CompareAndSetExecutor cas, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.encoding = encoding;
        this.settings = settings;
        this.cas = cas;
        collectionTransaction = new TransactionTemplate(transactions);
        collectionTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        collectionTransaction.setTimeout(10);
    }

    /** Topological objects; repeat calls may reuse equal objects but issue fresh staging pins. */
    @Transactional(timeout = 10)
    public List<StagedRoot> stageBatch(StageBatch batch, Duration lease) {
        Objects.requireNonNull(batch, "batch");
        settings.requireLease(lease);
        repository.lockTimeout(settings.lockTimeout());
        List<NewObject> values = batch.objects().stream().map(repository::normalize).toList();
        encoding.requireBatchBudget(values);
        Instant now = repository.now();
        for (NewObject object : values) {
            ObjectRef ref = new ObjectRef(batch.reuseScopeId(), object.objectId());
            if (repository.insert(batch.reuseScopeId(), object, encoding.fingerprint(object))) {
                repository.insertEdges(batch.reuseScopeId(), object);
                repository.seal(ref);
                repository.enqueue(ref, now.plus(settings.orphanGrace()));
            } else {
                StoredObject existing = repository.find(ref, false)
                        .orElseThrow(() -> new StorageFailure(StorageFailure.Code.OBJECT_MISSING));
                // Fingerprints are not collision-free authority. Compare the complete normalized value and edges.
                if (!existing.value().equals(object)) throw new StorageFailure(StorageFailure.Code.OBJECT_MISMATCH);
            }
        }
        Instant expiry = repository.now().plus(lease);
        List<StagedRoot> roots = new ArrayList<>();
        for (UUID rootId : batch.rootObjectIds()) {
            ObjectRef ref = new ObjectRef(batch.reuseScopeId(), rootId);
            repository.childRank(ref);
            UUID pin = repository.pin(ref, "staging", new PinOwner("storage.stage", UUID.randomUUID(), batch.actorId()), expiry);
            roots.add(new StagedRoot(ref, pin, expiry));
        }
        return List.copyOf(roots);
    }

    /** Row key locks keep each immutable header and its edges coherent against concurrent collection. */
    @Transactional(timeout = 10)
    public List<StoredObject> readBatch(UUID scope, List<UUID> ids) {
        UuidPolicy.requireEntityId(scope, "reuseScopeId");
        List<UUID> ordered = List.copyOf(ids);
        if (ordered.size() > 64 || ordered.stream().distinct().count() != ordered.size()) {
            throw new StorageFailure(StorageFailure.Code.BUDGET_EXCEEDED);
        }
        repository.lockTimeout(settings.lockTimeout());
        var found = new java.util.HashMap<UUID, StoredObject>();
        for (UUID id : ordered.stream().sorted(Comparator.naturalOrder()).toList()) {
            ObjectRef ref = new ObjectRef(scope, id);
            found.put(id, repository.find(ref, false)
                    .orElseThrow(() -> new StorageFailure(StorageFailure.Code.OBJECT_MISSING)));
        }
        List<StoredObject> result = ordered.stream().map(found::get).toList();
        encoding.requireBatchBudget(result.stream().map(StoredObject::value).toList());
        return result;
    }

    /** Joins the publication receipt/CAS transaction; does not replace domain validation or authorization. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID retain(StagedRoot prepared, PinOwner owner) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(owner, "owner");
        repository.lockTimeout(settings.lockTimeout());
        var pin = repository.lockPin(prepared.root().reuseScopeId(), prepared.stagingPinId())
                .orElseThrow(() -> new StorageFailure(StorageFailure.Code.PREPARATION_EXPIRED));
        if (!pin.kind().equals("staging") || !pin.root().equals(prepared.root()) || !pin.actor().equals(owner.actorId())) {
            throw new StorageFailure(StorageFailure.Code.INVALID_PREPARATION);
        }
        if (!pin.expiry().isAfter(repository.now())) throw new StorageFailure(StorageFailure.Code.PREPARATION_EXPIRED);
        return repository.pin(pin.root(), "durable", owner, null);
    }

    /** Caller owns pin authorization. Missing pins are an idempotent no-op. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID scope, UUID pinId) {
        UuidPolicy.requireEntityId(scope, "reuseScopeId");
        UuidPolicy.requireEntityId(pinId, "pinId");
        repository.lockTimeout(settings.lockTimeout());
        repository.lockPin(scope, pinId).ifPresent(pin -> {
            repository.removePin(scope, pinId);
            repository.enqueue(pin.root(), repository.now().plus(settings.orphanGrace()));
        });
    }

    @Transactional(timeout = 10)
    public long renewStaging(UUID scope, UUID pinId, long expectedVersion, Duration extension) {
        UuidPolicy.requireEntityId(scope, "reuseScopeId");
        UuidPolicy.requireEntityId(pinId, "pinId");
        settings.requireLease(extension);
        repository.lockTimeout(settings.lockTimeout());
        var pin = repository.lockPin(scope, pinId)
                .orElseThrow(() -> new StorageFailure(StorageFailure.Code.PREPARATION_EXPIRED));
        Instant now = repository.now();
        if (!pin.kind().equals("staging") || !pin.expiry().isAfter(now)) {
            throw new StorageFailure(StorageFailure.Code.PREPARATION_EXPIRED);
        }
        Instant expiry = now.plus(extension).isAfter(pin.expiry()) ? now.plus(extension) : pin.expiry();
        return cas.updateOne(expectedVersion, () -> repository.renew(scope, pinId, expectedVersion, expiry));
    }

    /** Explicit bounded primitive only; durable pins never participate in expiry. */
    @Transactional(propagation = Propagation.NEVER)
    public int expireStaging(UUID scope, Instant cutoff, int limit) {
        requireCollection(scope, cutoff, limit);
        return retryBatch(() -> {
            Instant now = repository.now();
            List<UUID> expired = repository.expired(scope, cutoff.isBefore(now) ? cutoff : now, limit);
            for (UUID pinId : expired) {
                var pin = repository.lockPin(scope, pinId).orElseThrow();
                repository.removePin(scope, pinId);
                repository.enqueue(pin.root(), now.plus(settings.orphanGrace()));
            }
            return expired.size();
        });
    }

    /** No mark recursion: at most eight candidates and 32 outgoing edges per candidate. */
    @Transactional(propagation = Propagation.NEVER)
    public CollectionResult collectBatch(UUID scope, Instant cutoff, int limit) {
        requireCollection(scope, cutoff, limit);
        return retryBatch(() -> {
            Instant now = repository.now();
            Instant eligibleAt = cutoff.isBefore(now) ? cutoff : now;
            List<ObjectRef> candidates = repository.candidates(scope, eligibleAt, limit);
            int deleted = 0;
            int deferred = 0;
            for (ObjectRef ref : candidates) {
                // Removing an earlier parent can postpone a child in this same locked batch.
                // Recheck against the original cutoff, not a later wall-clock reading.
                if (!repository.candidateReady(ref, eligibleAt)) {
                    deferred++;
                    continue;
                }
                var object = repository.find(ref, true);
                if (object.isEmpty()) {
                    repository.removeCandidate(ref);
                    continue;
                }
                if (repository.referenced(ref)) {
                    repository.removeCandidate(ref);
                    deferred++;
                } else {
                    var children = object.get().value().edges();
                    repository.removeObject(ref);
                    for (NewEdge child : children) repository.enqueue(child.child(), now.plus(settings.orphanGrace()));
                    deleted++;
                }
            }
            return new CollectionResult(candidates.size(), deleted, deferred);
        });
    }

    private <T> T retryBatch(Supplier<T> operation) {
        for (int attempt = 0; ; attempt++) {
            try {
                return collectionTransaction.execute(status -> {
                    repository.lockTimeout(settings.lockTimeout());
                    return operation.get();
                });
            } catch (PessimisticLockingFailureException exception) {
                if (attempt == 2) throw exception;
            }
        }
    }

    private static void requireCollection(UUID scope, Instant cutoff, int limit) {
        UuidPolicy.requireEntityId(scope, "reuseScopeId");
        Objects.requireNonNull(cutoff, "cutoff");
        if (limit < 1 || limit > 8) throw new IllegalArgumentException("Invalid storage collection batch");
    }
}
