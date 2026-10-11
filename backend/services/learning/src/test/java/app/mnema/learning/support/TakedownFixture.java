package app.mnema.learning.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

/**
 * Legal takedowns for the tests of the READERS (Share/15, #437), written the way the console's decision writes them but without the console: one complaint
 * row of a made-up reporter and a {@code content_takedown} row per revision. The decision flow itself is tested through HTTP
 * ({@code ComplaintHttpIntegrationTest}); here the predicate is exercised by every reader of content.
 */
public final class TakedownFixture {
    private final JdbcClient jdbc;

    public TakedownFixture(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** The lineage of a deck. */
    public UUID scope(UUID deck) {
        return jdbc.sql("SELECT reuse_scope_id FROM app_learning.deck WHERE deck_id = :deck").param("deck", deck).query(UUID.class).single();
    }

    /** Takes the material revision down for the whole lineage. */
    public void item(UUID deck, UUID member, UUID revision) { take(scope(deck), "ITEM", member, revision); }

    /** Takes the exercise revision down for the whole lineage. */
    public void exercise(UUID deck, UUID exercise, UUID revision) { take(scope(deck), "EXERCISE", exercise, revision); }

    public void take(UUID scope, String kind, UUID key, UUID revision) {
        UUID complaint = jdbc.sql("""
                INSERT INTO app_learning.content_complaint(target_kind, deck_id, public_code, reason, reporter_id, status, decision, created_at, due_at, decided_at,
                        decided_by, retain_until)
                VALUES ('DECK', :deck, 'Kq7xT3mNpR', 'COPYRIGHT', :reporter, 'DECIDED', 'TAKEN_DOWN', clock_timestamp(), clock_timestamp() + interval '30 days',
                        clock_timestamp(), :actor, clock_timestamp() + interval '3 years') RETURNING complaint_id
                """).param("deck", UUID.randomUUID()).param("reporter", UUID.randomUUID()).param("actor", UUID.randomUUID()).query(UUID.class).single();
        jdbc.sql("INSERT INTO app_learning.content_takedown(reuse_scope_id, unit_kind, unit_key, revision_id, complaint_id, created_at) "
                        + "VALUES (:scope, :kind, :key, :revision, :complaint, clock_timestamp())")
                .param("scope", scope).param("kind", kind).param("key", key).param("revision", revision).param("complaint", complaint).update();
        // what the console's decision also records: the media assets of the named revision, blocked for the whole lineage
        jdbc.sql("""
                INSERT INTO app_learning.content_takedown_asset(reuse_scope_id, asset_id, takedown_id)
                SELECT DISTINCT t.reuse_scope_id, r.asset_id, t.takedown_id FROM app_learning.content_takedown t
                  JOIN app_learning.content_media_ref r ON r.reuse_scope_id = t.reuse_scope_id AND r.member_key = t.unit_key AND r.revision_id = t.revision_id
                 WHERE t.complaint_id = :complaint AND t.unit_kind = 'ITEM'
                UNION
                SELECT DISTINCT t.reuse_scope_id, r.asset_id, t.takedown_id FROM app_learning.content_takedown t
                  JOIN app_learning.exercise_media_ref r ON r.reuse_scope_id = t.reuse_scope_id AND r.exercise_id = t.unit_key AND r.exercise_revision_id = t.revision_id
                 WHERE t.complaint_id = :complaint AND t.unit_kind = 'EXERCISE'
                ON CONFLICT DO NOTHING
                """).param("complaint", complaint).update();
    }
}
