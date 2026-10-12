package app.mnema.learning.update;

import app.mnema.learning.catalog.exercise.ExerciseRevisionVisibility;
import app.mnema.learning.catalog.item.ItemRevisionVisibility;
import app.mnema.learning.moderation.ContentTakedowns;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The SQL of the combined publication. Every statement that writes many rows takes arrays and {@code unnest}s them, so a command of N operations is a handful of statements
 * (chunks of {@value #CHUNK}) and not N round trips. Heads are always read and written by the deck's own rows ({@code deck_head_*}, {@code objective_head}); the lineage rows
 * ({@code item_revision}, {@code exercise_revision}, {@code objective_revision}) are reached through the deck's scope and only to READ the revision a head moves to: nothing here
 * writes a lineage row.
 */
@Repository
class CombinedPublicationRepository {
    /** Rows per statement: arrays of this size keep every statement small and its plan a plain primary-key join. */
    static final int CHUNK = 1_000;
    private static final String BLOCKED_ITEM = ContentTakedowns.blockedItem("r.reuse_scope_id", "r.member_key", "r.revision_id");
    private static final String BLOCKED_EXERCISE = ContentTakedowns.blockedExerciseOrQuote("r.reuse_scope_id", "r.exercise_id", "r.revision_id");

    private final JdbcClient jdbc;

    CombinedPublicationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    record DeckState(UUID deckId, UUID ownerId, UUID scopeId, UUID revisionId, long version, String title, String description,
                     UUID membersRootId, UUID exercisesRootId, int memberCount, int exerciseCount, Fork fork) { }

    /** The copy relation of a deck (its live {@code deck_fork} row): the upstream deck, whether the copy is materialized and whether the owner detached it. */
    record Fork(UUID upstreamDeckId, boolean materialized, boolean detached) { }

    /** A head: the revision the deck holds for a key and its sequence. */
    record Head(UUID revisionId, long sequence) { }

    /**
     * A lineage revision a head can move to: its sequence, the descriptor object the manifest entry points at, whether it is taken down, and whether it is
     * {@code visible} to the deck itself (its head or its journal) or to the deck's upstream (the same rule, for the upstream's head and journal).
     */
    record Revision(long sequence, UUID descriptorRootId, boolean blocked, boolean visible) { }

    /** A (key, revision) pair, the key of a map of revisions. */
    record Ref(UUID key, UUID revisionId) { }

    /** A head write: move {@code key} to {@code revisionId} (and, for an exercise, to {@code ordinal}). */
    record HeadWrite(UUID key, UUID revisionId, int ordinal) { }

    /** One row of the item journal. */
    record ItemChange(int ordinal, UUID member, String kind, UUID previous, UUID revision, Integer from, Integer to) { }

    /** One row of the exercise journal. */
    record ExerciseChange(UUID exercise, UUID previous, UUID revision, int ordinal) { }

    /** The header of a stored command (the metadata and the final writes as JSON text). */
    record Header(UUID commandId, UUID deckId, UUID actorId, UUID expectedHeadRevisionId, byte[] digest, int unitCount, int manifestUnitCount,
                  String metadata) { }

    // ---- deck ----

    Optional<DeckState> deck(UUID actor, UUID deck) {
        return jdbc.sql("""
                SELECT d.deck_id,d.owner_id,d.reuse_scope_id,d.head_revision_id,d.row_version,r.title,r.description,
                       r.members_root_id,r.exercises_root_id,r.member_count,r.exercise_count,
                       f.upstream_deck_id,f.materialized_at IS NOT NULL AS materialized,f.detached_at IS NOT NULL AS detached
                  FROM app_learning.deck d JOIN app_learning.deck_revision r ON r.deck_id=d.deck_id AND r.revision_id=d.head_revision_id
                  LEFT JOIN app_learning.deck_fork f ON f.deck_id=d.deck_id AND f.released_at IS NULL
                 WHERE d.owner_id=:actor AND d.deck_id=:deck AND d.deleted_at IS NULL
                """).param("actor", actor).param("deck", deck)
                .query((row, ignored) -> new DeckState(row.getObject("deck_id", UUID.class), row.getObject("owner_id", UUID.class),
                        row.getObject("reuse_scope_id", UUID.class), row.getObject("head_revision_id", UUID.class), row.getLong("row_version"),
                        row.getString("title"), row.getString("description"), row.getObject("members_root_id", UUID.class),
                        row.getObject("exercises_root_id", UUID.class), row.getInt("member_count"), row.getInt("exercise_count"),
                        row.getObject("upstream_deck_id", UUID.class) == null ? null
                                : new Fork(row.getObject("upstream_deck_id", UUID.class), row.getBoolean("materialized"), row.getBoolean("detached")))).optional();
    }

    Instant now() { return jdbc.sql("SELECT statement_timestamp()").query(Timestamp.class).single().toInstant(); }

    int advance(UUID actor, UUID deck, UUID revision, long expected) {
        return jdbc.sql("""
                UPDATE app_learning.deck SET head_revision_id=:revision,row_version=row_version+1
                 WHERE owner_id=:actor AND deck_id=:deck AND row_version=:expected AND deleted_at IS NULL
                """).param("revision", revision).param("actor", actor).param("deck", deck).param("expected", expected).update();
    }

    void insertDeckRevision(DeckState previous, UUID revision, UUID command, Instant time, String title, String description,
                            UUID membersRoot, UUID membersPin, int memberCount, UUID exercisesRoot, UUID exercisesPin, int exerciseCount) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_revision(deck_id,revision_id,reuse_scope_id,owner_id,sequence,
                    parent_revision_id,parent_sequence,command_id,title,description,created_at,members_root_id,
                    exercises_root_id,members_pin_id,exercises_pin_id,member_count,exercise_count)
                VALUES (:deck,:revision,:scope,:actor,:sequence,:parent,:parentSequence,:command,:title,:description,
                    :time,:members,:exercises,:membersPin,:exercisesPin,:memberCount,:exerciseCount)
                """).param("deck", previous.deckId()).param("revision", revision).param("scope", previous.scopeId())
                .param("actor", previous.ownerId()).param("sequence", previous.version() + 1)
                .param("parent", previous.revisionId()).param("parentSequence", previous.version())
                .param("command", command).param("title", title).param("description", description)
                .param("time", Timestamp.from(time)).param("members", membersRoot).param("exercises", exercisesRoot)
                .param("membersPin", membersPin).param("exercisesPin", exercisesPin)
                .param("memberCount", memberCount).param("exerciseCount", exerciseCount).update();
    }

    // ---- reads: heads and the lineage revisions they move to ----

    Map<UUID, Head> itemHeads(UUID deck, Collection<UUID> members) {
        Map<UUID, Head> result = new HashMap<>();
        for (List<UUID> chunk : chunks(members)) {
            jdbc.sql("SELECT member_key,revision_id,item_sequence FROM app_learning.deck_head_item "
                            + "WHERE deck_id=:deck AND member_key=ANY(CAST(CAST(:keys AS text[]) AS uuid[]))")
                    .param("deck", deck).param("keys", text(chunk))
                    .query((row, ignored) -> result.put(row.getObject("member_key", UUID.class),
                            new Head(row.getObject("revision_id", UUID.class), row.getLong("item_sequence")))).list();
        }
        return result;
    }

    Map<UUID, Head> exerciseHeads(UUID deck, Collection<UUID> exercises) {
        Map<UUID, Head> result = new HashMap<>();
        for (List<UUID> chunk : chunks(exercises)) {
            jdbc.sql("SELECT exercise_id,revision_id,exercise_sequence FROM app_learning.deck_head_exercise "
                            + "WHERE deck_id=:deck AND exercise_id=ANY(CAST(CAST(:keys AS text[]) AS uuid[]))")
                    .param("deck", deck).param("keys", text(chunk))
                    .query((row, ignored) -> result.put(row.getObject("exercise_id", UUID.class),
                            new Head(row.getObject("revision_id", UUID.class), row.getLong("exercise_sequence")))).list();
        }
        return result;
    }

    Map<UUID, Head> objectiveHeads(UUID deck, Collection<UUID> objectives) {
        Map<UUID, Head> result = new HashMap<>();
        for (List<UUID> chunk : chunks(objectives)) {
            jdbc.sql("SELECT objective_id,revision_id,objective_sequence FROM app_learning.objective_head "
                            + "WHERE deck_id=:deck AND objective_id=ANY(CAST(CAST(:keys AS text[]) AS uuid[]))")
                    .param("deck", deck).param("keys", text(chunk))
                    .query((row, ignored) -> result.put(row.getObject("objective_id", UUID.class),
                            new Head(row.getObject("revision_id", UUID.class), row.getLong("objective_sequence")))).list();
        }
        return result;
    }

    Map<Ref, Revision> itemRevisions(DeckState deck, Collection<Ref> refs) {
        Map<Ref, Revision> result = new HashMap<>();
        for (List<Ref> chunk : chunks(refs)) {
            // the two visibilities are separate columns: each is a correlated probe of one deck's head and journal, never an OR of EXISTS over a whole journal
            jdbc.sql("SELECT r.member_key,r.revision_id,r.item_sequence,r.descriptor_root_id," + BLOCKED_ITEM + " AS blocked,"
                            + ItemRevisionVisibility.visibleTo(":deck", "r") + " AS own," + ItemRevisionVisibility.visibleTo(":upstream", "r") + " AS upstream "
                            + "FROM unnest(CAST(:keys AS text[]),CAST(:revisions AS text[])) AS v(member_key,revision_id) "
                            + "JOIN app_learning.item_revision r ON r.reuse_scope_id=:scope AND r.member_key=CAST(v.member_key AS uuid) "
                            + "AND r.revision_id=CAST(v.revision_id AS uuid)")
                    .param("scope", deck.scopeId()).param("deck", deck.deckId()).param("upstream", upstream(deck))
                    .param("keys", text(chunk.stream().map(Ref::key).toList()))
                    .param("revisions", text(chunk.stream().map(Ref::revisionId).toList()))
                    .query((row, ignored) -> result.put(new Ref(row.getObject("member_key", UUID.class), row.getObject("revision_id", UUID.class)),
                            new Revision(row.getLong("item_sequence"), row.getObject("descriptor_root_id", UUID.class), row.getBoolean("blocked"),
                                    row.getBoolean("own") || row.getBoolean("upstream")))).list();
        }
        return result;
    }

    Map<Ref, Revision> exerciseRevisions(DeckState deck, Collection<Ref> refs) {
        Map<Ref, Revision> result = new HashMap<>();
        for (List<Ref> chunk : chunks(refs)) {
            jdbc.sql("SELECT r.exercise_id,r.revision_id,r.exercise_sequence,r.descriptor_root_id," + BLOCKED_EXERCISE + " AS blocked,"
                            + ExerciseRevisionVisibility.visibleTo(":deck", "r") + " AS own," + ExerciseRevisionVisibility.visibleTo(":upstream", "r") + " AS upstream "
                            + "FROM unnest(CAST(:keys AS text[]),CAST(:revisions AS text[])) AS v(exercise_id,revision_id) "
                            + "JOIN app_learning.exercise_revision r ON r.reuse_scope_id=:scope AND r.exercise_id=CAST(v.exercise_id AS uuid) "
                            + "AND r.revision_id=CAST(v.revision_id AS uuid)")
                    .param("scope", deck.scopeId()).param("deck", deck.deckId()).param("upstream", upstream(deck))
                    .param("keys", text(chunk.stream().map(Ref::key).toList()))
                    .param("revisions", text(chunk.stream().map(Ref::revisionId).toList()))
                    .query((row, ignored) -> result.put(new Ref(row.getObject("exercise_id", UUID.class), row.getObject("revision_id", UUID.class)),
                            new Revision(row.getLong("exercise_sequence"), row.getObject("descriptor_root_id", UUID.class), row.getBoolean("blocked"),
                                    row.getBoolean("own") || row.getBoolean("upstream")))).list();
        }
        return result;
    }

    /**
     * The objective revisions that exist in the lineage. An objective revision has no visibility rule of its own (it is never read by its id), so it is acceptable to the
     * deck when one of the two decks WROTE it ({@code deck_id}, the origin), or when an ASSESSED binding of an exercise revision that is visible to the deck or to its
     * upstream pins it (the exercise rule of {@link ExerciseRevisionVisibility}).
     */
    Map<Ref, Revision> objectiveRevisions(DeckState deck, Collection<Ref> refs) {
        Map<Ref, Revision> result = new HashMap<>();
        for (List<Ref> chunk : chunks(refs)) {
            jdbc.sql("SELECT r.objective_id,r.revision_id,r.objective_sequence,"
                            + "(r.deck_id=:deck OR r.deck_id=:upstream) AS written,"
                            + pinnedBy(":deck") + " AS own," + pinnedBy(":upstream") + " AS upstream "
                            + "FROM unnest(CAST(:keys AS text[]),CAST(:revisions AS text[])) AS v(objective_id,revision_id) "
                            + "JOIN app_learning.objective_revision r ON r.reuse_scope_id=:scope AND r.objective_id=CAST(v.objective_id AS uuid) "
                            + "AND r.revision_id=CAST(v.revision_id AS uuid)")
                    .param("scope", deck.scopeId()).param("deck", deck.deckId()).param("upstream", upstream(deck))
                    .param("keys", text(chunk.stream().map(Ref::key).toList()))
                    .param("revisions", text(chunk.stream().map(Ref::revisionId).toList()))
                    .query((row, ignored) -> result.put(new Ref(row.getObject("objective_id", UUID.class), row.getObject("revision_id", UUID.class)),
                            new Revision(row.getLong("objective_sequence"), null, false,
                                    row.getBoolean("written") || row.getBoolean("own") || row.getBoolean("upstream")))).list();
        }
        return result;
    }

    private static String pinnedBy(String deckParameter) {
        return "EXISTS (SELECT 1 FROM app_learning.exercise_content_binding b JOIN app_learning.exercise_revision x ON x.reuse_scope_id=b.reuse_scope_id "
                + "AND x.exercise_id=b.exercise_id AND x.revision_id=b.exercise_revision_id WHERE b.reuse_scope_id=r.reuse_scope_id AND b.objective_id=r.objective_id "
                + "AND b.objective_revision_id=r.revision_id AND b.role='ASSESSED' AND " + ExerciseRevisionVisibility.visibleTo(deckParameter, "x") + ")";
    }

    private static UUID upstream(DeckState deck) {
        if (deck.fork() == null) throw new IllegalStateException("Only a copy adopts revisions");
        return deck.fork().upstreamDeckId();
    }

    /** How many of the objects exist and are sealed (the staged roots of a job's cursor: a page that is gone is never read, which would doom the transaction). */
    int sealedObjects(UUID scope, Collection<UUID> objects) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.storage_object WHERE reuse_scope_id=:scope AND sealed "
                        + "AND object_id=ANY(CAST(CAST(:objects AS text[]) AS uuid[]))")
                .param("scope", scope).param("objects", text(objects)).query(Integer.class).single();
    }

    // ---- writes: item heads, exemplars and the item journal ----

    /** Moves existing item heads; returns how many rows moved. */
    int updateItemHeads(UUID deck, UUID scope, List<HeadWrite> writes, Instant time) {
        int total = 0;
        for (List<HeadWrite> chunk : chunks(writes)) {
            total += jdbc.sql("""
                    UPDATE app_learning.deck_head_item h SET revision_id=r.revision_id,item_sequence=r.item_sequence,updated_at=:time
                      FROM unnest(CAST(:keys AS text[]),CAST(:revisions AS text[])) AS v(member_key,revision_id)
                      JOIN app_learning.item_revision r ON r.reuse_scope_id=:scope AND r.member_key=CAST(v.member_key AS uuid)
                       AND r.revision_id=CAST(v.revision_id AS uuid)
                     WHERE h.deck_id=:deck AND h.member_key=r.member_key
                    """).param("deck", deck).param("scope", scope).param("time", Timestamp.from(time))
                    .param("keys", text(chunk.stream().map(HeadWrite::key).toList()))
                    .param("revisions", text(chunk.stream().map(HeadWrite::revisionId).toList())).update();
        }
        return total;
    }

    int insertItemHeads(UUID deck, UUID scope, List<HeadWrite> writes, Instant time) {
        int total = 0;
        for (List<HeadWrite> chunk : chunks(writes)) {
            total += jdbc.sql("""
                    INSERT INTO app_learning.deck_head_item(deck_id,reuse_scope_id,member_key,revision_id,item_sequence,updated_at)
                    SELECT :deck,:scope,r.member_key,r.revision_id,r.item_sequence,:time
                      FROM unnest(CAST(:keys AS text[]),CAST(:revisions AS text[])) AS v(member_key,revision_id)
                      JOIN app_learning.item_revision r ON r.reuse_scope_id=:scope AND r.member_key=CAST(v.member_key AS uuid)
                       AND r.revision_id=CAST(v.revision_id AS uuid)
                    """).param("deck", deck).param("scope", scope).param("time", Timestamp.from(time))
                    .param("keys", text(chunk.stream().map(HeadWrite::key).toList()))
                    .param("revisions", text(chunk.stream().map(HeadWrite::revisionId).toList())).update();
        }
        return total;
    }

    /** Deletes the heads and the «Эталон» marks of the members (what the item delete path removes with a head). */
    int deleteItemHeads(UUID deck, List<UUID> members) {
        int total = 0;
        for (List<UUID> chunk : chunks(members)) {
            jdbc.sql("DELETE FROM app_learning.deck_item_exemplar WHERE deck_id=:deck AND member_key=ANY(CAST(CAST(:keys AS text[]) AS uuid[]))")
                    .param("deck", deck).param("keys", text(chunk)).update();
            total += jdbc.sql("DELETE FROM app_learning.deck_head_item WHERE deck_id=:deck AND member_key=ANY(CAST(CAST(:keys AS text[]) AS uuid[]))")
                    .param("deck", deck).param("keys", text(chunk)).update();
        }
        return total;
    }

    void insertItemChanges(UUID deck, UUID scope, UUID deckRevision, long deckSequence, List<ItemChange> changes) {
        for (List<ItemChange> chunk : chunks(changes)) {
            jdbc.sql("""
                    INSERT INTO app_learning.deck_item_change(deck_id,reuse_scope_id,deck_revision_id,deck_sequence,change_ordinal,member_key,
                        change_kind,previous_revision_id,revision_id,from_ordinal,to_ordinal)
                    SELECT :deck,:scope,:deckRevision,:deckSequence,v.ordinal,CAST(v.member AS uuid),v.kind,CAST(v.previous AS uuid),CAST(v.revision AS uuid),v.from_ordinal,v.to_ordinal
                      FROM unnest(CAST(:ordinals AS integer[]),CAST(:members AS text[]),CAST(:kinds AS text[]),CAST(:previous AS text[]),
                                  CAST(:revisions AS text[]),CAST(:froms AS integer[]),CAST(:tos AS integer[]))
                           AS v(ordinal,member,kind,previous,revision,from_ordinal,to_ordinal)
                    """).param("deck", deck).param("scope", scope).param("deckRevision", deckRevision).param("deckSequence", deckSequence)
                    .param("ordinals", chunk.stream().map(ItemChange::ordinal).toArray(Integer[]::new))
                    .param("members", text(chunk.stream().map(ItemChange::member).toList()))
                    .param("kinds", chunk.stream().map(ItemChange::kind).toArray(String[]::new))
                    .param("previous", text(chunk.stream().map(ItemChange::previous).toList()))
                    .param("revisions", text(chunk.stream().map(ItemChange::revision).toList()))
                    .param("froms", chunk.stream().map(ItemChange::from).toArray(Integer[]::new))
                    .param("tos", chunk.stream().map(ItemChange::to).toArray(Integer[]::new)).update();
        }
    }

    // ---- writes: exercise heads and the exercise journal ----

    int updateExerciseHeads(UUID deck, UUID scope, List<HeadWrite> writes, Instant time) {
        int total = 0;
        for (List<HeadWrite> chunk : chunks(writes)) {
            total += jdbc.sql("""
                    UPDATE app_learning.deck_head_exercise h SET revision_id=r.revision_id,exercise_sequence=r.exercise_sequence,ordinal=v.ordinal,updated_at=:time
                      FROM unnest(CAST(:keys AS text[]),CAST(:revisions AS text[]),CAST(:ordinals AS integer[])) AS v(exercise_id,revision_id,ordinal)
                      JOIN app_learning.exercise_revision r ON r.reuse_scope_id=:scope AND r.exercise_id=CAST(v.exercise_id AS uuid)
                       AND r.revision_id=CAST(v.revision_id AS uuid)
                     WHERE h.deck_id=:deck AND h.exercise_id=r.exercise_id
                    """).param("deck", deck).param("scope", scope).param("time", Timestamp.from(time))
                    .param("keys", text(chunk.stream().map(HeadWrite::key).toList()))
                    .param("revisions", text(chunk.stream().map(HeadWrite::revisionId).toList()))
                    .param("ordinals", chunk.stream().map(HeadWrite::ordinal).toArray(Integer[]::new)).update();
        }
        return total;
    }

    int insertExerciseHeads(UUID deck, UUID scope, List<HeadWrite> writes, Instant time) {
        int total = 0;
        for (List<HeadWrite> chunk : chunks(writes)) {
            total += jdbc.sql("""
                    INSERT INTO app_learning.deck_head_exercise(deck_id,reuse_scope_id,exercise_id,revision_id,exercise_sequence,ordinal,updated_at)
                    SELECT :deck,:scope,r.exercise_id,r.revision_id,r.exercise_sequence,v.ordinal,:time
                      FROM unnest(CAST(:keys AS text[]),CAST(:revisions AS text[]),CAST(:ordinals AS integer[])) AS v(exercise_id,revision_id,ordinal)
                      JOIN app_learning.exercise_revision r ON r.reuse_scope_id=:scope AND r.exercise_id=CAST(v.exercise_id AS uuid)
                       AND r.revision_id=CAST(v.revision_id AS uuid)
                    """).param("deck", deck).param("scope", scope).param("time", Timestamp.from(time))
                    .param("keys", text(chunk.stream().map(HeadWrite::key).toList()))
                    .param("revisions", text(chunk.stream().map(HeadWrite::revisionId).toList()))
                    .param("ordinals", chunk.stream().map(HeadWrite::ordinal).toArray(Integer[]::new)).update();
        }
        return total;
    }

    int deleteExerciseHeads(UUID deck, List<UUID> exercises) {
        int total = 0;
        for (List<UUID> chunk : chunks(exercises)) {
            total += jdbc.sql("DELETE FROM app_learning.deck_head_exercise WHERE deck_id=:deck AND exercise_id=ANY(CAST(CAST(:keys AS text[]) AS uuid[]))")
                    .param("deck", deck).param("keys", text(chunk)).update();
        }
        return total;
    }

    /**
     * Gives the heads the command did not touch the ordinals the manifest gives them: they keep their relative order and take, in that order, the positions that the touched
     * exercises do not occupy ({@code taken}, 0..{@code count}-1 in all). One statement, O(exercises of the deck) once per command instead of one shift per operation; the
     * database refuses a duplicate at commit (the unique ordinal is deferred) and {@link #exerciseOrdinals} proves the result is exactly 0..count-1.
     */
    int reassignExerciseOrdinals(UUID deck, Collection<UUID> touched, Collection<Integer> taken, int count) {
        return jdbc.sql("""
                UPDATE app_learning.deck_head_exercise h SET ordinal=f.slot
                  FROM (SELECT u.exercise_id,s.slot
                          FROM (SELECT head.exercise_id,row_number() OVER (ORDER BY head.ordinal)-1 AS rn
                                  FROM app_learning.deck_head_exercise head
                                  LEFT JOIN unnest(CAST(:touched AS text[])) AS t(exercise_id) ON CAST(t.exercise_id AS uuid)=head.exercise_id
                                 WHERE head.deck_id=:deck AND t.exercise_id IS NULL) u
                          JOIN (SELECT series.slot,row_number() OVER (ORDER BY series.slot)-1 AS rn
                                  FROM generate_series(0,:last) AS series(slot)
                                  LEFT JOIN unnest(CAST(:taken AS integer[])) AS t(slot) ON t.slot=series.slot
                                 WHERE t.slot IS NULL) s ON s.rn=u.rn) f
                 WHERE h.deck_id=:deck AND h.exercise_id=f.exercise_id AND h.ordinal<>f.slot
                """).param("deck", deck).param("touched", text(touched)).param("taken", taken.toArray(Integer[]::new)).param("last", count - 1).update();
    }

    /** (count, min, max) of the deck's exercise ordinals. */
    record Ordinals(int count, int min, int max) { }

    Ordinals exerciseOrdinals(UUID deck) {
        return jdbc.sql("SELECT count(*)::integer AS n,COALESCE(min(ordinal),0) AS lo,COALESCE(max(ordinal),-1) AS hi "
                        + "FROM app_learning.deck_head_exercise WHERE deck_id=:deck").param("deck", deck)
                .query((row, ignored) -> new Ordinals(row.getInt("n"), row.getInt("lo"), row.getInt("hi"))).single();
    }

    int itemHeadCount(UUID deck) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.deck_head_item WHERE deck_id=:deck").param("deck", deck).query(Integer.class).single();
    }

    void insertExerciseChanges(UUID deck, UUID scope, UUID deckRevision, long deckSequence, List<ExerciseChange> changes) {
        for (List<ExerciseChange> chunk : chunks(changes)) {
            jdbc.sql("""
                    INSERT INTO app_learning.deck_exercise_change(deck_id,reuse_scope_id,deck_revision_id,deck_sequence,exercise_id,
                        previous_revision_id,revision_id,ordinal)
                    SELECT :deck,:scope,:deckRevision,:deckSequence,CAST(v.exercise AS uuid),CAST(v.previous AS uuid),CAST(v.revision AS uuid),v.ordinal
                      FROM unnest(CAST(:exercises AS text[]),CAST(:previous AS text[]),CAST(:revisions AS text[]),CAST(:ordinals AS integer[]))
                           AS v(exercise,previous,revision,ordinal)
                    """).param("deck", deck).param("scope", scope).param("deckRevision", deckRevision).param("deckSequence", deckSequence)
                    .param("exercises", text(chunk.stream().map(ExerciseChange::exercise).toList()))
                    .param("previous", text(chunk.stream().map(ExerciseChange::previous).toList()))
                    .param("revisions", text(chunk.stream().map(ExerciseChange::revision).toList()))
                    .param("ordinals", chunk.stream().map(ExerciseChange::ordinal).toArray(Integer[]::new)).update();
        }
    }

    // ---- writes: objective heads ----

    /** Moves or inserts the objective heads; returns how many rows were written (one per objective). */
    int upsertObjectiveHeads(UUID deck, UUID scope, List<HeadWrite> writes, Instant time) {
        int total = 0;
        for (List<HeadWrite> chunk : chunks(writes)) {
            total += jdbc.sql("""
                    INSERT INTO app_learning.objective_head(deck_id,reuse_scope_id,objective_id,revision_id,objective_sequence,updated_at)
                    SELECT :deck,:scope,r.objective_id,r.revision_id,r.objective_sequence,:time
                      FROM unnest(CAST(:keys AS text[]),CAST(:revisions AS text[])) AS v(objective_id,revision_id)
                      JOIN app_learning.objective_revision r ON r.reuse_scope_id=:scope AND r.objective_id=CAST(v.objective_id AS uuid)
                       AND r.revision_id=CAST(v.revision_id AS uuid)
                    ON CONFLICT (deck_id,objective_id) DO UPDATE
                       SET revision_id=EXCLUDED.revision_id,objective_sequence=EXCLUDED.objective_sequence,updated_at=EXCLUDED.updated_at
                    """).param("deck", deck).param("scope", scope).param("time", Timestamp.from(time))
                    .param("keys", text(chunk.stream().map(HeadWrite::key).toList()))
                    .param("revisions", text(chunk.stream().map(HeadWrite::revisionId).toList())).update();
        }
        return total;
    }

    // ---- consistency checks of the result ----

    /** Bindings of the adopted exercise revisions that name a material the deck does not have: a presentation would quote a non-member. */
    int bindingsOutsideTheHeads(UUID deck, UUID scope, Collection<Ref> adopted) {
        int total = 0;
        for (List<Ref> chunk : chunks(adopted)) {
            total += jdbc.sql("""
                    SELECT count(*)::integer
                      FROM unnest(CAST(:exercises AS text[]),CAST(:revisions AS text[])) AS v(exercise_id,revision_id)
                      JOIN app_learning.exercise_content_binding b ON b.reuse_scope_id=:scope AND b.exercise_id=CAST(v.exercise_id AS uuid)
                       AND b.exercise_revision_id=CAST(v.revision_id AS uuid)
                     WHERE NOT EXISTS (SELECT 1 FROM app_learning.deck_head_item h WHERE h.deck_id=:deck AND h.member_key=b.member_key)
                    """).param("deck", deck).param("scope", scope).param("exercises", text(chunk.stream().map(Ref::key).toList()))
                    .param("revisions", text(chunk.stream().map(Ref::revisionId).toList())).query(Integer.class).single();
        }
        return total;
    }

    /**
     * Objectives (of the given ones) whose head is not what the materializer would write: the NEWEST revision pinned by an ASSESSED binding of the deck's head exercises
     * ({@code MaterializationRepository}, "the revision the source's own head would hold"). An objective that no head exercise pins has no expectation and is not counted.
     */
    int objectivesOffTheRule(UUID deck, Collection<UUID> objectives) {
        int total = 0;
        for (List<UUID> chunk : chunks(objectives)) {
            total += jdbc.sql("""
                    SELECT count(*)::integer
                      FROM (SELECT DISTINCT ON (b.objective_id) b.objective_id,b.objective_revision_id
                              FROM unnest(CAST(:objectives AS text[])) AS t(objective_id)
                              JOIN app_learning.exercise_content_binding b ON b.objective_id=CAST(t.objective_id AS uuid) AND b.role='ASSESSED'
                              JOIN app_learning.deck_head_exercise h ON h.deck_id=:deck AND h.reuse_scope_id=b.reuse_scope_id AND h.exercise_id=b.exercise_id
                               AND h.revision_id=b.exercise_revision_id
                              JOIN app_learning.objective_revision o ON o.reuse_scope_id=b.reuse_scope_id AND o.objective_id=b.objective_id AND o.revision_id=b.objective_revision_id
                             ORDER BY b.objective_id,o.objective_sequence DESC) expected
                      LEFT JOIN app_learning.objective_head head ON head.deck_id=:deck AND head.objective_id=expected.objective_id
                     WHERE head.revision_id IS DISTINCT FROM expected.objective_revision_id
                    """).param("deck", deck).param("objectives", text(chunk)).query(Integer.class).single();
        }
        return total;
    }

    /** The objectives the ASSESSED bindings of the given exercise revisions pin. */
    Set<UUID> assessedObjectivesOf(UUID scope, Collection<Ref> exercises) {
        Set<UUID> result = new java.util.LinkedHashSet<>();
        for (List<Ref> chunk : chunks(exercises)) {
            jdbc.sql("""
                    SELECT DISTINCT b.objective_id
                      FROM unnest(CAST(:exercises AS text[]),CAST(:revisions AS text[])) AS v(exercise_id,revision_id)
                      JOIN app_learning.exercise_content_binding b ON b.reuse_scope_id=:scope AND b.exercise_id=CAST(v.exercise_id AS uuid)
                       AND b.exercise_revision_id=CAST(v.revision_id AS uuid) AND b.role='ASSESSED'
                    """).param("scope", scope).param("exercises", text(chunk.stream().map(Ref::key).toList()))
                    .param("revisions", text(chunk.stream().map(Ref::revisionId).toList()))
                    .query((row, ignored) -> result.add(row.getObject(1, UUID.class))).list();
        }
        return result;
    }

    /** Bindings (any role) of the deck's HEAD exercises that name one of the removed materials: removing it would leave an exercise quoting a non-member. */
    int headExercisesBoundTo(UUID deck, UUID scope, Collection<UUID> removed) {
        int total = 0;
        for (List<UUID> chunk : chunks(removed)) {
            total += jdbc.sql("""
                    SELECT count(*)::integer
                      FROM unnest(CAST(:members AS text[])) AS v(member_key)
                      JOIN app_learning.exercise_content_binding b ON b.reuse_scope_id=:scope AND b.member_key=CAST(v.member_key AS uuid)
                      JOIN app_learning.deck_head_exercise h ON h.deck_id=:deck AND h.reuse_scope_id=b.reuse_scope_id AND h.exercise_id=b.exercise_id
                       AND h.revision_id=b.exercise_revision_id
                    """).param("deck", deck).param("scope", scope).param("members", text(chunk)).query(Integer.class).single();
        }
        return total;
    }

    /**
     * Deletes the stored commands of up to {@code limit} orphans, oldest first: no live ({@code QUEUED} or {@code RUNNING}) job holds the command id and the command is older than
     * a minute. A cancelled queued job (cancelled by {@code LearningJobs.cancel}, which runs no hook), a job purged by retention and a lost enqueue race end up here.
     */
    int purgeOrphans(int limit) {
        return jdbc.sql("""
                DELETE FROM app_learning.combined_publication c WHERE c.command_id IN (
                    SELECT p.command_id FROM app_learning.combined_publication p
                     WHERE p.created_at < statement_timestamp() - interval '1 minute'
                       AND NOT EXISTS (SELECT 1 FROM app_learning.learning_job j WHERE j.queue=:queue AND j.dedupe_key=p.command_id::text AND j.state IN ('QUEUED','RUNNING'))
                     ORDER BY p.created_at LIMIT :limit FOR UPDATE OF p SKIP LOCKED)
                """).param("queue", CombinedPublication.QUEUE).param("limit", limit).update();
    }

    // ---- the stored command of a job ----

    /** Writes the header; false when the command is already stored (an enqueue of the same command id). */
    boolean insertHeader(CombinedUnits units, byte[] digest, int manifestUnits, String finalWritesText) {
        return jdbc.sql("""
                INSERT INTO app_learning.combined_publication(command_id,deck_id,actor_id,expected_head_revision_id,digest,unit_count,manifest_unit_count,metadata,final_writes)
                VALUES (:command,:deck,:actor,:expected,:digest,:units,:manifest,CAST(:metadata AS jsonb),:finalWrites)
                ON CONFLICT (command_id) DO NOTHING
                """).param("command", units.commandId()).param("deck", units.deckId()).param("actor", units.actorId())
                .param("expected", units.expectedHeadRevisionId()).param("digest", digest).param("units", units.unitCount())
                .param("manifest", manifestUnits)
                .param("metadata", units.metadataJson() == null ? null : units.metadataJson().toString(), java.sql.Types.VARCHAR)
                .param("finalWrites", finalWritesText, java.sql.Types.VARCHAR).update() == 1;
    }

    void insertUnits(UUID command, List<CombinedUnits.Row> rows) {
        for (List<CombinedUnits.Row> chunk : chunks(rows)) {
            jdbc.sql("""
                    INSERT INTO app_learning.combined_publication_unit(command_id,unit_ordinal,kind,key_id,revision_id,ordinal,to_ordinal)
                    SELECT :command,v.unit,v.kind,CAST(v.key AS uuid),CAST(v.revision AS uuid),v.ordinal,v.to_ordinal
                      FROM unnest(CAST(:units AS integer[]),CAST(:kinds AS text[]),CAST(:keys AS text[]),CAST(:revisions AS text[]),
                                  CAST(:ordinals AS integer[]),CAST(:tos AS integer[])) AS v(unit,kind,key,revision,ordinal,to_ordinal)
                    """).param("command", command)
                    .param("units", chunk.stream().map(CombinedUnits.Row::unit).toArray(Integer[]::new))
                    .param("kinds", chunk.stream().map(CombinedUnits.Row::kind).toArray(String[]::new))
                    .param("keys", text(chunk.stream().map(CombinedUnits.Row::key).toList()))
                    .param("revisions", text(chunk.stream().map(CombinedUnits.Row::revisionId).toList()))
                    .param("ordinals", chunk.stream().map(CombinedUnits.Row::ordinal).toArray(Integer[]::new))
                    .param("tos", chunk.stream().map(CombinedUnits.Row::toOrdinal).toArray(Integer[]::new)).update();
        }
    }

    Optional<Header> header(UUID command) {
        return jdbc.sql("""
                SELECT command_id,deck_id,actor_id,expected_head_revision_id,digest,unit_count,manifest_unit_count,metadata::text AS metadata
                  FROM app_learning.combined_publication WHERE command_id=:command
                """).param("command", command)
                .query((row, ignored) -> new Header(row.getObject("command_id", UUID.class), row.getObject("deck_id", UUID.class),
                        row.getObject("actor_id", UUID.class), row.getObject("expected_head_revision_id", UUID.class), row.getBytes("digest"),
                        row.getInt("unit_count"), row.getInt("manifest_unit_count"), row.getString("metadata"))).optional();
    }

    /**
     * The final writes of a stored command, as the caller's text. Read ONLY by the last slice ({@code restore}): they can be megabytes (the decisions of an apply command), and the
     * header every slice reads does not carry them.
     */
    Optional<String> finalWrites(UUID command) {
        return jdbc.sql("SELECT final_writes FROM app_learning.combined_publication WHERE command_id=:command").param("command", command)
                .query((row, ignored) -> row.getString("final_writes")).optional().filter(text -> text != null);
    }

    /** The stored operations {@code [from, to)} in unit order. */
    List<CombinedUnits.Row> units(UUID command, int from, int to) {
        return jdbc.sql("""
                SELECT unit_ordinal,kind,key_id,revision_id,ordinal,to_ordinal FROM app_learning.combined_publication_unit
                 WHERE command_id=:command AND unit_ordinal>=:from AND unit_ordinal<:to ORDER BY unit_ordinal
                """).param("command", command).param("from", from).param("to", to)
                .query((row, ignored) -> new CombinedUnits.Row(row.getInt("unit_ordinal"), row.getString("kind"), row.getObject("key_id", UUID.class),
                        row.getObject("revision_id", UUID.class), row.getObject("ordinal", Integer.class), row.getObject("to_ordinal", Integer.class))).list();
    }

    /** Deletes the stored command and, by cascade, its operations. */
    void deleteCommand(UUID command) {
        jdbc.sql("DELETE FROM app_learning.combined_publication WHERE command_id=:command").param("command", command).update();
    }

    // ---- helpers ----

    static String[] text(Collection<UUID> values) {
        String[] result = new String[values.size()];
        int index = 0;
        for (UUID value : values) result[index++] = value == null ? null : value.toString();
        return result;
    }

    private static <T> List<List<T>> chunks(Collection<T> values) {
        List<T> all = values instanceof List<T> list ? list : new ArrayList<>(values);
        List<List<T>> result = new ArrayList<>();
        for (int at = 0; at < all.size(); at += CHUNK) result.add(all.subList(at, Math.min(all.size(), at + CHUNK)));
        return result;
    }
}
