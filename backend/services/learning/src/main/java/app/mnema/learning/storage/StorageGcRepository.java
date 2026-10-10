package app.mnema.learning.storage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static app.mnema.learning.storage.StorageRepository.at;

/** Finds the next reuse scope with reclaimable work; the reclamation itself stays in {@link ImmutableStorage}. */
@Repository
class StorageGcRepository {
    /** Sorts before every real scope (entity ids are never nil). */
    static final UUID START = new UUID(0L, 0L);

    private final JdbcClient jdbc;

    StorageGcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The smallest scope greater than {@code after} (PostgreSQL uuid order) holding a garbage candidate past {@code garbageCutoff}
     * or an expired staging pin. Two index probes that stop at the first match; no scan of the whole queue.
     */
    Optional<UUID> nextScope(UUID after, Instant garbageCutoff, Instant now) {
        return jdbc.sql("""
                SELECT scope FROM (
                    SELECT (SELECT reuse_scope_id FROM app_learning.storage_gc_candidate
                             WHERE reuse_scope_id > :after AND not_before <= :cutoff
                             ORDER BY reuse_scope_id LIMIT 1) AS scope
                    UNION ALL
                    SELECT (SELECT reuse_scope_id FROM app_learning.storage_pin
                             WHERE reuse_scope_id > :after AND expires_at <= :now
                             ORDER BY reuse_scope_id LIMIT 1)
                ) found WHERE scope IS NOT NULL ORDER BY scope LIMIT 1
                """).param("after", after).param("cutoff", at(garbageCutoff)).param("now", at(now))
                .query(UUID.class).optional();
    }
}
