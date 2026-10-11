package app.mnema.learning.update.plan;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.platform.jobs.JobTestAccess;
import app.mnema.learning.support.ContractFixtures;
import app.mnema.learning.update.plan.PlanWorlds.Copy;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.mnema.learning.update.plan.PlanWorlds.JSON;
import static app.mnema.learning.update.plan.PlanWorlds.json;

/**
 * The helpers of the apply command tests (Updates/4, #443): the command as the frontend sends it over the real route, and what the command may have written, read back from the
 * tables (heads, manifests, base, decisions, receipts), so a test can prove that a refused command wrote nothing.
 */
final class ApplyWorlds {
    final PlanWorlds worlds;
    final JdbcClient jdbc;
    final ManifestPages manifests;
    final UpdatePlanService plans;

    ApplyWorlds(PlanWorlds worlds, ManifestPages manifests, UpdatePlanService plans) {
        this.worlds = worlds;
        this.jdbc = worlds.jdbc;
        this.manifests = manifests;
        this.plans = plans;
    }

    /** A named unit and what to do with it. */
    record Pick(String unitId, String choice) {
        static Pick of(UnitKind kind, UUID key, String choice) { return new Pick(kind.unitId(key), choice); }

        static Pick material(UUID key, String choice) { return of(UnitKind.MATERIAL, key, choice); }

        static Pick exercise(UUID key, String choice) { return of(UnitKind.EXERCISE, key, choice); }
    }

    // ---- the command ----

