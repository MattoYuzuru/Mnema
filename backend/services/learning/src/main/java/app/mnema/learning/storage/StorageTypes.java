package app.mnema.learning.storage;

import app.mnema.learning.platform.id.UuidPolicy;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Physical references are internal locators, never deck identities or authorization capabilities. */
public final class StorageTypes {
    private StorageTypes() {
    }

    public enum ObjectKind { BLOCK, HEADER, FRAGMENT, PAGE }

    public record ObjectRef(UUID reuseScopeId, UUID objectId) {
        public ObjectRef {
            reuseScopeId = UuidPolicy.requireEntityId(reuseScopeId, "reuseScopeId");
            objectId = UuidPolicy.requireEntityId(objectId, "objectId");
        }
    }

    public record NewEdge(int ordinal, UUID logicalKey, ObjectRef child) {
        public NewEdge {
            Objects.requireNonNull(child, "child");
            if (ordinal < 0 || ordinal >= 32) throw new IllegalArgumentException("Invalid storage ordinal");
            if (logicalKey != null) UuidPolicy.requireEntityId(logicalKey, "logicalKey");
        }
    }

    public record NewObject(UUID objectId, ObjectKind kind, short encodingVersion, short dagRank,
                            JsonNode payload, List<NewEdge> edges) {
        public NewObject {
            objectId = UuidPolicy.requireEntityId(objectId, "objectId");
            Objects.requireNonNull(kind, "kind");
            payload = Objects.requireNonNull(payload, "payload").deepCopy();
            edges = List.copyOf(edges);
            if (!payload.isObject() || encodingVersion < 1 || dagRank < 0 || dagRank > 32 || edges.size() > 32
                    || (kind == ObjectKind.PAGE ? dagRank == 0 : dagRank != 0 || !edges.isEmpty())) {
                throw new IllegalArgumentException("Invalid storage object");
            }
            for (int i = 0; i < edges.size(); i++) {
                if (edges.get(i).ordinal() != i) throw new IllegalArgumentException("Non-contiguous storage edges");
            }
        }

        @Override
        public JsonNode payload() {
            return payload.deepCopy();
        }
    }

    public record StageBatch(UUID reuseScopeId, UUID actorId, List<NewObject> objects, List<UUID> rootObjectIds) {
        public StageBatch {
            reuseScopeId = UuidPolicy.requireEntityId(reuseScopeId, "reuseScopeId");
            actorId = UuidPolicy.requireEntityId(actorId, "actorId");
            objects = List.copyOf(objects);
            rootObjectIds = List.copyOf(rootObjectIds);
            if (objects.size() > 64 || rootObjectIds.isEmpty() || rootObjectIds.size() > 64
                    || objects.stream().map(NewObject::objectId).distinct().count() != objects.size()
                    || rootObjectIds.stream().distinct().count() != rootObjectIds.size()) {
                throw new IllegalArgumentException("Invalid storage batch");
            }
            rootObjectIds.forEach(id -> UuidPolicy.requireEntityId(id, "rootObjectId"));
            for (NewObject object : objects) {
                for (NewEdge edge : object.edges()) {
                    if (!edge.child().reuseScopeId().equals(reuseScopeId)) {
                        throw new IllegalArgumentException("Cross-scope storage edge");
                    }
                }
            }
        }
    }

    public record StagedRoot(ObjectRef root, UUID stagingPinId, Instant expiresAt) {
        public StagedRoot {
            Objects.requireNonNull(root, "root");
            stagingPinId = UuidPolicy.requireEntityId(stagingPinId, "stagingPinId");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public record PinOwner(String kind, UUID id, UUID actorId) {
        public PinOwner {
            if (kind == null || kind.length() > 80 || !kind.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")) {
                throw new IllegalArgumentException("Invalid pin owner kind");
            }
            id = UuidPolicy.requireEntityId(id, "ownerId");
            actorId = UuidPolicy.requireEntityId(actorId, "actorId");
        }
    }

    public record StoredObject(ObjectRef ref, NewObject value) {
        public StoredObject {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(value, "value");
        }
    }

    public record CollectionResult(int inspected, int deleted, int deferred) {
    }
}
