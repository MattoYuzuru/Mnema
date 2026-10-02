package app.mnema.learning.generation.mbm;

import app.mnema.learning.platform.id.UuidPolicy;

import java.util.UUID;

/** Production allocator: random UUIDv4 for nodes and assets. */
public final class RandomIdAllocator implements IdAllocator {

    @Override
    public UUID nextNodeId() {
        return UuidPolicy.newPortableId();
    }

    @Override
    public UUID nextAssetId() {
        return UuidPolicy.newPortableId();
    }
}
