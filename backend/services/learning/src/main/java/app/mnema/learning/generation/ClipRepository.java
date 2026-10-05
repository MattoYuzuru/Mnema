package app.mnema.learning.generation;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of the clips a slot of a proposal has had ({@code generation_media_clip}, #297): the first one and every redo, with the voice and take each was
 * made with. All of them are held by the media GC while the session lives, so a revert can restore an earlier take. Like {@link CandidateRepository},
 * every statement runs inside a service transaction that took the session lock first.
 */
@Repository
class ClipRepository {
    /** One clip of a slot. */
    record Clip(UUID assetId, UUID artifactId, String slotKey, String voice, int take) { }

    private static final RowMapper<Clip> CLIP = (row, ignored) -> new Clip(row.getObject("asset_id", UUID.class), row.getObject("artifact_id", UUID.class),
            row.getString("slot_key"), row.getString("voice"), row.getInt("take"));

    private final JdbcClient jdbc;

    ClipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Clip clip, UUID sessionId, UUID owner) {
        jdbc.sql("INSERT INTO app_learning.generation_media_clip(asset_id,artifact_id,slot_key,session_id,owner_id,voice,take,created_at) "
                        + "VALUES (:asset,:artifact,:slot,:session,:owner,:voice,:take,clock_timestamp()) ON CONFLICT (asset_id) DO NOTHING")
                .param("asset", clip.assetId()).param("artifact", clip.artifactId()).param("slot", clip.slotKey()).param("session", sessionId)
                .param("owner", owner).param("voice", clip.voice()).param("take", clip.take()).update();
    }

    Optional<Clip> find(UUID artifactId, UUID assetId) {
        return jdbc.sql("SELECT asset_id,artifact_id,slot_key,voice,take FROM app_learning.generation_media_clip WHERE artifact_id=:artifact AND asset_id=:asset")
                .param("artifact", artifactId).param("asset", assetId).query(CLIP).optional();
    }

    /** The highest take any clip of the slot has had, -1 when it has had none: a redo must never repeat one (a revert moves the slot back to an older take). */
    int maxTake(UUID artifactId, String slotKey) {
        return jdbc.sql("SELECT COALESCE(max(take),-1) FROM app_learning.generation_media_clip WHERE artifact_id=:artifact AND slot_key=:slot")
                .param("artifact", artifactId).param("slot", slotKey).query(Integer.class).single();
    }

    List<Clip> ofArtifact(UUID artifactId) {
        return jdbc.sql("SELECT asset_id,artifact_id,slot_key,voice,take FROM app_learning.generation_media_clip WHERE artifact_id=:artifact "
                + "ORDER BY created_at,asset_id").param("artifact", artifactId).query(CLIP).list();
    }
}