    static String body(UUID command, String planVersion, boolean acceptSafe, Pick... picks) {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString()).put("planVersion", planVersion);
        if (acceptSafe) body.put("acceptSafe", true);
        if (picks.length > 0) {
            ArrayNode choices = body.putArray("choices");
            for (Pick pick : picks) choices.addObject().put("unitId", pick.unitId()).put("choice", pick.choice());
        }
        return body.toString();
    }

    HttpResponse<String> post(Copy copy, String body) {
        return worlds.send("POST", "/decks/" + copy.deck() + "/update-plan:apply", copy.owner(), body, null);
    }

    HttpResponse<String> apply(Copy copy, UUID command, String planVersion, boolean acceptSafe, Pick... picks) {
        return post(copy, body(command, planVersion, acceptSafe, picks));
    }

    /** The command with the CURRENT plan version of the copy. */
    HttpResponse<String> apply(Copy copy, boolean acceptSafe, Pick... picks) {
        return apply(copy, UUID.randomUUID(), version(copy), acceptSafe, picks);
    }

    String version(Copy copy) { return plans.plan(copy.owner(), copy.deck()).planVersion(); }

    UpdatePlan plan(Copy copy) { return plans.plan(copy.owner(), copy.deck()); }

    /** The plan as the route answers it (all pages concatenated into one list of units). */
    JsonNode planRoute(Copy copy) { return json(worlds.get("/decks/" + copy.deck() + "/update-plan?limit=100", copy.owner())); }

    static String code(HttpResponse<String> response) {
        return json(response).path("code").stringValue("");
    }

    // ---- the author's edits in bulk ----

    /**
     * Saves a new revision of each material (first text {@code prefix + index}) in bulk publications of 20; the materials are in the order of the deck, the first at
     * {@code firstOrdinal}. Returns the new revisions in the same order.
     */
    List<UUID> saveAll(UUID owner, UUID deck, List<UUID> members, int firstOrdinal, String prefix) {
        List<UUID> revisions = new ArrayList<>();
        for (int at = 0; at < members.size(); at += 20) {
            JsonNode head = worlds.decks.read(owner, deck);
            ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
            ArrayNode changes = body.putArray("changes");
            for (int index = at; index < Math.min(members.size(), at + 20); index++) {
                JsonNode current = worlds.items.read(owner, deck, members.get(index), null);
                ObjectNode document = (ObjectNode) current.path("document").deepCopy();
                ((ObjectNode) document.path("root").path("content").get(0).path("content").get(0).path("attrs")).put("text", prefix + " " + index);
                ObjectNode change = changes.addObject().put("operation", "save").put("memberKey", members.get(index).toString())
                        .put("expectedItemRevisionId", current.path("itemRevisionId").stringValue(null)).put("expectedOrdinal", firstOrdinal + index);
                change.set("document", document);
            }
            JsonNode acknowledged = worlds.items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)),
                    ItemPublicationCommand.readBulk(ContractFixtures.bytes(body))).acknowledgement();
            acknowledged.path("changes").forEach(change -> revisions.add(UUID.fromString(change.path("itemRevisionId").stringValue(null))));
        }
        return revisions;
    }

    /** Runs the jobs of the queue by hand until the copy is ready (a test without an executor). */
    Copy copyByHand(PlanWorlds.Source source, JobTestAccess access) {
        UUID owner = UUID.randomUUID();
        UUID deck = worlds.forked(owner, source.code());
        for (int round = 0; round < 50; round++) {
            if (!json(worlds.get("/decks/" + deck, owner)).path("fork").path("preparing").booleanValue(true)) break;
            access.runOne("deck.copy.materialize");
        }
        worlds.awaitReady(owner, deck);
        return new Copy(owner, deck);
    }

    // ---- what the tables hold ----

    long revisions(UUID deck) {
        return jdbc.sql("SELECT count(*) FROM app_learning.deck_revision WHERE deck_id=:d").param("d", deck).query(Long.class).single();
    }

    long receipts(UUID actor) {
        return jdbc.sql("SELECT count(*) FROM app_learning.command_receipt WHERE actor_id=:a").param("a", actor).query(Long.class).single();
    }

    UUID exerciseHead(UUID deck, UUID exercise) {
        return jdbc.sql("SELECT revision_id FROM app_learning.deck_head_exercise WHERE deck_id=:d AND exercise_id=:e").param("d", deck).param("e", exercise)
                .query(UUID.class).optional().orElse(null);
    }

    UUID objectiveHead(UUID deck, UUID objective) {
        return jdbc.sql("SELECT revision_id FROM app_learning.objective_head WHERE deck_id=:d AND objective_id=:o").param("d", deck).param("o", objective)
                .query(UUID.class).optional().orElse(null);
    }

    String title(UUID deck) {
        return jdbc.sql("SELECT r.title FROM app_learning.deck d JOIN app_learning.deck_revision r ON r.deck_id=d.deck_id AND r.revision_id=d.head_revision_id WHERE d.deck_id=:d")
                .param("d", deck).query(String.class).single();
    }

    /** The members of the deck's head, in the order of its manifest. */
    List<UUID> members(UUID deck) {
        var head = jdbc.sql("SELECT d.reuse_scope_id,r.members_root_id,r.member_count FROM app_learning.deck d JOIN app_learning.deck_revision r ON r.deck_id=d.deck_id "
                        + "AND r.revision_id=d.head_revision_id WHERE d.deck_id=:d").param("d", deck)
                .query((row, ignored) -> new Object[] {row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getInt(3)}).single();
        List<UUID> keys = new ArrayList<>();
        for (int at = 0; at < (int) head[2]; at += 100) {
            for (Entry entry : manifests.members((UUID) head[0], (UUID) head[1], (int) head[2], at, 100)) keys.add(entry.key());
        }
        return keys;
    }

    /** The members of the SOURCE's published revision, in order. */
    List<UUID> publishedMembers(UUID source) {
        var head = jdbc.sql("SELECT d.reuse_scope_id,r.members_root_id,r.member_count FROM app_learning.deck d JOIN app_learning.deck_publication p ON p.deck_id=d.deck_id "
                        + "JOIN app_learning.deck_revision r ON r.deck_id=d.deck_id AND r.revision_id=p.published_revision_id WHERE d.deck_id=:d").param("d", source)
                .query((row, ignored) -> new Object[] {row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getInt(3)}).single();
        List<UUID> keys = new ArrayList<>();
        for (int at = 0; at < (int) head[2]; at += 100) {
            for (Entry entry : manifests.members((UUID) head[0], (UUID) head[1], (int) head[2], at, 100)) keys.add(entry.key());
        }
        return keys;
    }

    /** The recorded decisions of the copy: {@code KIND:key} to {@code DECISION resolved base} ({@code -} for absent, {@code .} for not used). */
    Map<String, String> decisions(UUID deck) {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.sql("SELECT unit_kind,unit_key,decision,resolved_upstream_revision_id,resolved_upstream_absent,pending_base_revision_id,pending_base_absent "
                        + "FROM app_learning.fork_unit_decision WHERE deck_id=:d ORDER BY unit_kind,unit_key").param("d", deck)
                .query((row, ignored) -> result.put(row.getString(1) + ":" + row.getObject(2, UUID.class), row.getString(3) + " "
                        + state(row.getObject(4, UUID.class), row.getBoolean(5)) + " " + state(row.getObject(6, UUID.class), row.getBoolean(7)))).list();
        return result;
    }

    private static String state(UUID revision, boolean absent) { return absent ? "-" : revision == null ? "." : revision.toString(); }

    /** Everything an apply command may write or must leave alone, as one comparable string. */
    String snapshot(UUID deck) {
        return jdbc.sql("""
                SELECT jsonb_build_object(
                  'deck',(SELECT to_jsonb(d) FROM app_learning.deck d WHERE d.deck_id=:deck),
                  'revisions',(SELECT jsonb_agg(r.revision_id ORDER BY r.sequence) FROM app_learning.deck_revision r WHERE r.deck_id=:deck),
                  'items',(SELECT jsonb_agg(to_jsonb(h) - 'updated_at' ORDER BY h.member_key) FROM app_learning.deck_head_item h WHERE h.deck_id=:deck),
                  'exercises',(SELECT jsonb_agg(to_jsonb(h) - 'updated_at' ORDER BY h.exercise_id) FROM app_learning.deck_head_exercise h WHERE h.deck_id=:deck),
                  'objectives',(SELECT jsonb_agg(to_jsonb(h) - 'updated_at' ORDER BY h.objective_id) FROM app_learning.objective_head h WHERE h.deck_id=:deck),
                  'fork',(SELECT to_jsonb(f) FROM app_learning.deck_fork f WHERE f.deck_id=:deck),
                  'decisions',(SELECT jsonb_agg(to_jsonb(x) - 'decided_at' ORDER BY x.unit_kind,x.unit_key) FROM app_learning.fork_unit_decision x WHERE x.deck_id=:deck),
                  'itemChanges',(SELECT count(*) FROM app_learning.deck_item_change c WHERE c.deck_id=:deck),
                  'exerciseChanges',(SELECT count(*) FROM app_learning.deck_exercise_change c WHERE c.deck_id=:deck),
                  'receipts',(SELECT count(*) FROM app_learning.command_receipt c WHERE c.actor_id=(SELECT owner_id FROM app_learning.deck WHERE deck_id=:deck)),
                  'jobs',(SELECT count(*) FROM app_learning.learning_job j WHERE j.queue='deck.combined.publish' AND j.payload->>'deckId'=CAST(:deck AS text)),
                  'commands',(SELECT count(*) FROM app_learning.combined_publication c WHERE c.deck_id=:deck))::text
                """).param("deck", deck).query(String.class).single();
    }

    /** The plan route's units as {@code unitId -> class}. */
    static Map<String, String> classes(JsonNode plan) {
        Map<String, String> result = new LinkedHashMap<>();
        plan.path("units").forEach(unit -> result.put(unit.path("unitId").stringValue(null), unit.path("class").stringValue(null)));
        return result;
    }

    static Map<String, String> classes(UpdatePlan plan) {
        Map<String, String> result = new LinkedHashMap<>();
        plan.units().forEach(unit -> result.put(unit.unitId(), unit.unitClass().name()));
        return result;
    }
}
