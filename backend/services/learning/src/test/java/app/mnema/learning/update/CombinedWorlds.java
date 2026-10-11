package app.mnema.learning.update;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.SharedScopeFixture.Copy;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.update.CombinedUnits.ExerciseOp;
import app.mnema.learning.update.CombinedUnits.ItemOp;
import app.mnema.learning.update.CombinedUnits.Metadata;
import app.mnema.learning.update.CombinedUnits.ObjectiveOp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;

/**
 * The scenes of the combined publication tests, built with the real services: an author's deck, a copy in the same lineage ({@link SharedScopeFixture}), what the
 * author does afterwards (edits, additions, removals, order, exercises, objectives) and the readers of a deck's head (the manifests through {@link ManifestPages}).
 */
final class CombinedWorlds {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final int BULK = 25;

    final DeckService decks;
    final ItemService items;
    final ExerciseService exercises;
    final MediaCatalog media;
    final JdbcClient jdbc;
    final ManifestPages manifests;
    final StudyFixtures study;
    final SharedScopeFixture shared;
    private final PlatformTransactionManager transactions;

    CombinedWorlds(DeckService decks, ItemService items, ExerciseService exercises, StudySessionService sessions, MediaCatalog media, ImmutableStorage storage,
                   JdbcClient jdbc, PlatformTransactionManager transactions, ManifestPages manifests) {
        this.decks = decks;
        this.items = items;
        this.exercises = exercises;
        this.media = media;
        this.jdbc = jdbc;
        this.manifests = manifests;
        this.transactions = transactions;
        this.study = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        this.shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
    }

    /** An exercise as the author published it: its ids and the material it assesses. */
    record Authored(UUID exerciseId, UUID revisionId, UUID objectiveId, UUID objectiveRevisionId) {
        static Authored of(JsonNode acknowledgement) {
            return new Authored(id(acknowledgement, "exerciseId"), id(acknowledgement, "exerciseRevisionId"), id(acknowledgement, "objectiveId"),
                    id(acknowledgement, "objectiveRevisionId"));
        }
    }

    /** The author's deck with materials A, B, C (exercises on A and B) and a copy of it for another account, made right away. */
    record Fork(UUID author, UUID source, Material a, Material b, Material c, Authored exerciseA, Authored exerciseB, Copy copy) {
        UUID copyOwner() { return copy.owner(); }

        UUID copyDeck() { return copy.deckId(); }
    }

