package app.mnema.learning.community;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.DeckModeration;
import app.mnema.learning.library.DeckVisibility;
import app.mnema.learning.library.PublicationCommand;
import app.mnema.learning.library.PublicationMetadata;
import app.mnema.learning.library.PublicationService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.MediaFixtures;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.StudyFixtures;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static app.mnema.learning.support.StudyFixtures.audio;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.category;
import static app.mnema.learning.support.StudyFixtures.item;
import static app.mnema.learning.support.StudyFixtures.option;
import static app.mnema.learning.support.StudyFixtures.text;

/**
 * The worlds of the Community/1 tests, built with the production services: an author's deck with materials, exercises of every mechanic and media, published at a level
 * through {@link PublicationService} (the production command, which writes the outbox row and enqueues the projection job in its transaction), hidden through
 * {@link DeckModeration} and deleted through {@link DeckService}. No executor runs in the test context (role {@code api}); the jobs are run by hand: {@link #run} gives a
 * handler one slice in its own transaction, as the executor would, with a job the test writes itself, so the order of the jobs is the test's.
 */
final class CatalogWorlds {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Creates per bulk command; a bulk of 100 does not fit the 10 s of a publication on a busy machine. */
    private static final int BULK = 20;

    /** An author's deck: materials in creation order (the exercises are attached to the first ones). */
    record World(UUID owner, UUID deck, List<StudyFixtures.Material> materials) { }

    final DeckService decks;
    final ItemService items;
    final JdbcClient jdbc;
    final StudyFixtures study;
    final SharedScopeFixture shared;
    final PublicationService publication;
    final DeckModeration moderation;
    final TransactionTemplate transaction;

    CatalogWorlds(DeckService decks, ItemService items, ExerciseService exercises, StudySessionService sessions, MediaCatalog media, ImmutableStorage storage,
                  JdbcClient jdbc, PlatformTransactionManager transactions, PublicationService publication, DeckModeration moderation) {
        this.decks = decks;
        this.items = items;
        this.jdbc = jdbc;
        this.study = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        this.shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
        this.publication = publication;
        this.moderation = moderation;
        this.transaction = new TransactionTemplate(transactions);
    }

    // ---- the author's deck ----

