package app.mnema.learning.fork;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.StudyFixtures;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worlds of the Share/10 tests, built with the production services and the production HTTP routes: an author's deck with materials and exercises, published at a
 * level through {@code PUT /publication}, copied through {@code POST /decks/forks}. Heads of a copy are compared with the PUBLISHED manifest read through the
 * counted-page reader, never with the source's heads.
 */
final class ForkWorlds {
    static final JsonMapper JSON = JsonMapper.builder().build();
    /** Creates per publication: a bulk of 100 does not fit the 10 s of a publication on a busy machine. */
    private static final int BULK = 20;

    /** An author's published deck: its materials (with the nodes the exercises quote) and the exercises' ids. */
    record Source(UUID author, UUID deck, String code, List<StudyFixtures.Material> materials, List<UUID> exercises) { }

    final int port;
    final DeckService decks;
    final ItemService items;
    final JdbcClient jdbc;
    final ManifestPages manifests;
    final StudyFixtures study;
    final SharedScopeFixture shared;

    ForkWorlds(int port, DeckService decks, ItemService items, ExerciseService exercises, StudySessionService sessions, MediaCatalog media,
               ImmutableStorage storage, JdbcClient jdbc, PlatformTransactionManager transactions, ManifestPages manifests) {
        this.port = port;
        this.decks = decks;
        this.items = items;
        this.jdbc = jdbc;
        this.manifests = manifests;
        this.study = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        this.shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
    }

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

    static ObjectNode command(UUID commandId, String code) {
        return JSON.createObjectNode().put("commandId", commandId.toString()).put("sourceCode", code);
    }

    HttpResponse<String> fork(UUID account, String code) { return fork(account, UUID.randomUUID(), code); }

    HttpResponse<String> fork(UUID account, UUID commandId, String code) {
        return send("POST", "/decks/forks", account, command(commandId, code).toString(), null);
    }