    Fork fork() {
        Material a = study.material();
        Material b = study.addMaterial(a.actor(), a.deck(), "beta", "other");
        Material c = study.addMaterial(a.actor(), a.deck(), "gamma", "third");
        Authored exerciseA = Authored.of(study.publish(a, study.freeResponse(a, blocks(text("A question")), blocks(), "memory"), "About A"));
        Authored exerciseB = Authored.of(study.publish(b, study.freeResponse(b, blocks(text("B question")), blocks(), "beta"), "About B"));
        Copy copy = copyOf(a.deck(), UUID.randomUUID());
        return new Fork(a.actor(), a.deck(), a, b, c, exerciseA, exerciseB, copy);
    }

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    /**
     * A copy of {@code upstream} for {@code owner}: the heads and roots of the {@link SharedScopeFixture} copy plus the live, materialized, attached
     * {@code deck_fork} row the real copy job leaves (written with the guards off, as the fixture writes the rest of the copy).
     */
    Copy copyOf(UUID upstream, UUID owner) {
        Copy copy = shared.copy(upstream, owner);
        String code = new java.util.Random().ints(10, 0, ALPHABET.length()).collect(StringBuilder::new, (text, index) -> text.append(ALPHABET.charAt(index)), StringBuilder::append).toString();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("INSERT INTO app_learning.deck_fork(deck_id,reuse_scope_id,owner_id,upstream_deck_id,upstream_code,base_revision_id,forked_at,job_id,materialized_at) "
                            + "SELECT :deck,:scope,:owner,:upstream,:code,d.head_revision_id,clock_timestamp(),gen_random_uuid(),clock_timestamp() FROM app_learning.deck d WHERE d.deck_id=:upstream")
                    .param("deck", copy.deckId()).param("scope", copy.scope()).param("owner", owner).param("upstream", upstream).param("code", code).update();
        });
        return copy;
    }

    /** The material as the copy's owner sees it. */
    static Material inCopy(Material material, Copy copy) {
        return new Material(copy.owner(), copy.deckId(), material.member(), material.itemRevision(), material.node(), material.distractor(), material.divider(),
                material.root());
    }

    // ---- what an author does ----

    /** Saves a new revision of a material whose first text becomes {@code text} (every node keeps its id); returns the new revision. */
    UUID save(UUID owner, UUID deck, UUID member, UUID expected, String text) {
        JsonNode head = decks.read(owner, deck);
        JsonNode current = items.read(owner, deck, member, null);
        ObjectNode document = (ObjectNode) current.path("document").deepCopy();
        firstText(document.path("root")).put("text", text);
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedDeckRevisionId", head.path("revisionId").stringValue(null))
                .put("expectedItemRevisionId", expected.toString()).put("expectedOrdinal", current.path("ordinal").intValue());
        body.set("document", document);
        JsonNode change = items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)), ItemPublicationCommand.readSave(bytes(body), member))
                .acknowledgement().path("changes").get(0);
        return UUID.fromString(change.path("itemRevisionId").stringValue(null));
    }

    private static ObjectNode firstText(JsonNode node) {
        if ("text".equals(node.path("type").stringValue(null))) return (ObjectNode) node.path("attrs");
        for (JsonNode child : node.path("content")) {
            ObjectNode found = firstText(child);
            if (found != null) return found;
        }
        return null;
    }

    /** Revises an exercise (and its objective title) so that it assesses the material at {@code subject}; returns the new ids. */
    Authored revise(Material subject, Authored previous, String objectiveTitle) {
        ObjectNode body = study.createBody(subject, study.freeResponse(subject, blocks(text("Revised question")), blocks(), "memory"), "ignored");
        body.set("objective", JSON.createObjectNode().put("operation", "revise").put("objectiveId", previous.objectiveId().toString())
                .put("expectedObjectiveRevisionId", previous.objectiveRevisionId().toString()).put("title", objectiveTitle));
        body.put("expectedExerciseRevisionId", previous.revisionId().toString());
        return Authored.of(exercises.publish(subject.actor(), subject.deck(), previous.exerciseId(), study.deckVersion(subject),
                ExerciseCommand.readUpdate(bytes(body))).acknowledgement());
    }

    void deleteExercise(UUID owner, UUID deck, UUID exercise) {
        exercises.delete(owner, deck, exercise, Long.parseLong(decks.read(owner, deck).path("rowVersion").stringValue(null)));
    }

    /** Moves a material to {@code ordinal} (its position after the move). */
    void reorder(UUID owner, UUID deck, UUID member, int ordinal) {
        JsonNode head = decks.read(owner, deck);
        JsonNode current = items.read(owner, deck, member, null);
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
        body.putArray("changes").addObject().put("operation", "reorder").put("memberKey", member.toString())
                .put("expectedItemRevisionId", current.path("itemRevisionId").stringValue(null)).put("expectedOrdinal", current.path("ordinal").intValue())
                .put("ordinal", ordinal);
        items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)), ItemPublicationCommand.readBulk(bytes(body)));
    }

    /** {@code count} new materials in bulk commands of {@value #BULK} (an item publication applies in a transaction of 10 s: a loaded machine needs the margin); returns {member, revision} pairs in order. */
    List<UUID[]> createMany(UUID owner, UUID deck, int count, String prefix) {
        List<UUID[]> created = new ArrayList<>();
        for (int at = 0; at < count; at += BULK) {
            JsonNode head = decks.read(owner, deck);
            ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
            ArrayNode changes = body.putArray("changes");
            for (int index = at; index < Math.min(count, at + BULK); index++) {
                UUID member = UUID.randomUUID();
                ObjectNode change = changes.addObject().put("operation", "create").put("memberKey", member.toString());
                change.set("document", document(prefix + " " + index, member));
            }
            JsonNode result = items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)), ItemPublicationCommand.readBulk(bytes(body)))
                    .acknowledgement();
            result.path("changes").forEach(change -> created.add(new UUID[] {UUID.fromString(change.path("memberKey").stringValue(null)),
                    UUID.fromString(change.path("itemRevisionId").stringValue(null))}));
        }
        return created;
    }

    /** Saves a new revision of each material in bulk commands of {@value #BULK} (the materials are in the order of the deck, starting at {@code firstOrdinal}); returns the new revisions. */
    List<UUID> saveMany(UUID owner, UUID deck, List<UUID[]> materials, int firstOrdinal, String prefix) {
        List<UUID> revisions = new ArrayList<>();
        for (int at = 0; at < materials.size(); at += BULK) {
            JsonNode head = decks.read(owner, deck);
            ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
            ArrayNode changes = body.putArray("changes");
            for (int index = at; index < Math.min(materials.size(), at + BULK); index++) {
                ObjectNode change = changes.addObject().put("operation", "save").put("memberKey", materials.get(index)[0].toString())
                        .put("expectedItemRevisionId", materials.get(index)[1].toString()).put("expectedOrdinal", firstOrdinal + index);
                change.set("document", document(prefix + " " + index, materials.get(index)[0]));
            }
            JsonNode result = items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)), ItemPublicationCommand.readBulk(bytes(body)))
                    .acknowledgement();
            result.path("changes").forEach(change -> revisions.add(UUID.fromString(change.path("itemRevisionId").stringValue(null))));
        }
        return revisions;
    }

    /** A native document of one paragraph; the node ids derive from {@code seed}, so a later revision of the same material keeps them. */
    static ObjectNode document(String value, UUID seed) {
        ObjectNode document = JSON.createObjectNode().put("formatVersion", 1);
        ObjectNode root = document.putObject("root").put("id", derived(seed, "root").toString()).put("type", "doc").put("version", 1);
        root.putObject("attrs");
        ObjectNode paragraph = root.putArray("content").addObject().put("id", derived(seed, "paragraph").toString()).put("type", "paragraph").put("version", 1);
        paragraph.putObject("attrs");
        ObjectNode leaf = paragraph.putArray("content").addObject().put("id", derived(seed, "text").toString()).put("type", "text").put("version", 1);
        leaf.putObject("attrs").put("text", value);
        leaf.putArray("content");
        return document;
    }

    /** A stable id derived from the seed, shaped as a version 4 UUID (the entity-id policy accepts registered random versions only). */
    private static UUID derived(UUID seed, String role) {
        UUID hashed = UUID.nameUUIDFromBytes((seed + role).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new UUID((hashed.getMostSignificantBits() & ~0xF000L) | 0x4000L, hashed.getLeastSignificantBits());
    }

    /** Ends every job of the combined publication queue that is still waiting or running: the database is shared by the test classes and a worker claims the oldest job. */
    void cancelPendingJobs() {
        jdbc.sql("UPDATE app_learning.learning_job SET state='CANCELLED', finished_at=clock_timestamp(), lease_token=NULL, lease_until=NULL "
                + "WHERE queue='deck.combined.publish' AND state IN ('QUEUED','RUNNING')").update();
    }

    // ---- readers ----

    /** The head of a deck: its revision, version, roots and counts. */
    record DeckHead(UUID scope, UUID revisionId, long version, UUID membersRoot, UUID exercisesRoot, int memberCount, int exerciseCount, String title, String description) { }

    DeckHead head(UUID deck) {
        return jdbc.sql("""
                SELECT d.reuse_scope_id,d.head_revision_id,d.row_version,r.members_root_id,r.exercises_root_id,r.member_count,r.exercise_count,r.title,r.description
                  FROM app_learning.deck d JOIN app_learning.deck_revision r ON r.deck_id=d.deck_id AND r.revision_id=d.head_revision_id WHERE d.deck_id=:deck
                """).param("deck", deck).query((row, ignored) -> new DeckHead(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getLong(3),
                row.getObject(4, UUID.class), row.getObject(5, UUID.class), row.getInt(6), row.getInt(7), row.getString(8), row.getString(9))).single();
    }

    /** The members manifest of the deck's head, in order: (key, descriptor object). */
    List<String> members(UUID deck) {
        DeckHead head = head(deck);
        List<String> result = new ArrayList<>();
        for (int at = 0; at < head.memberCount(); at += 100) {
            for (Entry entry : manifests.members(head.scope(), head.membersRoot(), head.memberCount(), at, 100)) {
                result.add(entry.key() + "/" + entry.target().objectId());
            }
        }
        return result;
    }

    List<String> exercises(UUID deck) {
        DeckHead head = head(deck);
        List<String> result = new ArrayList<>();
        for (int at = 0; at < head.exerciseCount(); at += 100) {
            for (Entry entry : manifests.exercises(head.scope(), head.exercisesRoot(), head.exerciseCount(), at, 100)) {
                result.add(entry.key() + "/" + entry.target().objectId());
            }
        }
        return result;
    }

    /** member key -> head revision of the deck's material heads. */
    java.util.Map<UUID, UUID> itemHeads(UUID deck) {
        java.util.Map<UUID, UUID> result = new java.util.HashMap<>();
        jdbc.sql("SELECT member_key,revision_id FROM app_learning.deck_head_item WHERE deck_id=:deck").param("deck", deck)
                .query((row, ignored) -> result.put(row.getObject(1, UUID.class), row.getObject(2, UUID.class))).list();
        return result;
    }

    java.util.Map<UUID, UUID> exerciseHeads(UUID deck) {
        java.util.Map<UUID, UUID> result = new java.util.HashMap<>();
        jdbc.sql("SELECT exercise_id,revision_id FROM app_learning.deck_head_exercise WHERE deck_id=:deck").param("deck", deck)
                .query((row, ignored) -> result.put(row.getObject(1, UUID.class), row.getObject(2, UUID.class))).list();
        return result;
    }

    java.util.Map<UUID, UUID> objectiveHeads(UUID deck) {
        java.util.Map<UUID, UUID> result = new java.util.HashMap<>();
        jdbc.sql("SELECT objective_id,revision_id FROM app_learning.objective_head WHERE deck_id=:deck").param("deck", deck)
                .query((row, ignored) -> result.put(row.getObject(1, UUID.class), row.getObject(2, UUID.class))).list();
        return result;
    }

    /** Everything a combined publication may write or must leave alone, as one comparable string (the heads, the journals, the revisions of the deck). */
    String snapshot(UUID deck) {
        return jdbc.sql("""
                SELECT jsonb_build_object(
                  'deck',(SELECT to_jsonb(d) FROM app_learning.deck d WHERE d.deck_id=:deck),
                  'revisions',(SELECT jsonb_agg(r.revision_id ORDER BY r.sequence) FROM app_learning.deck_revision r WHERE r.deck_id=:deck),
                  'items',(SELECT jsonb_agg(to_jsonb(h) - 'updated_at' ORDER BY h.member_key) FROM app_learning.deck_head_item h WHERE h.deck_id=:deck),
                  'exercises',(SELECT jsonb_agg(to_jsonb(h) - 'updated_at' ORDER BY h.exercise_id) FROM app_learning.deck_head_exercise h WHERE h.deck_id=:deck),
                  'objectives',(SELECT jsonb_agg(to_jsonb(h) - 'updated_at' ORDER BY h.objective_id) FROM app_learning.objective_head h WHERE h.deck_id=:deck),
                  'itemChanges',(SELECT count(*) FROM app_learning.deck_item_change c WHERE c.deck_id=:deck),
                  'exerciseChanges',(SELECT count(*) FROM app_learning.deck_exercise_change c WHERE c.deck_id=:deck))::text
                """).param("deck", deck).query(String.class).single();
    }

    long receipts(UUID command) {
        return jdbc.sql("SELECT count(*) FROM app_learning.command_receipt WHERE command_id=:command").param("command", command).query(Long.class).single();
    }

    long revisions(UUID deck) {
        return jdbc.sql("SELECT count(*) FROM app_learning.deck_revision WHERE deck_id=:deck").param("deck", deck).query(Long.class).single();
    }

    // ---- commands ----

    static CombinedUnits units(UUID owner, UUID deck, UUID expectedHead, Metadata metadata, List<ItemOp> itemOps, List<ExerciseOp> exerciseOps,
                               List<ObjectiveOp> objectiveOps) {
        return new CombinedUnits(UUID.randomUUID(), deck, owner, expectedHead, metadata, itemOps, exerciseOps, objectiveOps);
    }

    static UUID id(JsonNode node, String field) { return UUID.fromString(node.path(field).stringValue(null)); }
}
