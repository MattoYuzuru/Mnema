package app.mnema.learning.generation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Ephemeral intent hand-over: one atomic claim, no provider call in a transaction, no request text after completion or deadline. */
@Component
final class IntentQueue {
    record Job(UUID id, UUID owner, UUID deck, IntentSpecs.Context context, String title, String request, Instant deadline) { }
    record Answer(JsonNode result, String error) { }

    private final JdbcClient jdbc;
    private final GenerationSettings settings;

    IntentQueue(JdbcClient jdbc, GenerationSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    UUID submit(UUID owner, UUID deck, IntentSpecs.Context context, String title, String request) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.generation_intent_request(id,owner_id,deck_id,context,title,request,deadline_at) "
                        + "VALUES (:id,:owner,:deck,CAST(:context AS jsonb),:title,:request,CURRENT_TIMESTAMP + (:millis * interval '1 millisecond'))")
                .param("id", id).param("owner", owner).param("deck", deck).param("context", Json.MAPPER.writeValueAsString(context))
                .param("title", title).param("request", request).param("millis", settings.intent().deadline().toMillis()).update();
        return id;
    }

    Optional<Job> claim() {
        return jdbc.sql("UPDATE app_learning.generation_intent_request SET state='RUNNING' WHERE id=(SELECT id "
                        + "FROM app_learning.generation_intent_request WHERE state='QUEUED' AND deadline_at>CURRENT_TIMESTAMP "
                        + "ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING id,owner_id,deck_id,context::text,title,request,deadline_at")
                .query((row, index) -> new Job(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class), row.getObject("deck_id", UUID.class),
                        context(Json.read(row.getString("context"))), row.getString("title"), row.getString("request"), row.getTimestamp("deadline_at").toInstant()))
                .optional();
    }

    Optional<Answer> read(UUID id, UUID owner) {
        return jdbc.sql("SELECT result::text,error FROM app_learning.generation_intent_request "
                        + "WHERE id=:id AND owner_id=:owner AND state IN ('SUCCEEDED','FAILED') AND deadline_at>CURRENT_TIMESTAMP")
                .param("id", id).param("owner", owner).query((row, index) -> new Answer(row.getString("result") == null ? null : Json.read(row.getString("result")),
                        row.getString("error"))).optional();
    }

    void complete(UUID id, JsonNode result, String error) {
        jdbc.sql("UPDATE app_learning.generation_intent_request SET state=:state,result=CAST(:result AS jsonb),error=:error,"
                        + "context=NULL,title=NULL,request=NULL WHERE id=:id AND state='RUNNING' AND deadline_at>CURRENT_TIMESTAMP")
                .param("state", error == null ? "SUCCEEDED" : "FAILED").param("result", result == null ? null : Json.write(result)).param("error", error)
                .param("id", id).update();
    }

    /** Removing a request fences a late worker result; an interrupted HTTP request has no subsequent product effect. */
    void discard(UUID id, UUID owner) {
        jdbc.sql("DELETE FROM app_learning.generation_intent_request WHERE id=:id AND owner_id=:owner").param("id", id).param("owner", owner).update();
    }

    void expire() {
        jdbc.sql("DELETE FROM app_learning.generation_intent_request WHERE deadline_at<=CURRENT_TIMESTAMP").update();
    }

    private static IntentSpecs.Context context(JsonNode node) {
        return new IntentSpecs.Context(node.path("kind").stringValue(), uuid(node, "memberKey"), uuid(node, "itemRevisionId"),
                uuid(node, "exerciseId"), uuid(node, "exerciseRevisionId"), node.path("hasAudio").booleanValue(), node.path("speakable").booleanValue());
    }

    private static UUID uuid(JsonNode node, String name) {
        return node.path(name).isNull() ? null : UUID.fromString(node.path(name).stringValue());
    }
}
