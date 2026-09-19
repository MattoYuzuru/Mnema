package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.platform.id.UuidPolicy;

import java.util.UUID;

/** Explicit intent; indices address direct native children, and move uses after-removal indexing. */
public sealed interface NativeStructuralEdit {
    record Insert(UUID nodeId, UUID parentId, int childIndex) implements NativeStructuralEdit {
        public Insert {
            UuidPolicy.requireEntityId(nodeId, "nodeId"); UuidPolicy.requireEntityId(parentId, "parentId");
            if (childIndex < 0) throw new IllegalArgumentException("Invalid child index");
        }
    }
    record Delete(UUID nodeId) implements NativeStructuralEdit {
        public Delete { UuidPolicy.requireEntityId(nodeId, "nodeId"); }
    }
    record Move(UUID nodeId, UUID parentId, int childIndexAfterRemoval) implements NativeStructuralEdit {
        public Move {
            UuidPolicy.requireEntityId(nodeId, "nodeId"); UuidPolicy.requireEntityId(parentId, "parentId");
            if (childIndexAfterRemoval < 0) throw new IllegalArgumentException("Invalid child index");
        }
    }
}
