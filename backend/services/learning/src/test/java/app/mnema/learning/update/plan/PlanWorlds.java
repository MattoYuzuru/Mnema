package app.mnema.learning.update.plan;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.MediaFixtures;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Material;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.quote;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worlds of the update plan tests, built with the production services and the production HTTP routes: an author's published deck, a real copy of it made by
 * {@code POST /decks/forks} and materialized by the real job, and everything the author and the learner do to their decks afterwards (edits, additions, removals, order, exercises,
 * objectives, titles) through the same services the editor uses. The plan is then asked of the real service or the real route.
 * Large media-free initial bulks use BulkItemFixture; operations under test and small authoring worlds keep the production command path.
 */
final class PlanWorlds {
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int BULK = 20;

    /** An exercise as its publication acknowledged it. */
    record Authored(UUID exerciseId, UUID revisionId, UUID objectiveId, UUID objectiveRevisionId) {
        static Authored of(JsonNode acknowledgement) {
            return new Authored(id(acknowledgement, "exerciseId"), id(acknowledgement, "exerciseRevisionId"), id(acknowledgement, "objectiveId"),
                    id(acknowledgement, "objectiveRevisionId"));
        }
    }

    /** The author's published deck: materials in the order created (with the nodes the exercises quote) and the exercises on the first of them. */
    record Source(UUID author, UUID deck, String code, List<Material> materials, List<Authored> exercises) { }

    final int port;
    final DeckService decks;
    final ItemService items;
    final ExerciseService exercises;
    final JdbcClient jdbc;
    final ManifestPages manifests;
    final StudyFixtures study;
    final SharedScopeFixture shared;
    final app.mnema.learning.catalog.item.BulkItemFixture bulkItems;

    PlanWorlds(int port, DeckService decks, ItemService items, ExerciseService exercises, StudySessionService sessions, MediaCatalog media, ImmutableStorage storage,
               JdbcClient jdbc, PlatformTransactionManager transactions, ManifestPages manifests) {
        this.port = port;
        this.decks = decks;
        this.items = items;
        this.exercises = exercises;
        this.jdbc = jdbc;
        this.manifests = manifests;
        this.study = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        this.shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
        this.bulkItems = new app.mnema.learning.catalog.item.BulkItemFixture(storage, jdbc, transactions);
    }

    static UUID id(JsonNode node, String field) { return UUID.fromString(node.path(field).stringValue(null)); }

    // ---- http ----

