package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectRef;

import java.util.List;
import java.util.Optional;

/** Bounded caller-owned preparation data, with new objects in children-before-parent order. */
public final class NativeEncodingPlan {
    private final NativeSnapshot snapshot;
    private final List<NewObject> additions;
    private final ObjectRef sourceRoot;

    NativeEncodingPlan(NativeSnapshot snapshot, List<NewObject> additions, ObjectRef sourceRoot) {
        this.snapshot = snapshot;
        this.additions = List.copyOf(additions);
        this.sourceRoot = sourceRoot;
    }

    public NativeSnapshot snapshot() { return snapshot; }
    public List<NewObject> additions() { return additions; }
    public Optional<ObjectRef> sourceRoot() { return Optional.ofNullable(sourceRoot); }
}
