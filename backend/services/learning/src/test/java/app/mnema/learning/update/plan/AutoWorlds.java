package app.mnema.learning.update.plan;

import app.mnema.learning.platform.jobs.JobTestAccess;
import app.mnema.learning.update.plan.PlanWorlds.Copy;
import app.mnema.learning.update.plan.PlanWorlds.Source;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static app.mnema.learning.update.plan.PlanWorlds.JSON;
import static app.mnema.learning.update.plan.PlanWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The helpers of the automatic-update and «Что нового» tests (Updates/6 part B, #445): copies that follow the source automatically, copies that look idle or active, the jobs of the
 * two queues as rows, and everything a read or a skipped job must leave alone, as one comparable string.
 */
final class AutoWorlds {
    static final String FANOUT = "deck.update.fanout";
    static final String AUTO = "deck.update.auto";

    /** A job of one of the queues as the table holds it. */
    record JobRow(UUID id, String key, UUID account, String state, ObjectNode payload, long progressCount, boolean waiting) { }

    final PlanWorlds worlds;
    final ApplyWorlds apply;
    final JdbcClient jdbc;
    final PlatformTransactionManager transactions;

    AutoWorlds(PlanWorlds worlds, ApplyWorlds apply, PlatformTransactionManager transactions) {
        this.worlds = worlds;
        this.apply = apply;
        this.jdbc = worlds.jdbc;
        this.transactions = transactions;
    }

    // ---- copies ----

    HttpResponse<String> autoUpdate(UUID owner, UUID deck, boolean on) {
        return worlds.send("PUT", "/decks/" + deck + "/fork:auto-update", owner, JSON.createObjectNode().put("autoUpdate", on).toString(), null);
    }

    /** A ready copy of the source for a new account, following the author automatically. */
    Copy autoCopy(Source source, JobTestAccess hand) {
        Copy copy = apply.copyByHand(source, hand);
        assertThat(autoUpdate(copy.owner(), copy.deck(), true).statusCode()).isEqualTo(200);
        return copy;
    }

    /** Pushes the creation of the copy {@code days} into the past (the guard that keeps the column immutable is a trigger; the test switches triggers off for the statement). */
    void age(UUID deck, int days) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE app_learning.deck_fork SET forked_at = forked_at - (:days * interval '1 day') WHERE deck_id = :deck").param("days", days).param("deck", deck).update();
        });
    }

    /** The owner starts a Study session of the copy (the signal of "active"). */
    void study(Copy copy) {
        ObjectNode request = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("mode", "SCHEDULED");
        request.putObject("budget").put("maxPresentations", 20).put("maxNewObjectives", 20);
        HttpResponse<String> response = worlds.send("POST", "/decks/" + copy.deck() + "/study-sessions", copy.owner(), request.toString(), null);
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 202);
    }

    void setAutoDirectly(UUID deck, boolean on) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE app_learning.deck_fork SET auto_update = :on WHERE deck_id = :deck").param("on", on).param("deck", deck).update();
        });
    }

    // ---- jobs ----

    List<JobRow> jobs(String queue) {
        return jdbc.sql("SELECT job_id,dedupe_key,account_id,state,payload::text AS payload,progress_count,(state='QUEUED' AND run_after > clock_timestamp()) AS waiting "
                        + "FROM app_learning.learning_job WHERE queue=:queue ORDER BY created_at,job_id").param("queue", queue)
                .query((row, ignored) -> new JobRow(row.getObject("job_id", UUID.class), row.getString("dedupe_key"), row.getObject("account_id", UUID.class), row.getString("state"),
                        (ObjectNode) read(row.getString("payload")), row.getLong("progress_count"), row.getBoolean("waiting"))).list();
    }

    /** The jobs of the queue about the deck (a source for the fan-out, a copy for the apply). */
    List<JobRow> jobs(String queue, UUID deck) {
        return jobs(queue).stream().filter(job -> deck.toString().equals(job.payload().path("deckId").stringValue(null))).toList();
    }

    /** The automatic-apply jobs for these published revisions (the queue is shared by the tests of the class). */
    List<JobRow> autoJobs(UUID... revisions) {
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (UUID revision : revisions) wanted.add(revision.toString());
        return jobs(AUTO).stream().filter(job -> wanted.contains(job.payload().path("revisionId").stringValue(null))).toList();
    }

    /** The database is shared by the test classes and a worker claims the oldest job of a queue: whatever is queued or running goes. */
    void clearQueues() {
        jdbc.sql("UPDATE app_learning.learning_job SET state='CANCELLED', finished_at=clock_timestamp(), lease_token=NULL, lease_until=NULL "
                + "WHERE state IN ('QUEUED','RUNNING') AND queue IN ('deck.combined.publish','deck.copy.materialize','deck.update.fanout','deck.update.auto')").update();
    }

    private static JsonNode read(String text) {
        try { return JSON.readTree(text); } catch (RuntimeException failure) { throw new AssertionError(text, failure); }
    }

    // ---- what a read or a skip must leave alone ----

    /** The apply snapshot of the copy plus the log of «Что нового» (with the marks) and the jobs about it. */
    String full(UUID deck) {
        return apply.snapshot(deck) + jdbc.sql("""
                SELECT COALESCE((SELECT jsonb_agg(to_jsonb(l) ORDER BY l.applied_at,l.deck_revision_id) FROM app_learning.fork_update_log l WHERE l.deck_id=:deck),'[]'::jsonb)::text
                    || COALESCE((SELECT jsonb_agg(to_jsonb(o) ORDER BY o.deck_revision_id,o.objective_id) FROM app_learning.fork_update_log_objective o WHERE o.deck_id=:deck),'[]'::jsonb)::text
                    || (SELECT count(*) FROM app_learning.learning_job j WHERE j.queue IN ('deck.update.auto','deck.combined.publish') AND j.payload->>'deckId'=CAST(:deck AS text))::text
                """).param("deck", deck).query(String.class).single();
    }

    List<JsonNode> log(UUID deck) {
        return jdbc.sql("SELECT to_jsonb(l)::text FROM app_learning.fork_update_log l WHERE l.deck_id=:deck ORDER BY l.applied_at,l.deck_revision_id").param("deck", deck)
                .query((row, ignored) -> read(row.getString(1))).list();
    }

    JsonNode deck(Copy copy) { return json(worlds.get("/decks/" + copy.deck(), copy.owner())); }

    JsonNode history(Copy copy) { return json(worlds.get("/decks/" + copy.deck() + "/update-history", copy.owner())); }
}