    /** A new deck of a new owner with {@code materials} plain materials (bulk commands of {@value #BULK}). */
    World deck(String title, String description, int materials) {
        UUID owner = UUID.randomUUID();
        UUID deck = UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), title, description)).acknowledgement().path("deck").path("deckId").stringValue(null));
        List<StudyFixtures.Material> created = new ArrayList<>();
        for (int from = 0; from < materials; from += BULK) created.addAll(bulk(owner, deck, Math.min(BULK, materials - from)));
        return new World(owner, deck, created);
    }

    private List<StudyFixtures.Material> bulk(UUID owner, UUID deck, int count) {
        JsonNode head = decks.read(owner, deck);
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
        ArrayNode changes = body.putArray("changes");
        for (int index = 0; index < count; index++) {
            changes.addObject().put("operation", "create").put("memberKey", UUID.randomUUID().toString()).set("document", MediaFixtures.imageDocument());
        }
        JsonNode acknowledged = items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)),
                ItemPublicationCommand.readBulk(new java.io.ByteArrayInputStream(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))).acknowledgement().path("changes");
        List<StudyFixtures.Material> created = new ArrayList<>();
        for (JsonNode change : acknowledged) {
            created.add(new StudyFixtures.Material(owner, deck, UUID.fromString(change.path("memberKey").stringValue(null)),
                    UUID.fromString(change.path("itemRevisionId").stringValue(null)), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        }
        return created;
    }

    /** Adds one more plain material to the head (an unpublished change until the next publication). */
    StudyFixtures.Material addMaterial(World world) {
        return study.addMaterial(world.owner(), world.deck(), "ещё " + UUID.randomUUID().toString().substring(0, 6), "ещё");
    }

    /** Adds a material whose document shows one asset of the given node type ({@code image}, {@code audio} or {@code video}). */
    void addMediaMaterial(World world, String nodeType, UUID asset) {
        ObjectNode document = MediaFixtures.imageDocument(asset);
        if (!nodeType.equals("image")) {
            ObjectNode node = (ObjectNode) document.path("root").path("content").get(1);
            node.put("type", nodeType);
            ObjectNode attrs = (ObjectNode) node.path("attrs");
            attrs.removeAll();
            attrs.put("assetId", asset.toString()).put("title", "Звук");
        }
        shared.create(world.owner(), world.deck(), document);
    }

    /** Publishes one exercise of the given mechanic for a material of the deck; returns its id. */
    UUID exercise(StudyFixtures.Material material, ExerciseType type) {
        return exercise(material, type, true);
    }

    /** As {@link #exercise(StudyFixtures.Material, ExerciseType)}; a disabled exercise is part of the manifest but is not studied. */
    UUID exercise(StudyFixtures.Material material, ExerciseType type, boolean enabled) {
        ObjectNode exercise = switch (type) {
            case SELF_CHECK -> study.selfCheck(material, blocks(text("Вспомните")), blocks(text("Ответ")));
            case FREE_RESPONSE -> study.freeResponse(material, blocks(text("Что значит?")), blocks(), "ответ");
            case CLOZE -> {
                UUID blank = UUID.randomUUID();
                yield study.cloze(material, blocks(), blocks(text("Вставьте: "), StudyFixtures.blank(blank, false, 0, true)), StudyFixtures.blankKey(blank, "слово"));
            }
            case CHOICE -> {
                UUID correct = UUID.randomUUID();
                yield study.choice(material, false, blocks(text("Выберите")), blocks().add(option(correct, text("верно"))).add(option(UUID.randomUUID(), text("неверно"))), correct);
            }
            case MATCH -> {
                UUID leftOne = UUID.randomUUID(), leftTwo = UUID.randomUUID(), rightOne = UUID.randomUUID(), rightTwo = UUID.randomUUID();
                yield study.match(material, blocks(text("Сопоставьте")), blocks().add(item(leftOne, text("1"))).add(item(leftTwo, text("2"))),
                        blocks().add(item(rightOne, text("один"))).add(item(rightTwo, text("два"))), new UUID[][] {{leftOne, rightOne}, {leftTwo, rightTwo}});
            }
            case ORDER -> {
                UUID first = UUID.randomUUID(), second = UUID.randomUUID();
                yield study.order(material, blocks(text("Расставьте")), blocks().add(item(first, text("раз"))).add(item(second, text("два"))), first, second);
            }
            case CATEGORIZE -> {
                UUID noun = UUID.randomUUID(), verb = UUID.randomUUID(), house = UUID.randomUUID(), run = UUID.randomUUID();
                yield study.categorize(material, blocks(text("Распределите")), blocks().add(category(noun, "Существительное")).add(category(verb, "Глагол")),
                        blocks().add(item(house, text("дом"))).add(item(run, text("бежать"))), new UUID[][] {{house, noun}, {run, verb}});
            }
        };
        exercise.put("enabled", enabled);
        return UUID.fromString(study.publish(material, exercise, "Цель " + UUID.randomUUID().toString().substring(0, 6)).path("exerciseId").stringValue(null));
    }

    /** A free-response exercise whose prompt plays an audio asset (a listening exercise). */
    UUID listening(StudyFixtures.Material material, UUID audioAsset) {
        ObjectNode exercise = study.freeResponse(material, blocks(audio(audioAsset, "Звук", null), text("Что слышно?")), blocks(), "ответ");
        return UUID.fromString(study.publish(material, exercise, "Слух " + UUID.randomUUID().toString().substring(0, 6)).path("exerciseId").stringValue(null));
    }

    // ---- the production publication command ----

    static PublicationMetadata metadata(String... tags) {
        return new PublicationMetadata("spanish", "ru", "es", "A2", List.of(tags));
    }

    /** Moves the deck to {@code level} and publishes its head (the command of the owner). */
    void publish(World world, DeckVisibility level, PublicationMetadata metadata) {
        save(world, level, metadata, true);
    }

    /** Publishes the head at PUBLIC with the default metadata and one tag. */
    void publish(World world) {
        publish(world, DeckVisibility.PUBLIC, metadata("испанский"));
    }

    /** Changes the level and the metadata only, keeping the published revision. */
    void setLevel(World world, DeckVisibility level, PublicationMetadata metadata) {
        save(world, level, metadata, false);
    }

    void setLevel(World world, DeckVisibility level) {
        setLevel(world, level, metadata("испанский"));
    }

    private void save(World world, DeckVisibility level, PublicationMetadata metadata, boolean publishHead) {
        long version = Long.parseLong(publication.read(world.owner(), world.deck(), true).path("rowVersion").stringValue(null));
        UUID head = UUID.fromString(decks.read(world.owner(), world.deck()).path("revisionId").stringValue(null));
        PublicationCommand command = new PublicationCommand(UUID.randomUUID(), level, metadata, true,
                publishHead ? new PublicationCommand.Publish(head, null) : null);
        publication.save(world.owner(), world.deck(), version, command, true);
    }

    /** The owner saves new title and description as a new head revision (not published). */
    void rename(World world, String title, String description) {
        decks.save(world.owner(), world.deck(), Long.parseLong(decks.read(world.owner(), world.deck()).path("rowVersion").stringValue(null)),
                new DeckCommand(UUID.randomUUID(), title, description));
    }

    void delete(World world) {
        decks.delete(world.owner(), world.deck(), Long.parseLong(decks.read(world.owner(), world.deck()).path("rowVersion").stringValue(null)));
    }

    void hide(World world) {
        transaction.executeWithoutResult(status -> moderation.hide(world.deck(), Instant.now()));
    }

    void unhide(World world) {
        transaction.executeWithoutResult(status -> moderation.unhide(world.deck()));
    }

    UUID publishedRevision(World world) {
        return jdbc.sql("SELECT published_revision_id FROM app_learning.deck_publication WHERE deck_id = :d").param("d", world.deck()).query(UUID.class).single();
    }

    // ---- the jobs ----

    /** A job the test writes itself for {@code deck}; the handler sees exactly what the executor shows it. */
    static Job job(UUID deck, ObjectNode progress, long progressCount) {
        return new Job(UUID.randomUUID(), CatalogOutbox.PROJECT_QUEUE, null, CatalogOutbox.payload(deck), progress, progressCount, 1);
    }

    /** One slice of {@code handler} in its own transaction, as the executor runs it. */
    Slice run(JobHandler handler, Job job) {
        return transaction.execute(status -> handler.run(job));
    }

    /** Runs the projection of {@code deck} to its end, slice after slice, as the executor would; returns the number of slices. */
    int project(CatalogProjector projector, UUID deck) {
        ObjectNode progress = null;
        long count = 0;
        for (int slices = 1; ; slices++) {
            Slice slice = run(projector, job(deck, progress, count));
            if (slice instanceof Slice.Done) return slices;
            Slice.Continue next = (Slice.Continue) slice;
            progress = next.progress();
            count = next.progressCount();
            if (slices > 10_000) throw new AssertionError("the projection does not end");
        }
    }
}
