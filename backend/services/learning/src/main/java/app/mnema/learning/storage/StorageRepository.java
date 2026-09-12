package app.mnema.learning.storage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static app.mnema.learning.storage.StorageTypes.*;

@Repository
class StorageRepository {
    private final JdbcClient jdbc;
    private final StorageEncoding encoding;

    StorageRepository(JdbcClient jdbc, StorageEncoding encoding) {
        this.jdbc = jdbc;
        this.encoding = encoding;
    }

    void lockTimeout(Duration timeout) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", timeout.toMillis() + "ms").query(String.class).single();
    }

    Instant now() {
        return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    NewObject normalize(NewObject object) {
        String payload = encoding.validatePayload(object.payload());
        String normalized = jdbc.sql("SELECT CAST(:payload AS jsonb)::text")
                .param("payload", payload).query(String.class).single();
        return new NewObject(object.objectId(), object.kind(), object.encodingVersion(), object.dagRank(),
                encoding.readPayload(normalized), object.edges());
    }

    boolean insert(UUID scope, NewObject object, byte[] fingerprint) {
        return jdbc.sql("""
                INSERT INTO app_learning.storage_object
                    (reuse_scope_id,object_id,kind,encoding_version,dag_rank,edge_count,payload,fingerprint)
                VALUES (:scope,:id,:kind,:version,:rank,:count,CAST(:payload AS jsonb),:fingerprint)
                ON CONFLICT (reuse_scope_id,object_id) DO NOTHING RETURNING object_id
                """).param("scope", scope).param("id", object.objectId())
                .param("kind", object.kind().name().toLowerCase(Locale.ROOT))
                .param("version", object.encodingVersion()).param("rank", object.dagRank())
                .param("count", object.edges().size()).param("payload", encoding.validatePayload(object.payload()))
                .param("fingerprint", fingerprint).query(UUID.class).optional().isPresent();
    }

    short childRank(ObjectRef ref) {
        return jdbc.sql("""
                SELECT dag_rank FROM app_learning.storage_object
                WHERE reuse_scope_id=:scope AND object_id=:id AND sealed FOR KEY SHARE
                """).param("scope", ref.reuseScopeId()).param("id", ref.objectId())
                .query(Short.class).optional().orElseThrow(() -> new StorageFailure(StorageFailure.Code.OBJECT_MISSING));
    }

    void insertEdges(UUID scope, NewObject object) {
        for (NewEdge edge : object.edges()) {
            short childRank = childRank(edge.child());
            if (childRank >= object.dagRank()) throw new IllegalArgumentException("Invalid physical DAG rank");
            jdbc.sql("""
                    INSERT INTO app_learning.storage_edge
                        (reuse_scope_id,parent_id,ordinal,parent_rank,child_id,child_rank,logical_key)
                    VALUES (:scope,:parent,:ordinal,:parentRank,:child,:childRank,:key)
                    """).param("scope", scope).param("parent", object.objectId())
                    .param("ordinal", edge.ordinal()).param("parentRank", object.dagRank())
                    .param("child", edge.child().objectId()).param("childRank", childRank)
                    .param("key", edge.logicalKey(), java.sql.Types.OTHER).update();
        }
    }

    void seal(ObjectRef ref) {
        jdbc.sql("UPDATE app_learning.storage_object SET sealed=true WHERE reuse_scope_id=:scope AND object_id=:id")
                .param("scope", ref.reuseScopeId()).param("id", ref.objectId()).update();
    }

    Optional<StoredObject> find(ObjectRef ref, boolean exclusive) {
        String lock = exclusive ? " FOR UPDATE" : " FOR KEY SHARE";
        var header = jdbc.sql("""
                SELECT kind,encoding_version,dag_rank,payload::text FROM app_learning.storage_object
                WHERE reuse_scope_id=:scope AND object_id=:id AND sealed
                """ + lock).param("scope", ref.reuseScopeId()).param("id", ref.objectId())
                .query((row, ignored) -> new Header(ObjectKind.valueOf(row.getString(1).toUpperCase(Locale.ROOT)),
                        row.getShort(2), row.getShort(3), row.getString(4))).optional();
        return header.map(value -> new StoredObject(ref, new NewObject(ref.objectId(), value.kind(), value.version(),
                value.rank(), encoding.readPayload(value.payload()), edges(ref))));
    }

    List<NewEdge> edges(ObjectRef ref) {
        return jdbc.sql("""
                SELECT ordinal,logical_key,child_id FROM app_learning.storage_edge
                WHERE reuse_scope_id=:scope AND parent_id=:id ORDER BY ordinal
                """).param("scope", ref.reuseScopeId()).param("id", ref.objectId())
                .query((row, ignored) -> new NewEdge(row.getInt(1), row.getObject(2, UUID.class),
                        new ObjectRef(ref.reuseScopeId(), row.getObject(3, UUID.class)))).list();
    }

    void enqueue(ObjectRef ref, Instant eligible) {
        jdbc.sql("""
                INSERT INTO app_learning.storage_gc_candidate(reuse_scope_id,object_id,not_before)
                VALUES (:scope,:id,:eligible)
                ON CONFLICT (reuse_scope_id,object_id) DO UPDATE SET
                    not_before=GREATEST(app_learning.storage_gc_candidate.not_before,EXCLUDED.not_before)
                """).param("scope", ref.reuseScopeId()).param("id", ref.objectId())
                .param("eligible", at(eligible)).update();
    }

    UUID pin(ObjectRef ref, String kind, PinOwner owner, Instant expiry) {
        UUID pinId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO app_learning.storage_pin
                    (reuse_scope_id,pin_id,root_id,pin_kind,owner_kind,owner_id,actor_id,expires_at)
                VALUES (:scope,:pin,:root,:kind,:ownerKind,:owner,:actor,:expiry)
                """).param("scope", ref.reuseScopeId()).param("pin", pinId).param("root", ref.objectId())
                .param("kind", kind).param("ownerKind", owner.kind()).param("owner", owner.id())
                .param("actor", owner.actorId()).param("expiry", expiry == null ? null : at(expiry), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return pinId;
    }

    Optional<Pin> lockPin(UUID scope, UUID pinId) {
        return jdbc.sql("""
                SELECT root_id,pin_kind,actor_id,expires_at,row_version FROM app_learning.storage_pin
                WHERE reuse_scope_id=:scope AND pin_id=:pin FOR UPDATE
                """).param("scope", scope).param("pin", pinId).query((row, ignored) -> {
                    OffsetDateTime expiry = row.getObject(4, OffsetDateTime.class);
                    return new Pin(new ObjectRef(scope, row.getObject(1, UUID.class)), row.getString(2),
                            row.getObject(3, UUID.class), expiry == null ? null : expiry.toInstant(), row.getLong(5));
                }).optional();
    }

    void removePin(UUID scope, UUID pinId) {
        jdbc.sql("DELETE FROM app_learning.storage_pin WHERE reuse_scope_id=:scope AND pin_id=:pin")
                .param("scope", scope).param("pin", pinId).update();
    }

    int renew(UUID scope, UUID pinId, long expectedVersion, Instant expiry) {
        return jdbc.sql("""
                UPDATE app_learning.storage_pin SET expires_at=:expiry,row_version=row_version+1
                WHERE reuse_scope_id=:scope AND pin_id=:pin AND row_version=:version
                  AND pin_kind='staging' AND expires_at>clock_timestamp()
                """).param("scope", scope).param("pin", pinId).param("version", expectedVersion)
                .param("expiry", at(expiry)).update();
    }

    List<UUID> expired(UUID scope, Instant cutoff, int limit) {
        return jdbc.sql("""
                SELECT pin_id FROM app_learning.storage_pin
                WHERE reuse_scope_id=:scope AND expires_at<=:cutoff
                ORDER BY expires_at,pin_id LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("scope", scope).param("cutoff", at(cutoff)).param("limit", limit).query(UUID.class).list();
    }

    List<ObjectRef> candidates(UUID scope, Instant cutoff, int limit) {
        return jdbc.sql("""
                SELECT object_id FROM app_learning.storage_gc_candidate
                WHERE reuse_scope_id=:scope AND not_before<=:cutoff
                ORDER BY not_before,object_id LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("scope", scope).param("cutoff", at(cutoff)).param("limit", limit)
                .query((row, ignored) -> new ObjectRef(scope, row.getObject(1, UUID.class))).list();
    }

    /** The caller already holds this candidate's FOR UPDATE lock for the batch. */
    boolean candidateReady(ObjectRef ref, Instant cutoff) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM app_learning.storage_gc_candidate
                    WHERE reuse_scope_id=:scope AND object_id=:id AND not_before<=:cutoff)
                """).param("scope", ref.reuseScopeId()).param("id", ref.objectId())
                .param("cutoff", at(cutoff)).query(Boolean.class).single();
    }

    boolean referenced(ObjectRef ref) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM app_learning.storage_edge WHERE reuse_scope_id=:scope AND child_id=:id)
                    OR EXISTS(SELECT 1 FROM app_learning.storage_pin WHERE reuse_scope_id=:scope AND root_id=:id)
                """).param("scope", ref.reuseScopeId()).param("id", ref.objectId()).query(Boolean.class).single();
    }

    void removeCandidate(ObjectRef ref) {
        jdbc.sql("DELETE FROM app_learning.storage_gc_candidate WHERE reuse_scope_id=:scope AND object_id=:id")
                .param("scope", ref.reuseScopeId()).param("id", ref.objectId()).update();
    }

    void removeObject(ObjectRef ref) {
        jdbc.sql("DELETE FROM app_learning.storage_object WHERE reuse_scope_id=:scope AND object_id=:id")
                .param("scope", ref.reuseScopeId()).param("id", ref.objectId()).update();
    }

    static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record Header(ObjectKind kind, short version, short rank, String payload) {
    }

    record Pin(ObjectRef root, String kind, UUID actor, Instant expiry, long version) {
    }
}