    /** The deck id of a successful copy command. */
    UUID forked(UUID account, String code) {
        HttpResponse<String> response = fork(account, code);
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 201);
        return UUID.fromString(json(response).path("deckId").stringValue(null));
    }

    /** Polls the owner's deck until the copy is ready (or fails the test after a minute). */
    JsonNode awaitReady(UUID owner, UUID deck) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = get("/decks/" + deck, owner);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            last = json(response);
            if (!last.path("fork").path("preparing").booleanValue(true)) return last;
            assertThat(last.path("fork").path("failed").booleanValue(false)).as("the copy failed: " + last).isFalse();
            try { Thread.sleep(100); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        }
        throw new AssertionError("the copy did not become ready: " + last);
    }

    // ---- the author's side ----

    /** A deck of {@code materials} materials, the first {@code exercises} of them with one FREE_RESPONSE exercise each, published at {@code level}. */
    Source source(String level, int materials, int exercises) {
        UUID author = UUID.randomUUID();
        HttpIdentityFixture.publicProfile(author, true);
        UUID deck = shared.deck(author, "Источник " + UUID.randomUUID().toString().substring(0, 8));
        List<StudyFixtures.Material> created = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < materials; index++) {
            StudyFixtures.Material material = study.addMaterial(author, deck, "слово " + index, "перевод " + index);
            created.add(material);
            if (index < exercises) {
                JsonNode published = study.publish(material, study.freeResponse(material, blocks(text("Что значит " + index + "?")),
                        blocks(text("Подсказка")), "перевод " + index), "Цель " + index);
                ids.add(UUID.fromString(published.path("exerciseId").stringValue(null)));
            }
        }
        String code = publish(author, deck, level);
        return new Source(author, deck, code, List.copyOf(created), List.copyOf(ids));
    }

    /**
     * A big deck built the way an author builds one: materials in publications of {@value #BULK} creates, then {@code exercises} FREE_RESPONSE exercises one by one, published at
     * {@code level}. Every material has its own text; the exercises assess the first materials.
     */
    Source bigSource(String level, int materials, int exercises) {
        UUID author = UUID.randomUUID();
        HttpIdentityFixture.publicProfile(author, true);
        UUID deck = shared.deck(author, "Большая колода " + UUID.randomUUID().toString().substring(0, 8));
        List<StudyFixtures.Material> created = new ArrayList<>();
        for (int from = 0; from < materials; from += BULK) {
            JsonNode head = decks.read(author, deck);
            ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
            var changes = body.putArray("changes");
            for (int index = from; index < Math.min(materials, from + BULK); index++) {
                changes.addObject().put("operation", "create").put("memberKey", UUID.randomUUID().toString()).set("document", app.mnema.learning.support.MediaFixtures.imageDocument());
            }
            JsonNode acknowledged = items.publish(author, deck, Long.parseLong(head.path("rowVersion").stringValue(null)),
                    app.mnema.learning.catalog.item.ItemPublicationCommand.readBulk(new java.io.ByteArrayInputStream(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                    .acknowledgement().path("changes");
            for (JsonNode change : acknowledged) {
                created.add(new StudyFixtures.Material(author, deck, UUID.fromString(change.path("memberKey").stringValue(null)),
                        UUID.fromString(change.path("itemRevisionId").stringValue(null)), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
            }
        }
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < exercises; index++) {
            StudyFixtures.Material material = created.get(index);
            JsonNode published = study.publish(material, study.freeResponse(material, blocks(text("Вопрос " + index)), blocks(text("Подсказка")), "ответ " + index), "Цель " + index);
            ids.add(UUID.fromString(published.path("exerciseId").stringValue(null)));
        }
        return new Source(author, deck, publish(author, deck, level), List.copyOf(created), List.copyOf(ids));
    }

    /** Publishes the deck's current head at {@code level} (the production command) and returns the public code. */
    String publish(UUID author, UUID deck, String level) {
        HttpResponse<String> saved = publishRaw(author, deck, level);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        return json(get("/decks/" + deck + "/publication", author)).path("publicCode").stringValue(null);
    }

    /** The publication command itself, answer unjudged. */
    HttpResponse<String> publishRaw(UUID author, UUID deck, String level) {
        HttpResponse<String> state = get("/decks/" + deck + "/publication", author);
        assertThat(state.statusCode()).as(state.body()).isEqualTo(200);
        String etag = state.headers().firstValue("etag").orElseThrow();
        UUID head = UUID.fromString(decks.read(author, deck).path("revisionId").stringValue(null));
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("visibility", level);
        ObjectNode metadata = body.putObject("metadata").put("topicId", "japanese").put("contentLanguage", "ru").put("targetLanguage", "ja").put("level", "A2");
        metadata.putArray("tags").add("jlpt n5");
        body.put("requestsEnabled", true);
        body.putObject("publish").put("expectedHeadRevisionId", head.toString()).putNull("releaseNote");
        return send("PUT", "/decks/" + deck + "/publication", author, body.toString(), etag);
    }

    /** Changes the level only, keeping the published revision (the production command). */
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

    /** Saves a new revision of a material with other text (its own document, only the first text node changed, so it is an ordinary edit); returns the new revision. */
    UUID edit(UUID owner, UUID deck, UUID member, UUID expectedRevision, String text) {
        ObjectNode document = (ObjectNode) items.read(owner, deck, member, null).path("document").deepCopy();
        ((ObjectNode) document.path("root").path("content").get(0).path("content").get(0).path("attrs")).put("text", text);
        return shared.save(owner, deck, member, expectedRevision, document);
    }

    // ---- the update state of a copy (Updates/6) ----

    /** The revision the source's publication row names as published right now. */
    UUID publishedRevision(UUID source) {
        return jdbc.sql("SELECT published_revision_id FROM app_learning.deck_publication WHERE deck_id = :d").param("d", source).query(UUID.class).single();
    }

    /** The author adds a material and publishes the head again at {@code level}; returns the new published revision. */
    UUID republish(Source source, String level) {
        study.addMaterial(source.author(), source.deck(), "ещё " + UUID.randomUUID().toString().substring(0, 6), "ещё");
        publish(source.author(), source.deck(), level);
        return publishedRevision(source.deck());
    }

    /** Marks a copy ready without running the job (no executor runs in an {@code api} context); its heads stay empty, which the update state does not read. */
    void markReady(UUID copy) {
        jdbc.sql("UPDATE app_learning.deck_fork SET materialized_at = statement_timestamp() WHERE deck_id = :d AND materialized_at IS NULL").param("d", copy).update();
    }

    /** What the apply command of Updates/4 does to the base: moves it forward to the published revision (the V61 guard decides if that is legal). */
    void moveBase(UUID copy, UUID revision) {
        jdbc.sql("UPDATE app_learning.deck_fork SET base_revision_id = :r WHERE deck_id = :d").param("d", copy).param("r", revision).update();
    }

    /** A PENDING decision of the copy for a made-up material (unit left for later: its base and the author's offered state are both "absent"). */
    UUID pend(UUID copy) {
        UUID unit = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO app_learning.fork_unit_decision(deck_id, reuse_scope_id, unit_kind, unit_key, decision, resolved_upstream_absent, pending_base_absent, decided_at)
                SELECT deck_id, reuse_scope_id, 'MATERIAL', :unit, 'PENDING', TRUE, TRUE, statement_timestamp() FROM app_learning.deck WHERE deck_id = :d
                """).param("d", copy).param("unit", unit).update();
        return unit;
    }

    /** A decided unit (the author removed it and the learner keeps his): it is no reason for the badge. */
    void keepMine(UUID copy) {
        jdbc.sql("""
                INSERT INTO app_learning.fork_unit_decision(deck_id, reuse_scope_id, unit_kind, unit_key, decision, resolved_upstream_absent, decided_at)
                SELECT deck_id, reuse_scope_id, 'MATERIAL', gen_random_uuid(), 'KEEP_MINE', TRUE, statement_timestamp() FROM app_learning.deck WHERE deck_id = :d
                """).param("d", copy).update();
    }

    /** The copy's {@code fork} member as the owner reads it. */
    JsonNode forkOf(UUID owner, UUID copy) {
        HttpResponse<String> response = get("/decks/" + copy, owner);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response).path("fork");
    }

    // ---- the published manifest and the heads of a copy ----

    /** What the PUBLISHED revision of {@code source} holds, resolved through the lineage by the manifest's descriptors. */
    record Manifest(List<String> members, List<String> exercises, List<String> objectives, int memberCount, int exerciseCount) { }

    Manifest manifest(UUID source) {
        record Root(UUID scope, UUID members, UUID exercises, int memberCount, int exerciseCount) { }
        Root root = jdbc.sql("""
                SELECT r.reuse_scope_id, r.members_root_id, r.exercises_root_id, r.member_count, r.exercise_count
                  FROM app_learning.deck_publication p JOIN app_learning.deck_revision r ON r.deck_id = p.deck_id AND r.revision_id = p.published_revision_id
                 WHERE p.deck_id = :deck
                """).param("deck", source).query((row, ignored) -> new Root(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getObject(3, UUID.class), row.getInt(4), row.getInt(5))).single();
        List<String> members = new ArrayList<>();
        List<Entry> memberEntries = new ArrayList<>();
        for (int at = 0; at < root.memberCount(); at += 100) {
            memberEntries.addAll(manifests.members(root.scope(), root.members(), root.memberCount(), at, Math.min(100, root.memberCount() - at)));
        }
        List<Entry> exerciseEntries = new ArrayList<>();
        for (int at = 0; at < root.exerciseCount(); at += 100) {
            exerciseEntries.addAll(manifests.exercises(root.scope(), root.exercises(), root.exerciseCount(), at, Math.min(100, root.exerciseCount() - at)));
        }
        {
            for (Entry entry : memberEntries) {
                members.add(jdbc.sql("SELECT member_key || '/' || revision_id || '/' || item_sequence FROM app_learning.item_revision "
                                + "WHERE reuse_scope_id = :scope AND member_key = :key AND descriptor_root_id = :descriptor")
                        .param("scope", root.scope()).param("key", entry.key()).param("descriptor", entry.target().objectId()).query(String.class).single());
            }
        }
        List<String> exercises = new ArrayList<>();
        List<String> objectives = new ArrayList<>();
        {
            int ordinal = 0;
            for (Entry entry : exerciseEntries) {
                exercises.add(ordinal++ + "/" + jdbc.sql("SELECT exercise_id || '/' || revision_id || '/' || exercise_sequence FROM app_learning.exercise_revision "
                                + "WHERE reuse_scope_id = :scope AND exercise_id = :key AND descriptor_root_id = :descriptor")
                        .param("scope", root.scope()).param("key", entry.key()).param("descriptor", entry.target().objectId()).query(String.class).single());
                objectives.add(jdbc.sql("SELECT b.objective_id || '/' || b.objective_revision_id FROM app_learning.exercise_revision r "
                                + "JOIN app_learning.exercise_content_binding b ON b.reuse_scope_id = r.reuse_scope_id AND b.exercise_id = r.exercise_id "
                                + "AND b.exercise_revision_id = r.revision_id AND b.role = 'ASSESSED' "
                                + "WHERE r.reuse_scope_id = :scope AND r.exercise_id = :key AND r.descriptor_root_id = :descriptor")
                        .param("scope", root.scope()).param("key", entry.key()).param("descriptor", entry.target().objectId()).query(String.class).single());
            }
        }
        return new Manifest(members, exercises, objectives.stream().distinct().sorted().toList(), root.memberCount(), root.exerciseCount());
    }

    /** The heads a copy holds, in the same notation as {@link #manifest}. */
    Manifest heads(UUID copy) {
        List<String> members = jdbc.sql("SELECT member_key || '/' || revision_id || '/' || item_sequence FROM app_learning.deck_head_item WHERE deck_id = :deck")
                .param("deck", copy).query(String.class).list();
        List<String> exercises = jdbc.sql("SELECT ordinal || '/' || exercise_id || '/' || revision_id || '/' || exercise_sequence FROM app_learning.deck_head_exercise "
                + "WHERE deck_id = :deck ORDER BY ordinal").param("deck", copy).query(String.class).list();
        List<String> objectives = jdbc.sql("SELECT objective_id || '/' || revision_id FROM app_learning.objective_head WHERE deck_id = :deck ORDER BY 1")
                .param("deck", copy).query(String.class).list();
        return new Manifest(members, exercises, objectives, members.size(), exercises.size());
    }

    /** The copy's heads equal the published manifest of {@code source}: members as a set, exercises with their manifest ordinals, objectives pinned by the bindings. */
    void assertHeadsEqualPublishedManifest(UUID source, UUID copy) {
        Manifest expected = manifest(source);
        Manifest actual = heads(copy);
        assertThat(new TreeSet<>(actual.members())).as("material heads").isEqualTo(new TreeSet<>(expected.members()));
        assertThat(actual.members()).as("no material twice").doesNotHaveDuplicates();
        assertThat(actual.exercises()).as("exercise heads with manifest ordinals").isEqualTo(expected.exercises());
        assertThat(actual.objectives()).as("objective heads").isEqualTo(expected.objectives());
        assertThat(actual.memberCount()).isEqualTo(expected.memberCount());
        assertThat(actual.exerciseCount()).isEqualTo(expected.exerciseCount());
    }

    static Path contract(String name) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/decks/" + name))) root = root.getParent();
        return root.resolve("contracts/decks/" + name);
    }

    static JsonNode forksContract() {
        try { return JSON.readTree(Files.readString(contract("forks.json"))); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