    HttpResponse<String> send(String method, String path, UUID account, String body, String ifMatch) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (ifMatch != null) headers.put("If-Match", ifMatch);
        return HttpIdentityFixture.send(port, method, path, account == null ? null : HttpIdentityFixture.reader(account), body, headers);
    }

    HttpResponse<String> get(String path, UUID account) { return send("GET", path, account, null, null); }

    static JsonNode json(HttpResponse<String> response) {
        try { return JSON.readTree(response.body()); } catch (RuntimeException failure) { throw new AssertionError(response.body(), failure); }
    }

    UUID forked(UUID account, String code) {
        HttpResponse<String> response = send("POST", "/decks/forks", account,
                JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("sourceCode", code).toString(), null);
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 201);
        return UUID.fromString(json(response).path("deckId").stringValue(null));
    }

    /** Polls the owner's deck until the copy is ready (or fails the test after 90 s). */
    void awaitReady(UUID owner, UUID deck) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = get("/decks/" + deck, owner);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            last = json(response);
            if (!last.path("fork").path("preparing").booleanValue(true)) return;
            assertThat(last.path("fork").path("failed").booleanValue(false)).as("the copy failed: " + last).isFalse();
            try { Thread.sleep(100); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        }
        throw new AssertionError("the copy did not become ready: " + last);
    }

    /** A ready copy of the source for a new account. */
    Copy copy(Source source) {
        UUID owner = UUID.randomUUID();
        UUID deck = forked(owner, source.code());
        awaitReady(owner, deck);
        return new Copy(owner, deck);
    }

    record Copy(UUID owner, UUID deck) { }

    // ---- the author's deck ----

    /** A deck of {@code materials} materials, the first {@code exercises} of them with one FREE_RESPONSE exercise each, published at {@code level}. */
    Source source(String level, int materials, int exerciseCount) { return source(level, materials, exerciseCount, 0).source(); }

    /** The author's deck and the members of its {@code bulk} plain materials created after the study materials (in the order of the deck). */
    record Big(Source source, List<UUID> bulk) { }

    Big source(String level, int materials, int exerciseCount, int bulk) {
        UUID author = UUID.randomUUID();
        HttpIdentityFixture.publicProfile(author, true);
        UUID deck = shared.deck(author, "Источник " + UUID.randomUUID().toString().substring(0, 8));
        List<Material> created = new ArrayList<>();
        List<Authored> authored = new ArrayList<>();
        for (int index = 0; index < materials; index++) {
            Material material = study.addMaterial(author, deck, "слово " + index, "перевод " + index);
            created.add(material);
            if (index < exerciseCount) {
                authored.add(Authored.of(study.publish(material, study.freeResponse(material, blocks(text("Что значит " + index + "?")), blocks(text("Подсказка")),
                        "перевод " + index), "Цель " + index)));
            }
        }
        List<UUID> plain = bulk == 0 ? List.of() : bulk(author, deck, bulk, "материал");
        return new Big(new Source(author, deck, publish(author, deck, level), List.copyOf(created), List.copyOf(authored)), List.copyOf(plain));
    }

    /** Many materials (without exercises), created in publications of {@value #BULK}; returns the members in order. */
    List<UUID> bulk(UUID owner, UUID deck, int count, String prefix) {
        if (count >= 100) {
            var documents = java.util.stream.IntStream.range(0, count).mapToObj(index -> {
                ObjectNode document = MediaFixtures.imageDocument();
                ((ObjectNode) document.path("root").path("content").get(0).path("content").get(0).path("attrs")).put("text", prefix + " " + index);
                return document;
            }).toList();
            return bulkItems.append(owner, deck, documents).stream().map(app.mnema.learning.catalog.item.BulkItemFixture.Created::member).toList();
        }
        List<UUID> members = new ArrayList<>();
        for (int from = 0; from < count; from += BULK) {
            JsonNode head = decks.read(owner, deck);
            ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
            ArrayNode changes = body.putArray("changes");
            for (int index = from; index < Math.min(count, from + BULK); index++) {
                ObjectNode change = changes.addObject().put("operation", "create").put("memberKey", UUID.randomUUID().toString());
                change.set("document", MediaFixtures.imageDocument());
                ((ObjectNode) change.path("document").path("root").path("content").get(0).path("content").get(0).path("attrs")).put("text", prefix + " " + index);
            }
            JsonNode acknowledged = items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)), ItemPublicationCommand.readBulk(bytes(body)))
                    .acknowledgement().path("changes");
            acknowledged.forEach(change -> members.add(UUID.fromString(change.path("memberKey").stringValue(null))));
        }
        return members;
    }

    /** Publishes the deck's current head at {@code level} (the production command) and returns the public code. */
    String publish(UUID author, UUID deck, String level) {
        HttpResponse<String> saved = publishRaw(author, deck, level);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        return json(get("/decks/" + deck + "/publication", author)).path("publicCode").stringValue(null);
    }

    HttpResponse<String> publishRaw(UUID author, UUID deck, String level) { return publishRaw(author, deck, level, null); }

    HttpResponse<String> publishRaw(UUID author, UUID deck, String level, String releaseNote) {
        HttpResponse<String> state = get("/decks/" + deck + "/publication", author);
        assertThat(state.statusCode()).as(state.body()).isEqualTo(200);
        String etag = state.headers().firstValue("etag").orElseThrow();
        UUID head = UUID.fromString(decks.read(author, deck).path("revisionId").stringValue(null));
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("visibility", level);
        ObjectNode metadata = body.putObject("metadata").put("topicId", "japanese").put("contentLanguage", "ru").put("targetLanguage", "ja").put("level", "A2");
        metadata.putArray("tags").add("jlpt n5");
        body.put("requestsEnabled", true);
        ObjectNode publish = body.putObject("publish").put("expectedHeadRevisionId", head.toString());
        if (releaseNote == null) publish.putNull("releaseNote"); else publish.put("releaseNote", releaseNote);
        return send("PUT", "/decks/" + deck + "/publication", author, body.toString(), etag);
    }

    /** Changes the level only (the production command); returns the public code, which a lowering rotates. */
    String level(UUID author, UUID deck, String level) {
        HttpResponse<String> state = get("/decks/" + deck + "/publication", author);
        String etag = state.headers().firstValue("etag").orElseThrow();
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("visibility", level);
        ObjectNode metadata = body.putObject("metadata").put("topicId", "japanese").put("contentLanguage", "ru").put("targetLanguage", "ja").put("level", "A2");
        metadata.putArray("tags").add("jlpt n5");
        body.put("requestsEnabled", true).putNull("publish");
        HttpResponse<String> saved = send("PUT", "/decks/" + deck + "/publication", author, body.toString(), etag);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        return json(get("/decks/" + deck + "/publication", author)).path("publicCode").stringValue(null);
    }

    // ---- what either side does to its deck (the author in the source, the learner in the copy) ----

    private static ObjectNode paragraphText(JsonNode node, int index) {
        ObjectNode paragraph = (ObjectNode) node.path("root").path("content").get(index);
        return (ObjectNode) paragraph.path("content").get(0).path("attrs");
    }

    private UUID headRevision(UUID owner, UUID deck, UUID member) {
        return UUID.fromString(items.read(owner, deck, member, null).path("itemRevisionId").stringValue(null));
    }

    /** Saves a new revision of the material whose FIRST paragraph reads {@code value}; every node keeps its id. Returns the new revision. */
    UUID editText(UUID owner, UUID deck, UUID member, String value) {
        ObjectNode document = (ObjectNode) items.read(owner, deck, member, null).path("document").deepCopy();
        paragraphText(document, 0).put("text", value);
        return shared.save(owner, deck, member, headRevision(owner, deck, member), document);
    }

    /** As {@link #editText} for the SECOND paragraph (the first and its node stay exactly as they were). */
    UUID editSecond(UUID owner, UUID deck, UUID member, String value) {
        ObjectNode document = (ObjectNode) items.read(owner, deck, member, null).path("document").deepCopy();
        paragraphText(document, 1).put("text", value);
        return shared.save(owner, deck, member, headRevision(owner, deck, member), document);
    }

    /** Saves the resulting document with the structural edits that explain the change (a save of a document with another structure is refused without them). */
    private UUID saveWithEdits(UUID owner, UUID deck, UUID member, ObjectNode document, ArrayNode edits) {
        JsonNode head = decks.read(owner, deck);
        JsonNode current = items.read(owner, deck, member, null);
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedDeckRevisionId", head.path("revisionId").stringValue(null))
                .put("expectedItemRevisionId", current.path("itemRevisionId").stringValue(null)).put("expectedOrdinal", current.path("ordinal").intValue());
        body.set("document", document);
        body.set("edits", edits);
        JsonNode change = items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)), ItemPublicationCommand.readSave(bytes(body), member))
                .acknowledgement().path("changes").get(0);
        return UUID.fromString(change.path("itemRevisionId").stringValue(null));
    }

    /** Deletes the first paragraph with its text: the node ids of the old revision that were in it exist no more. */
    UUID dropFirst(UUID owner, UUID deck, UUID member) {
        ObjectNode document = (ObjectNode) items.read(owner, deck, member, null).path("document").deepCopy();
        ArrayNode content = (ArrayNode) document.path("root").path("content");
        String node = content.get(0).path("id").stringValue(null);
        content.remove(0);
        ArrayNode edits = JSON.createArrayNode();
        edits.addObject().put("type", "delete").put("nodeId", node);
        return saveWithEdits(owner, deck, member, document, edits);
    }

    /** Appends a paragraph to the material; returns {revision, node id of the new paragraph}. */
    record Appended(UUID revision, UUID node) { }

    Appended append(UUID owner, UUID deck, UUID member, String value) {
        ObjectNode document = (ObjectNode) items.read(owner, deck, member, null).path("document").deepCopy();
        UUID node = UUID.randomUUID();
        ArrayNode content = (ArrayNode) document.path("root").path("content");
        int index = content.size();
        ObjectNode paragraph = content.addObject().put("id", node.toString()).put("type", "paragraph").put("version", 1);
        paragraph.putObject("attrs");
        ObjectNode leaf = paragraph.putArray("content").addObject().put("id", UUID.randomUUID().toString()).put("type", "text").put("version", 1);
        leaf.putObject("attrs").put("text", value);
        leaf.putArray("content");
        ArrayNode edits = JSON.createArrayNode();
        edits.addObject().put("type", "insert").put("nodeId", node.toString()).put("parentId", document.path("root").path("id").stringValue(null)).put("childIndex", index);
        return new Appended(saveWithEdits(owner, deck, member, document, edits), node);
    }

    void deleteMaterial(UUID owner, UUID deck, UUID member) {
        MediaFixtures.deleteMaterial(decks, items, owner, deck, member, headRevision(owner, deck, member));
    }

    /** Moves the material to {@code ordinal} (its position after the move). */
    void reorder(UUID owner, UUID deck, UUID member, int ordinal) {
        JsonNode head = decks.read(owner, deck);
        JsonNode current = items.read(owner, deck, member, null);
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
        body.putArray("changes").addObject().put("operation", "reorder").put("memberKey", member.toString())
                .put("expectedItemRevisionId", current.path("itemRevisionId").stringValue(null)).put("expectedOrdinal", current.path("ordinal").intValue()).put("ordinal", ordinal);
        items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)), ItemPublicationCommand.readBulk(bytes(body)));
    }

    void retitle(UUID owner, UUID deck, String title, String description) {
        decks.save(owner, deck, Long.parseLong(decks.read(owner, deck).path("rowVersion").stringValue(null)), new DeckCommand(UUID.randomUUID(), title, description));
    }

    /** The material as the owner of {@code deck} holds it NOW (its head revision), for building an exercise on it. */
    Material head(UUID owner, UUID deck, Material template) {
        JsonNode current = items.read(owner, deck, template.member(), null);
        return new Material(owner, deck, template.member(), UUID.fromString(current.path("itemRevisionId").stringValue(null)), template.node(), template.distractor(),
                template.divider(), template.root());
    }

    /** A new exercise on the material (as the owner of the deck holds it now) quoting {@code quoted}, with a new objective; returns its ids. */
    Authored exercise(Material material, String question, UUID quoted, String objective) {
        ArrayNode prompt = quoted == null ? blocks(text(question)) : blocks(text(question), quote(material, quoted));
        return Authored.of(study.publish(material, study.freeResponse(material, prompt, blocks(), "memory"), objective));
    }

    /** Revises the exercise (new question text and, if {@code objective} is not null, a new title of its objective) on the material the owner holds now. */
    Authored revise(UUID owner, UUID deck, Material template, Authored previous, String question, String objective) {
        return revise(owner, deck, template, previous, question, objective, true);
    }

    Authored revise(UUID owner, UUID deck, Material template, Authored previous, String question, String objective, boolean enabled) {
        Material subject = head(owner, deck, template);
        ObjectNode exercise = study.freeResponse(subject, blocks(text(question)), blocks(), "memory");
        exercise.put("enabled", enabled);
        return reviseWith(owner, deck, subject, previous, exercise, objective);
    }

    /** A new revision of exercise number {@code index} of {@link #source} with exactly its original content, only {@code enabled} as given. */
    Authored reviseOriginal(UUID owner, UUID deck, Material template, Authored previous, int index, boolean enabled) {
        Material subject = head(owner, deck, template);
        ObjectNode exercise = study.freeResponse(subject, blocks(text("Что значит " + index + "?")), blocks(text("Подсказка")), "перевод " + index);
        exercise.put("enabled", enabled);
        return reviseWith(owner, deck, subject, previous, exercise, null);
    }

    private Authored reviseWith(UUID owner, UUID deck, Material subject, Authored previous, ObjectNode exercise, String objective) {
        ObjectNode body = study.createBody(subject, exercise, "ignored");
        ObjectNode operation = JSON.createObjectNode();
        if (objective == null) {
            operation.put("operation", "reuse").put("objectiveId", previous.objectiveId().toString()).put("objectiveRevisionId", previous.objectiveRevisionId().toString());
        } else {
            operation.put("operation", "revise").put("objectiveId", previous.objectiveId().toString()).put("expectedObjectiveRevisionId", previous.objectiveRevisionId().toString())
                    .put("title", objective);
        }
        body.set("objective", operation);
        body.put("expectedExerciseRevisionId", previous.revisionId().toString());
        return Authored.of(exercises.publish(owner, deck, previous.exerciseId(), study.deckVersion(subject), ExerciseCommand.readUpdate(bytes(body))).acknowledgement());
    }

    /** A NEW exercise on the material that evidences the SAME objective (revision) as {@code shared}. */
    Authored exerciseReusing(Material material, Authored shared, String question) {
        ObjectNode body = study.createBody(material, study.freeResponse(material, blocks(text(question)), blocks(), "memory"), "ignored");
        body.set("objective", JSON.createObjectNode().put("operation", "reuse").put("objectiveId", shared.objectiveId().toString())
                .put("objectiveRevisionId", shared.objectiveRevisionId().toString()));
        return Authored.of(exercises.publish(material.actor(), material.deck(), null, study.deckVersion(material), ExerciseCommand.readCreate(bytes(body))).acknowledgement());
    }

    void deleteExercise(UUID owner, UUID deck, UUID exercise) {
        exercises.delete(owner, deck, exercise, Long.parseLong(decks.read(owner, deck).path("rowVersion").stringValue(null)));
    }

    // ---- facts about the database ----

    UUID base(UUID copy) { return jdbc.sql("SELECT base_revision_id FROM app_learning.deck_fork WHERE deck_id=:d").param("d", copy).query(UUID.class).single(); }

    UUID published(UUID source) { return jdbc.sql("SELECT published_revision_id FROM app_learning.deck_publication WHERE deck_id=:d").param("d", source).query(UUID.class).single(); }

    UUID headRevisionOf(UUID deck) { return jdbc.sql("SELECT head_revision_id FROM app_learning.deck WHERE deck_id=:d").param("d", deck).query(UUID.class).single(); }

    UUID itemHead(UUID deck, UUID member) {
        return jdbc.sql("SELECT revision_id FROM app_learning.deck_head_item WHERE deck_id=:d AND member_key=:m").param("d", deck).param("m", member).query(UUID.class).optional().orElse(null);
    }

    /** Test stand-in for the Updates/4 apply: moves the base of the copy (the V61 guard allows it forward) and records the decisions. */
    void moveBase(UUID copy, UUID base) {
        jdbc.sql("UPDATE app_learning.deck_fork SET base_revision_id=:base WHERE deck_id=:d").param("base", base).param("d", copy).update();
    }

    void decide(UUID copy, String kind, UUID key, String decision, UUID resolved, boolean resolvedAbsent, UUID pendingBase, boolean pendingBaseAbsent) {
        jdbc.sql("""
                INSERT INTO app_learning.fork_unit_decision(deck_id,reuse_scope_id,unit_kind,unit_key,decision,resolved_upstream_revision_id,resolved_upstream_absent,
                    pending_base_revision_id,pending_base_absent,decided_at)
                SELECT d.deck_id,d.reuse_scope_id,:kind,:key,:decision,:resolved,:resolvedAbsent,:pending,:pendingAbsent,clock_timestamp()
                  FROM app_learning.deck d WHERE d.deck_id=:deck
                ON CONFLICT (deck_id,unit_kind,unit_key) DO UPDATE SET decision=EXCLUDED.decision,resolved_upstream_revision_id=EXCLUDED.resolved_upstream_revision_id,
                    resolved_upstream_absent=EXCLUDED.resolved_upstream_absent,pending_base_revision_id=EXCLUDED.pending_base_revision_id,
                    pending_base_absent=EXCLUDED.pending_base_absent
                """).param("deck", copy).param("kind", kind).param("key", key).param("decision", decision).param("resolved", resolved, java.sql.Types.OTHER)
                .param("resolvedAbsent", resolvedAbsent).param("pending", pendingBase, java.sql.Types.OTHER).param("pendingAbsent", pendingBaseAbsent).update();
    }

    static Path contract(String name) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/decks/" + name))) root = root.getParent();
        return root.resolve("contracts/decks/" + name);
    }

    static JsonNode updatesContract() {
        try { return JSON.readTree(Files.readString(contract("updates.json"))); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
