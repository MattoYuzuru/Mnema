package app.mnema.learning.media;

import java.nio.file.Path;
import java.util.UUID;

/** Executes one bounded, offline derivation from a private source file. */
interface MediaWorkerGateway {
    void run(Path job, UUID assetId, long generation, String kind,
             long length, String sha256, long maxDurationMs);
}
