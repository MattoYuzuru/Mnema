package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectRef;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Validated codec snapshot. A physical root is neither an ACL nor a publication receipt. */
public final class NativeSnapshot {
    private final ObjectRef root;
    private final NativeDocument document;
    private final Map<UUID, NewObject> objects;
    private final List<List<String>> fragments;

    NativeSnapshot(ObjectRef root, NativeDocument document, Map<UUID, NewObject> objects, List<List<String>> fragments) {
        this.root = root;
        this.document = document;
        this.objects = Map.copyOf(objects);
        this.fragments = fragments.stream().map(List::copyOf).toList();
    }

    public ObjectRef root() { return root; }
    public NativeDocument document() { return document; }
    public int objectCount() { return objects.size(); }
    Map<UUID, NewObject> objects() { return objects; }
    List<List<String>> fragments() { return fragments; }
}
