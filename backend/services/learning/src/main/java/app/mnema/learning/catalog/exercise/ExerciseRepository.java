package app.mnema.learning.catalog.exercise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class ExerciseRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcClient jdbc;

    ExerciseRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    record DeckHead(UUID deckId, UUID ownerId, UUID scopeId, UUID revisionId, long version, String title,
                    String description, UUID membersRootId, UUID exercisesRootId, int memberCount,
                    int exerciseCount) { }
    record ObjectiveRow(UUID objectiveId, UUID objectiveKey, UUID memberKey, UUID revisionId, long sequence,
                        JsonNode answerContract, Instant createdAt, Instant updatedAt) { }
    record ExerciseRow(UUID exerciseId, UUID revisionId, long sequence, int ordinal, String type, boolean enabled,
                       JsonNode prompt, JsonNode evaluator, UUID descriptorRootId, Instant createdAt, Instant updatedAt) { }
    record BindingRow(UUID bindingId, int ordinal, String role, UUID memberKey, UUID itemRevisionId,
                      UUID objectiveId, UUID objectiveRevisionId, List<UUID> nodeIds, JsonNode display) { }
    record ItemRevision(UUID memberKey, UUID revisionId, UUID scopeId, UUID contentRootId) { }

    private static final RowMapper<DeckHead> DECK = (row, ignored) -> new DeckHead(
            row.getObject("deck_id", UUID.class), row.getObject("owner_id", UUID.class),
            row.getObject("reuse_scope_id", UUID.class), row.getObject("head_revision_id", UUID.class),
            row.getLong("row_version"), row.getString("title"), row.getString("description"),
            row.getObject("members_root_id", UUID.class), row.getObject("exercises_root_id", UUID.class),
            row.getInt("member_count"), row.getInt("exercise_count"));
    private static final RowMapper<ObjectiveRow> OBJECTIVE = (row, ignored) -> new ObjectiveRow(
            row.getObject("objective_id", UUID.class), row.getObject("objective_key", UUID.class),
            row.getObject("member_key", UUID.class), row.getObject("revision_id", UUID.class),
            row.getLong("objective_sequence"), json(row.getString("answer_contract")),
            row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    private static final RowMapper<ExerciseRow> EXERCISE = (row, ignored) -> new ExerciseRow(
            row.getObject("exercise_id", UUID.class), row.getObject("revision_id", UUID.class),
            row.getLong("exercise_sequence"), row.getInt("ordinal"), row.getString("exercise_type"),
            row.getBoolean("enabled"), json(row.getString("prompt_spec")), json(row.getString("evaluator_policy")),
            row.getObject("descriptor_root_id", UUID.class), row.getTimestamp("created_at").toInstant(),
            row.getTimestamp("updated_at").toInstant());
    private static final RowMapper<BindingRow> BINDING = (row, ignored) -> new BindingRow(
            row.getObject("binding_id", UUID.class), row.getInt("binding_ordinal"), row.getString("role"),
            row.getObject("member_key", UUID.class), row.getObject("item_revision_id", UUID.class),
            row.getObject("objective_id", UUID.class), row.getObject("objective_revision_id", UUID.class),
            uuidArray(row.getArray("node_ids")), json(row.getString("display_spec")));

    Optional<DeckHead> deck(UUID actor, UUID deck) {
        return jdbc.sql("""
                SELECT d.deck_id,d.owner_id,d.reuse_scope_id,d.head_revision_id,d.row_version,
                       r.title,r.description,r.members_root_id,r.exercises_root_id,r.member_count,r.exercise_count
                  FROM app_learning.deck d JOIN app_learning.deck_revision r
                    ON r.deck_id=d.deck_id AND r.revision_id=d.head_revision_id
                 WHERE d.owner_id=:actor AND d.deck_id=:deck
                """).param("actor", actor).param("deck", deck).query(DECK).optional();
    }

    Optional<ObjectiveRow> objectiveHead(UUID actor, UUID deck, UUID objective) {
        return jdbc.sql("""
                SELECT o.objective_id,o.objective_key,o.member_key,r.revision_id,r.objective_sequence,
                       r.answer_contract,o.created_at,h.updated_at
                  FROM app_learning.deck d JOIN app_learning.memory_objective o ON o.deck_id=d.deck_id
                  JOIN app_learning.objective_head h ON h.deck_id=o.deck_id AND h.objective_id=o.objective_id
                  JOIN app_learning.objective_revision r ON r.deck_id=h.deck_id AND r.objective_id=h.objective_id
                    AND r.revision_id=h.revision_id
                 WHERE d.owner_id=:actor AND o.deck_id=:deck AND o.objective_id=:objective
                """).param("actor", actor).param("deck", deck).param("objective", objective)
                .query(OBJECTIVE).optional();
    }

    Optional<ObjectiveRow> objectiveRevision(UUID actor, UUID deck, UUID objective, UUID revision) {
        return jdbc.sql("""
                SELECT o.objective_id,o.objective_key,o.member_key,r.revision_id,r.objective_sequence,
                       r.answer_contract,o.created_at,r.created_at AS updated_at
                  FROM app_learning.deck d JOIN app_learning.memory_objective o ON o.deck_id=d.deck_id
                  JOIN app_learning.objective_revision r ON r.deck_id=o.deck_id AND r.objective_id=o.objective_id
                 WHERE d.owner_id=:actor AND o.deck_id=:deck AND o.objective_id=:objective AND r.revision_id=:revision
                """).param("actor", actor).param("deck", deck).param("objective", objective)
                .param("revision", revision).query(OBJECTIVE).optional();
    }

    Optional<ExerciseRow> exerciseHead(UUID actor, UUID deck, UUID exercise) {
        return exercise(actor, deck, exercise, null);
    }

    Optional<ExerciseRow> exerciseRevision(UUID actor, UUID deck, UUID exercise, UUID revision) {
        return exercise(actor, deck, exercise, revision);
    }

    private Optional<ExerciseRow> exercise(UUID actor, UUID deck, UUID exercise, UUID revision) {
        String revisionJoin = revision == null
                ? "JOIN app_learning.deck_head_exercise h ON h.deck_id=x.deck_id AND h.exercise_id=x.exercise_id "
                + "JOIN app_learning.exercise_revision r ON r.deck_id=h.deck_id AND r.exercise_id=h.exercise_id AND r.revision_id=h.revision_id"
                : "JOIN app_learning.exercise_revision r ON r.deck_id=x.deck_id AND r.exercise_id=x.exercise_id "
                + "LEFT JOIN app_learning.deck_head_exercise h ON h.deck_id=x.deck_id AND h.exercise_id=x.exercise_id";
        String ordinal = revision == null ? "h.ordinal" : "COALESCE((SELECT c.ordinal FROM app_learning.deck_exercise_change c WHERE c.deck_id=x.deck_id AND c.exercise_id=x.exercise_id AND c.revision_id=r.revision_id),h.ordinal)";
        var query = jdbc.sql("""
                SELECT x.exercise_id,r.revision_id,r.exercise_sequence,%s AS ordinal,r.exercise_type,r.enabled,
                       r.prompt_spec,r.evaluator_policy,r.descriptor_root_id,x.created_at,r.created_at AS updated_at
                  FROM app_learning.deck d JOIN app_learning.exercise_definition x ON x.deck_id=d.deck_id
                  %s
                 WHERE d.owner_id=:actor AND x.deck_id=:deck AND x.exercise_id=:exercise
                """.formatted(ordinal, revisionJoin) + (revision == null ? "" : " AND r.revision_id=:revision"))
                .param("actor", actor).param("deck", deck).param("exercise", exercise);
        if (revision != null) query.param("revision", revision);
        return query.query(EXERCISE).optional();
    }

    List<ExerciseRow> page(UUID actor, UUID deck, int start, int limit) {
        return jdbc.sql("""
                SELECT x.exercise_id,r.revision_id,r.exercise_sequence,h.ordinal,r.exercise_type,r.enabled,
                       r.prompt_spec,r.evaluator_policy,r.descriptor_root_id,x.created_at,r.created_at AS updated_at
                  FROM app_learning.deck d JOIN app_learning.deck_head_exercise h ON h.deck_id=d.deck_id
                  JOIN app_learning.exercise_definition x ON x.deck_id=h.deck_id AND x.exercise_id=h.exercise_id
                  JOIN app_learning.exercise_revision r ON r.deck_id=h.deck_id AND r.exercise_id=h.exercise_id
                    AND r.revision_id=h.revision_id
                 WHERE d.owner_id=:actor AND h.deck_id=:deck AND h.ordinal>=:start
                 ORDER BY h.ordinal LIMIT :limit
                """).param("actor", actor).param("deck", deck).param("start", start).param("limit", limit)
                .query(EXERCISE).list();
    }

    List<BindingRow> bindings(UUID deck, UUID exercise, UUID revision) {
        return jdbc.sql("""
                SELECT binding_id,binding_ordinal,role,member_key,item_revision_id,objective_id,
                       objective_revision_id,node_ids,display_spec
                  FROM app_learning.exercise_content_binding
                 WHERE deck_id=:deck AND exercise_id=:exercise AND exercise_revision_id=:revision
                 ORDER BY binding_ordinal
                """).param("deck", deck).param("exercise", exercise).param("revision", revision)
                .query(BINDING).list();
    }

    Optional<ItemRevision> itemRevision(UUID actor, UUID deck, UUID member, UUID revision) {
        return jdbc.sql("""
                SELECT r.member_key,r.revision_id,r.reuse_scope_id,r.content_root_id
                  FROM app_learning.deck d JOIN app_learning.item_revision r ON r.deck_id=d.deck_id
                  JOIN app_learning.deck_head_item h ON h.deck_id=r.deck_id AND h.member_key=r.member_key
                    AND h.revision_id=r.revision_id
                 WHERE d.owner_id=:actor AND r.deck_id=:deck AND r.member_key=:member AND r.revision_id=:revision
                """).param("actor", actor).param("deck", deck).param("member", member).param("revision", revision)
                .query((row, ignored) -> new ItemRevision(row.getObject("member_key", UUID.class),
                        row.getObject("revision_id", UUID.class), row.getObject("reuse_scope_id", UUID.class),
                        row.getObject("content_root_id", UUID.class))).optional();
    }

    Instant now() { return jdbc.sql("SELECT statement_timestamp()").query(Timestamp.class).single().toInstant(); }

    int advance(UUID actor, UUID deck, UUID revision, long expected) {
        return jdbc.sql("""
                UPDATE app_learning.deck SET head_revision_id=:revision,row_version=row_version+1
                 WHERE owner_id=:actor AND deck_id=:deck AND row_version=:expected
                """).param("revision", revision).param("actor", actor).param("deck", deck)
                .param("expected", expected).update();
    }

    void insertDeckRevision(DeckHead previous, UUID revision, UUID command, Instant time, UUID membersPin,
                            UUID exercisesRoot, UUID exercisesPin, int exerciseCount) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_revision(deck_id,revision_id,reuse_scope_id,owner_id,sequence,
                    parent_revision_id,parent_sequence,command_id,title,description,created_at,members_root_id,
                    exercises_root_id,members_pin_id,exercises_pin_id,member_count,exercise_count)
                VALUES (:deck,:revision,:scope,:actor,:sequence,:parent,:parentSequence,:command,:title,:description,
                    :time,:members,:exercises,:membersPin,:exercisesPin,:memberCount,:exerciseCount)
                """).param("deck", previous.deckId()).param("revision", revision).param("scope", previous.scopeId())
                .param("actor", previous.ownerId()).param("sequence", previous.version() + 1)
                .param("parent", previous.revisionId()).param("parentSequence", previous.version())
                .param("command", command).param("title", previous.title()).param("description", previous.description())
                .param("time", Timestamp.from(time)).param("members", previous.membersRootId())
                .param("exercises", exercisesRoot).param("membersPin", membersPin).param("exercisesPin", exercisesPin)
                .param("memberCount", previous.memberCount()).param("exerciseCount", exerciseCount).update();
    }

    void insertObjective(UUID deck, UUID objective, UUID key, UUID member, UUID actor, UUID scope, Instant time) {
        jdbc.sql("""
                INSERT INTO app_learning.memory_objective(deck_id,objective_id,objective_key,member_key,owner_id,
                    reuse_scope_id,created_at) VALUES (:deck,:objective,:key,:member,:actor,:scope,:time)
                """).param("deck", deck).param("objective", objective).param("key", key).param("member", member)
                .param("actor", actor).param("scope", scope).param("time", Timestamp.from(time)).update();
    }

    void insertObjectiveRevision(UUID deck, UUID objective, UUID revision, long sequence, UUID parent,
                                 UUID deckRevision, long deckSequence, UUID command, JsonNode answer, Instant time) {
        jdbc.sql("""
                INSERT INTO app_learning.objective_revision(deck_id,objective_id,revision_id,objective_sequence,
                    parent_revision_id,parent_objective_sequence,deck_revision_id,deck_sequence,command_id,
                    answer_contract,created_at)
                VALUES (:deck,:objective,:revision,:sequence,:parent,:parentSequence,:deckRevision,:deckSequence,
                    :command,CAST(:answer AS jsonb),:time)
                """).param("deck", deck).param("objective", objective).param("revision", revision)
                .param("sequence", sequence).param("parent", parent, java.sql.Types.OTHER)
                .param("parentSequence", parent == null ? null : sequence - 1, java.sql.Types.BIGINT)
                .param("deckRevision", deckRevision).param("deckSequence", deckSequence).param("command", command)
                .param("answer", answer.toString()).param("time", Timestamp.from(time)).update();
    }

    void insertObjectiveHead(UUID deck, UUID objective, UUID revision, long sequence, Instant time) {
        jdbc.sql("""
                INSERT INTO app_learning.objective_head(deck_id,objective_id,revision_id,objective_sequence,updated_at)
                VALUES (:deck,:objective,:revision,:sequence,:time)
                """).param("deck", deck).param("objective", objective).param("revision", revision)
                .param("sequence", sequence).param("time", Timestamp.from(time)).update();
    }

    void updateObjectiveHead(UUID deck, UUID objective, UUID revision, long sequence, Instant time) {
        jdbc.sql("""
                UPDATE app_learning.objective_head SET revision_id=:revision,objective_sequence=:sequence,updated_at=:time
                 WHERE deck_id=:deck AND objective_id=:objective
                """).param("revision", revision).param("sequence", sequence).param("time", Timestamp.from(time))
                .param("deck", deck).param("objective", objective).update();
    }

    void insertExercise(UUID deck, UUID exercise, UUID actor, UUID scope, Instant time) {
        jdbc.sql("""
                INSERT INTO app_learning.exercise_definition(deck_id,exercise_id,owner_id,reuse_scope_id,created_at)
                VALUES (:deck,:exercise,:actor,:scope,:time)
                """).param("deck", deck).param("exercise", exercise).param("actor", actor).param("scope", scope)
                .param("time", Timestamp.from(time)).update();
    }

    void insertExerciseRevision(UUID deck, UUID exercise, UUID revision, UUID scope, long sequence, UUID parent,
                                UUID deckRevision, long deckSequence, UUID command, ExerciseCommand.Exercise value,
                                UUID descriptor, Instant time) {
        jdbc.sql("""
                INSERT INTO app_learning.exercise_revision(deck_id,exercise_id,revision_id,reuse_scope_id,
                    exercise_sequence,parent_revision_id,parent_exercise_sequence,deck_revision_id,deck_sequence,
                    command_id,exercise_type,schema_version,enabled,prompt_spec,evaluator_policy,descriptor_root_id,created_at)
                VALUES (:deck,:exercise,:revision,:scope,:sequence,:parent,:parentSequence,:deckRevision,:deckSequence,
                    :command,:type,1,:enabled,CAST(:prompt AS jsonb),CAST(:evaluator AS jsonb),:descriptor,:time)
                """).param("deck", deck).param("exercise", exercise).param("revision", revision).param("scope", scope)
                .param("sequence", sequence).param("parent", parent, java.sql.Types.OTHER)
                .param("parentSequence", parent == null ? null : sequence - 1, java.sql.Types.BIGINT)
                .param("deckRevision", deckRevision).param("deckSequence", deckSequence).param("command", command)
                .param("type", value.type()).param("enabled", value.enabled()).param("prompt", value.prompt().toString())
                .param("evaluator", value.evaluatorPolicy().toString()).param("descriptor", descriptor)
                .param("time", Timestamp.from(time)).update();
    }

    void insertBinding(UUID deck, UUID exercise, UUID revision, ExerciseCommand.Binding binding,
                       UUID objective, UUID objectiveRevision) {
        jdbc.sql("""
                INSERT INTO app_learning.exercise_content_binding(deck_id,exercise_id,exercise_revision_id,binding_id,
                    binding_ordinal,role,member_key,item_revision_id,objective_id,objective_revision_id,node_ids,display_spec)
                VALUES (:deck,:exercise,:revision,:binding,:ordinal,:role,:member,:itemRevision,:objective,
                    :objectiveRevision,:nodeIds,CAST(:display AS jsonb))
                """).param("deck", deck).param("exercise", exercise).param("revision", revision)
                .param("binding", binding.bindingId()).param("ordinal", binding.ordinal()).param("role", binding.role())
                .param("member", binding.memberKey()).param("itemRevision", binding.itemRevisionId())
                .param("objective", objective, java.sql.Types.OTHER)
                .param("objectiveRevision", objectiveRevision, java.sql.Types.OTHER)
                .param("nodeIds", binding.nodeIds().toArray(UUID[]::new)).param("display", binding.display().toString())
                .update();
    }

    void insertExerciseHead(UUID deck, UUID exercise, UUID revision, long sequence, int ordinal, Instant time) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_head_exercise(deck_id,exercise_id,revision_id,exercise_sequence,ordinal,updated_at)
                VALUES (:deck,:exercise,:revision,:sequence,:ordinal,:time)
                """).param("deck", deck).param("exercise", exercise).param("revision", revision)
                .param("sequence", sequence).param("ordinal", ordinal).param("time", Timestamp.from(time)).update();
    }

    void updateExerciseHead(UUID deck, UUID exercise, UUID revision, long sequence, Instant time) {
        jdbc.sql("""
                UPDATE app_learning.deck_head_exercise SET revision_id=:revision,exercise_sequence=:sequence,updated_at=:time
                 WHERE deck_id=:deck AND exercise_id=:exercise
                """).param("revision", revision).param("sequence", sequence).param("time", Timestamp.from(time))
                .param("deck", deck).param("exercise", exercise).update();
    }

    void insertChange(UUID deck, UUID deckRevision, long deckSequence, UUID exercise, UUID previous,
                      UUID revision, int ordinal) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_exercise_change(deck_id,deck_revision_id,deck_sequence,exercise_id,
                    previous_revision_id,revision_id,ordinal)
                VALUES (:deck,:deckRevision,:deckSequence,:exercise,:previous,:revision,:ordinal)
                """).param("deck", deck).param("deckRevision", deckRevision).param("deckSequence", deckSequence)
                .param("exercise", exercise).param("previous", previous, java.sql.Types.OTHER)
                .param("revision", revision).param("ordinal", ordinal).update();
    }

    private static JsonNode json(String value) {
        try { return JSON.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid persisted JSON", exception); }
    }

    private static List<UUID> uuidArray(Array array) {
        try { return Arrays.stream((Object[]) array.getArray()).map(value -> (UUID) value).toList(); }
        catch (java.sql.SQLException exception) { throw new IllegalStateException("Invalid persisted UUID array", exception); }
    }
}
