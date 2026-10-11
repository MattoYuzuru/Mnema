package app.mnema.learning.moderation;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SQL of complaints, takedowns and the journal (Share/15). Every method takes the time it should use (the service's clock), none opens a transaction; the
 * ones that say "lock" are used inside the caller's transaction only. Lists are keyset pages over the partial indexes of V58.
 */
@Repository
class ComplaintRepository {
    private static final String COLUMNS = """
            complaint_id, target_kind, deck_id, public_code, profile_account_id, profile_username, reason, reporter_text, reporter_id, status, decision,
            created_at, due_at, decided_at, decided_by, retain_until""";
    private static final RowMapper<Complaint> COMPLAINT = (row, ignored) -> new Complaint(row.getObject("complaint_id", UUID.class), row.getString("target_kind"),
            row.getObject("deck_id", UUID.class), row.getString("public_code"), row.getObject("profile_account_id", UUID.class), row.getString("profile_username"),
            row.getString("reason"), row.getString("reporter_text"), row.getObject("reporter_id", UUID.class), row.getString("status"), row.getString("decision"),
            instant(row.getTimestamp("created_at")), instant(row.getTimestamp("due_at")), instant(row.getTimestamp("decided_at")),
            row.getObject("decided_by", UUID.class), instant(row.getTimestamp("retain_until")));

    /** One unit of a takedown. */
    record Unit(String kind, UUID key, UUID revisionId) { }

    /** A standing takedown row. */
    record Takedown(UUID takedownId, String unitKind, UUID unitKey, UUID revisionId, Instant createdAt) { }

    /** One journal row. */
    record Journal(UUID journalId, UUID complaintId, String action, UUID actorId, Instant occurredAt, String targetKind, UUID deckId, String publicCode,
                   UUID profileAccountId, int unitCount) { }

    private final JdbcClient jdbc;

    ComplaintRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }

    /** A transaction-scoped advisory lock on a named resource (serializes the daily limit of one reporter). */
    void lock(String resource, UUID id) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) lock").param("key", resource + ":" + id).query(Integer.class).single();
    }

    // ---- filing ----

    boolean openExists(UUID reporter, String targetKind, UUID target) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM app_learning.content_complaint
                                WHERE reporter_id = :reporter AND target_kind = :kind AND COALESCE(deck_id, profile_account_id) = :target AND status = 'OPEN')
                """).param("reporter", reporter).param("kind", targetKind).param("target", target).query(Boolean.class).single();
    }

    /** Creation times of the reporter's complaints since {@code since}, oldest first. */
    List<Instant> createdSince(UUID reporter, Instant since) {
        return jdbc.sql("SELECT created_at FROM app_learning.content_complaint WHERE reporter_id = :reporter AND created_at > :since ORDER BY created_at")
                .param("reporter", reporter).param("since", time(since)).query((row, ignored) -> instant(row.getTimestamp(1))).list();
    }

    void insertDeck(UUID reporter, UUID deck, String code, String reason, String text, Instant now, Instant due, Instant retain) {
        jdbc.sql("""
                INSERT INTO app_learning.content_complaint(target_kind, deck_id, public_code, reason, reporter_text, reporter_id, status, created_at, due_at, retain_until)
                VALUES ('DECK', :deck, :code, :reason, :text, :reporter, 'OPEN', :now, :due, :retain)
                """).param("deck", deck).param("code", code).param("reason", reason).param("text", text, java.sql.Types.VARCHAR).param("reporter", reporter)
                .param("now", time(now)).param("due", time(due)).param("retain", time(retain)).update();
    }

    void insertProfile(UUID reporter, UUID account, String username, String reason, String text, Instant now, Instant due, Instant retain) {
        jdbc.sql("""
                INSERT INTO app_learning.content_complaint(target_kind, profile_account_id, profile_username, reason, reporter_text, reporter_id, status, created_at,
                        due_at, retain_until)
                VALUES ('PROFILE', :account, :username, :reason, :text, :reporter, 'OPEN', :now, :due, :retain)
                """).param("account", account).param("username", username).param("reason", reason).param("text", text, java.sql.Types.VARCHAR)
                .param("reporter", reporter).param("now", time(now)).param("due", time(due)).param("retain", time(retain)).update();
    }

    // ---- console ----

    Optional<Complaint> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.content_complaint WHERE complaint_id = :id").param("id", id).query(COMPLAINT).optional();
    }

    /** The complaint locked until the end of the transaction: decisions on it serialize. */
    Optional<Complaint> lockComplaint(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.content_complaint WHERE complaint_id = :id FOR UPDATE").param("id", id).query(COMPLAINT).optional();
    }

    /** One more row than {@code limit}: the caller sees whether another page exists. OPEN: oldest deadline first; DECIDED: newest decision first. */
    List<Complaint> page(boolean open, ComplaintCursor after, int limit) {
        if (open) {
            return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.content_complaint WHERE status = 'OPEN' AND (CAST(:at AS timestamptz) IS NULL OR "
                            + "(due_at, complaint_id) > (CAST(:at AS timestamptz), CAST(:id AS uuid))) ORDER BY due_at, complaint_id LIMIT :limit")
                    .param("at", after == null ? null : time(after.time()), java.sql.Types.TIMESTAMP).param("id", after == null ? null : after.id(), java.sql.Types.OTHER)
                    .param("limit", limit + 1).query(COMPLAINT).list();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.content_complaint WHERE status = 'DECIDED' AND (CAST(:at AS timestamptz) IS NULL OR "
                        + "(decided_at, complaint_id) < (CAST(:at AS timestamptz), CAST(:id AS uuid))) ORDER BY decided_at DESC, complaint_id DESC LIMIT :limit")
                .param("at", after == null ? null : time(after.time()), java.sql.Types.TIMESTAMP).param("id", after == null ? null : after.id(), java.sql.Types.OTHER)
                .param("limit", limit + 1).query(COMPLAINT).list();
    }

    /** Titles of the published revisions of the decks of a page of complaints: one statement, no N+1. */
    java.util.Map<UUID, String> deckTitles(Collection<UUID> decks) {
        java.util.Map<UUID, String> result = new java.util.HashMap<>();
        if (decks.isEmpty()) return result;
        jdbc.sql("""
                SELECT p.deck_id, r.title FROM app_learning.deck_publication p
                  JOIN app_learning.deck_revision r ON r.deck_id = p.deck_id AND r.revision_id = p.published_revision_id
                 WHERE p.deck_id IN (:decks)
                """).param("decks", decks).query((row, ignored) -> result.put(row.getObject(1, UUID.class), row.getString(2))).list();
        return result;
    }

    int openAbout(UUID deck) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.content_complaint WHERE deck_id = :deck AND status = 'OPEN'").param("deck", deck)
                .query(Integer.class).single();
    }

    void decide(UUID id, String decision, UUID actor, Instant now) {
        jdbc.sql("UPDATE app_learning.content_complaint SET status = 'DECIDED', decision = :decision, decided_at = :now, decided_by = :actor "
                        + "WHERE complaint_id = :id AND status = 'OPEN'")
                .param("decision", decision).param("now", time(now)).param("actor", actor).param("id", id).update();
    }

    // ---- takedowns ----

    /** Which of the named revisions exist in the lineage (one statement per kind): the validation of an explicit revision. */
    Set<UUID> existingItemRevisions(UUID scope, Collection<Unit> units) {
        return existing("SELECT revision_id FROM app_learning.item_revision r JOIN unnest(CAST(:keys AS uuid[]), CAST(:revisions AS uuid[])) AS u(k, v) "
                + "ON r.member_key = u.k AND r.revision_id = u.v WHERE r.reuse_scope_id = :scope", scope, units);
    }

    Set<UUID> existingExerciseRevisions(UUID scope, Collection<Unit> units) {
        return existing("SELECT revision_id FROM app_learning.exercise_revision r JOIN unnest(CAST(:keys AS uuid[]), CAST(:revisions AS uuid[])) AS u(k, v) "
                + "ON r.exercise_id = u.k AND r.revision_id = u.v WHERE r.reuse_scope_id = :scope", scope, units);
    }

    private Set<UUID> existing(String sql, UUID scope, Collection<Unit> units) {
        if (units.isEmpty()) return Set.of();
        return new java.util.HashSet<>(jdbc.sql(sql).param("scope", scope).param("keys", units.stream().map(Unit::key).toArray(UUID[]::new))
                .param("revisions", units.stream().map(Unit::revisionId).toArray(UUID[]::new)).query(UUID.class).list());
    }

    /** Inserts the takedown rows; a row that exists is skipped. @return the NEW rows as (kind, count) */
    int[] insertTakedowns(UUID scope, UUID complaint, Collection<Unit> units, Instant now) {
        int materials = 0;
        int exercises = 0;
        for (Unit unit : units) {
            int inserted = jdbc.sql("""
                    INSERT INTO app_learning.content_takedown(reuse_scope_id, unit_kind, unit_key, revision_id, complaint_id, created_at)
                    VALUES (:scope, :kind, :key, :revision, :complaint, :now) ON CONFLICT (reuse_scope_id, unit_kind, revision_id) DO NOTHING
                    """).param("scope", scope).param("kind", unit.kind()).param("key", unit.key()).param("revision", unit.revisionId())
                    .param("complaint", complaint).param("now", time(now)).update();
            if (inserted == 1) {
                if ("ITEM".equals(unit.kind())) materials++;
                else exercises++;
            }
        }
        return new int[] {materials, exercises};
    }

    int takedownCount(UUID complaint) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.content_takedown WHERE complaint_id = :complaint").param("complaint", complaint)
                .query(Integer.class).single();
    }

    List<Takedown> takedowns(UUID complaint, int limit) {
        return jdbc.sql("SELECT takedown_id, unit_kind, unit_key, revision_id, created_at FROM app_learning.content_takedown WHERE complaint_id = :complaint "
                        + "ORDER BY takedown_id LIMIT :limit").param("complaint", complaint).param("limit", limit)
                .query((row, ignored) -> new Takedown(row.getObject(1, UUID.class), row.getString(2), row.getObject(3, UUID.class), row.getObject(4, UUID.class),
                        instant(row.getTimestamp(5)))).list();
    }

    // ---- journal ----

    UUID appendJournal(Complaint complaint, String action, UUID actor, Instant now, int units, Instant retain) {
        return jdbc.sql("""
                INSERT INTO app_learning.content_takedown_journal(complaint_id, action, actor_id, occurred_at, target_kind, deck_id, public_code, profile_account_id,
                        unit_count, retain_until)
                VALUES (:complaint, :action, :actor, :now, :kind, :deck, :code, :account, :units, :retain) RETURNING journal_id
                """).param("complaint", complaint.id()).param("action", action).param("actor", actor).param("now", time(now)).param("kind", complaint.targetKind())
                .param("deck", complaint.deckId(), java.sql.Types.OTHER).param("code", complaint.publicCode(), java.sql.Types.VARCHAR)
                .param("account", complaint.profileAccountId(), java.sql.Types.OTHER).param("units", units).param("retain", time(retain)).query(UUID.class).single();
    }

    List<Journal> journal(UUID before, int limit) {
        return jdbc.sql("SELECT journal_id, complaint_id, action, actor_id, occurred_at, target_kind, deck_id, public_code, profile_account_id, unit_count "
                        + "FROM app_learning.content_takedown_journal WHERE (CAST(:before AS uuid) IS NULL OR journal_id < CAST(:before AS uuid)) "
                        + "ORDER BY journal_id DESC LIMIT :limit")
                .param("before", before, java.sql.Types.OTHER).param("limit", limit + 1)
                .query((row, ignored) -> new Journal(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getString(3), row.getObject(4, UUID.class),
                        instant(row.getTimestamp(5)), row.getString(6), row.getObject(7, UUID.class), row.getString(8), row.getObject(9, UUID.class),
                        row.getInt(10))).list();
    }
}
