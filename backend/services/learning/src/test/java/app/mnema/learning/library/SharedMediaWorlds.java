package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.MediaFixtures;
import app.mnema.learning.support.MediaFixtures.Variants;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.SharedScopeFixture.Material;
import app.mnema.learning.support.StudyFixtures;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static app.mnema.learning.support.StudyFixtures.audio;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;

/**
 * Builds the author's side of the Share/9 tests through the production services: a deck with a material that shows an image and an exercise that plays a sound,
 * both READY with derived variants, published at the requested level. The object store is never involved (the keys are fake and only the presigner reads them).
 */
final class SharedMediaWorlds {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The author's published deck and what it shows. */
    record World(UUID deck, String code, UUID image, Variants imageVariants, UUID sound, Variants soundVariants, Material material, UUID exercise) { }

    private final UUID author;
    private final PublicationService publication;
    private final DeckPublications deckPublications;
    private final DeckService decks;
    private final ItemService items;
    private final JdbcClient jdbc;
    private final SharedScopeFixture shared;
    private final StudyFixtures study;

    SharedMediaWorlds(UUID author, PublicationService publication, DeckPublications deckPublications, DeckService decks, ItemService items,
                      ExerciseService exercises, StudySessionService sessions, MediaCatalog media, ImmutableStorage storage, JdbcClient jdbc,
                      PlatformTransactionManager transactions) {
        this.author = author;
        this.publication = publication;
        this.deckPublications = deckPublications;
        this.decks = decks;
        this.items = items;
        this.jdbc = jdbc;
        this.shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
        this.study = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
    }

    SharedScopeFixture shared() { return shared; }

    StudyFixtures study() { return study; }

    /** A deck published at LINK and moved to {@code level}; {@code grantee} (may be null) holds a grant. */
    World world(String level, UUID grantee) {
        UUID image = study.readyAsset(author, "image/png");
        Variants imageVariants = MediaFixtures.variants(jdbc, image, "image/webp");
        UUID sound = study.readyAsset(author, "audio/mpeg");
        Variants soundVariants = MediaFixtures.variants(jdbc, sound, "audio/mp4", false);
        UUID deck = shared.deck(author, "Медиа " + level);
        Material material = shared.create(author, deck, MediaFixtures.imageDocument(image));
        StudyFixtures.Material host = study.addMaterial(author, deck, "host", "text");
        JsonNode published = study.publish(host, study.freeResponse(host, blocks(audio(sound, "Звук", null), text("Что слышно?")), blocks(), "ответ"));
        save(deck, "LINK", true);
        if (!level.equals("LINK")) save(deck, level, false);
        if (grantee != null) deckPublications.grant(author, deck, grantee, GrantRole.VIEWER);
        return new World(deck, code(deck), image, imageVariants, sound, soundVariants, material, UUID.fromString(published.path("exerciseId").stringValue(null)));
    }

    /** Moves the deck to {@code level}; {@code publishHead} also publishes the head the author sees. */
    void save(UUID deck, String level, boolean publishHead) {
        var body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("visibility", level);
        body.putObject("metadata").put("topicId", "japanese").put("contentLanguage", "ru").put("targetLanguage", "ja").put("level", "A2").putArray("tags").add("jlpt n5");
        body.put("requestsEnabled", true);
        if (publishHead) body.putObject("publish").put("expectedHeadRevisionId", decks.read(author, deck).path("revisionId").stringValue(null)).putNull("releaseNote");
        else body.putNull("publish");
        long version = Long.parseLong(publication.read(author, deck, true).path("rowVersion").stringValue(null));
        publication.save(author, deck, version, PublicationCommand.read(new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8))), true);
    }

    String code(UUID deck) {
        return jdbc.sql("SELECT public_code FROM app_learning.deck_publication WHERE deck_id = :deck").param("deck", deck).query(String.class).single();
    }
}
