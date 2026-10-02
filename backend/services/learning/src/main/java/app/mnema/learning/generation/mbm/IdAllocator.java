package app.mnema.learning.generation.mbm;

import java.util.UUID;

/**
 * Injected identifier port: the compiler never generates identifiers itself, so goldens are deterministic and the
 * server owns the identifier policy. Both methods must return UUIDv4 values.
 */
public interface IdAllocator {

    /** A new node identifier. The compiler asks again if the value collides with an identifier already in the tree. */
    UUID nextNodeId();

    /** A pre-allocated identifier for the asset a media directive will produce later. */
    UUID nextAssetId();
}
