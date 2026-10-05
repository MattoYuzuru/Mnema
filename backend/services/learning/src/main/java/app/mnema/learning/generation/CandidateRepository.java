package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Candidate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SQL of the images an IMAGE search slot found ({@code generation_media_candidate}, #296) and of the node holds that go with them. Like
 * {@link GenerationRepository}, every state-changing statement runs inside a service transaction that took the session lock first.
 */
@Repository
class CandidateRepository {
    /** The contract's bound of candidates per slot. */
    static final int MAX_PER_SLOT = 12;
    /**
     * The state is read from the asset, not from the column (which is only the value written last): READY once the media pipeline made the
     * asset ready, FAILED once it rejected it or gave up, else VERIFYING, so a candidate never shows a state older than its asset.
     */
    private static final String COLUMNS = "c.candidate_id,c.artifact_id,c.slot_key,c.asset_id,c.source,c.source_id,c.title,c.author,c.license,"
            + "c.license_url,c.source_page_url,c.share_alike,c.width,c.height,c.created_at,CASE WHEN a.state='READY' THEN 'READY' "
            + "WHEN a.state IN ('REJECTED','FAILED_RETRYABLE','DELETED') THEN 'FAILED' ELSE 'VERIFYING' END AS state";
    private static final String FROM = " FROM app_learning.generation_media_candidate c JOIN app_learning.media_asset a ON a.asset_id=c.asset_id ";
    private static final RowMapper<Candidate> CANDIDATE = (row, ignored) -> new Candidate(row.getObject("candidate_id", UUID.class),
            row.getObject("artifact_id", UUID.class), row.getString("slot_key"), row.getObject("asset_id", UUID.class), row.getString("source"),
            row.getString("source_id"), row.getString("title"), row.getString("author"), row.getString("license"), row.getString("license_url"),
            row.getString("source_page_url"), row.getBoolean("share_alike"), row.getInt("width"), row.getInt("height"), row.getString("state"),
            GenerationRepository.instant(row, "created_at"));

    private final JdbcClient jdbc;

    CandidateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Candidate candidate, UUID sessionId, UUID owner) {
        jdbc.sql("INSERT INTO app_learning.generation_media_candidate(candidate_id,artifact_id,slot_key,session_id,owner_id,asset_id,source,source_id,"
                        + "title,author,license,license_url,source_page_url,share_alike,width,height,state,created_at) VALUES (:id,:artifact,:slot,"
                        + ":session,:owner,:asset,:source,:sourceId,:title,:author,:license,:licenseUrl,:page,:shareAlike,:width,:height,:state,clock_timestamp())")
                .param("id", candidate.candidateId()).param("artifact", candidate.artifactId()).param("slot", candidate.slotKey())
                .param("session", sessionId).param("owner", owner).param("asset", candidate.assetId()).param("source", candidate.source())
                .param("sourceId", candidate.sourceId()).param("title", candidate.title()).param("author", candidate.author())
                .param("license", candidate.license()).param("licenseUrl", candidate.licenseUrl()).param("page", candidate.sourcePageUrl())
                .param("shareAlike", candidate.shareAlike()).param("width", candidate.width()).param("height", candidate.height())
                .param("state", candidate.state()).update();
    }

    /** The candidates of a slot in the order they were found. */
    List<Candidate> ofSlot(UUID artifactId, String slotKey) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + "WHERE c.artifact_id=:artifact AND c.slot_key=:slot "
                + "ORDER BY c.created_at,c.candidate_id").param("artifact", artifactId).param("slot", slotKey).query(CANDIDATE).list();
    }

    /** The candidates of every slot of an artifact. */
    List<Candidate> ofArtifact(UUID artifactId) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + "WHERE c.artifact_id=:artifact "
                + "ORDER BY c.created_at,c.candidate_id").param("artifact", artifactId).query(CANDIDATE).list();
    }

    Optional<Candidate> find(UUID artifactId, UUID candidateId) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + "WHERE c.artifact_id=:artifact AND c.candidate_id=:id")
                .param("artifact", artifactId).param("id", candidateId).query(CANDIDATE).optional();
    }

    /** The {@code SOURCE:sourceId} keys a slot already has, which a new search must not bring again (failed ones included). */
    Set<String> keysOfSlot(UUID artifactId, String slotKey) {
        return ofSlot(artifactId, slotKey).stream().map(candidate -> candidate.source() + ":" + candidate.sourceId()).collect(Collectors.toSet());
    }

    int count(UUID artifactId, String slotKey) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_media_candidate WHERE artifact_id=:artifact AND slot_key=:slot")
                .param("artifact", artifactId).param("slot", slotKey).query(Integer.class).single();
    }

    void setState(UUID candidateId, String state) {
        jdbc.sql("UPDATE app_learning.generation_media_candidate SET state=:state WHERE candidate_id=:id")
                .param("state", state).param("id", candidateId).update();
    }

    // ------------------------------------------------------------------- slots

    /** The slot's visible state moves (GENERATING, VERIFYING, FAILED with a code); the asset stays. */
    void slotState(UUID artifactId, String slotKey, String state, String errorCode) {
        jdbc.sql("UPDATE app_learning.generation_media_slot SET state=:state,error_code=:code,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE artifact_id=:artifact AND slot_key=:slot AND state<>'REMOVED'")
                .param("state", state).param("code", errorCode).param("artifact", artifactId).param("slot", slotKey).update();
    }

    /** The slot is READY on {@code assetId}, the asset of its chosen candidate. */
    void slotReady(UUID artifactId, String slotKey, UUID assetId) {
        jdbc.sql("UPDATE app_learning.generation_media_slot SET state='READY',error_code=NULL,asset_id=:asset,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE artifact_id=:artifact AND slot_key=:slot AND state<>'REMOVED'")
                .param("asset", assetId).param("artifact", artifactId).param("slot", slotKey).update();
    }

    /** The Workshop's hold on the node goes (the slot's image is gone from the shown revision); the candidates stay held. */
    void releaseNode(UUID artifactId, UUID nodeId) {
        jdbc.sql("DELETE FROM app_learning.generation_media_ref WHERE artifact_id=:artifact AND node_id=:node")
                .param("artifact", artifactId).param("node", nodeId).update();
    }

    /** The Workshop's hold on the asset a media node uses (one per node), replaced when the node points at another asset. */
    void holdNode(UUID artifactId, UUID nodeId, UUID sessionId, UUID owner, UUID assetId) {
        jdbc.sql("INSERT INTO app_learning.generation_media_ref(artifact_id,node_id,session_id,owner_id,asset_id) VALUES (:artifact,:node,:session,"
                        + ":owner,:asset) ON CONFLICT (artifact_id,node_id) DO UPDATE SET asset_id=EXCLUDED.asset_id")
                .param("artifact", artifactId).param("node", nodeId).param("session", sessionId).param("owner", owner).param("asset", assetId).update();
    }
}
