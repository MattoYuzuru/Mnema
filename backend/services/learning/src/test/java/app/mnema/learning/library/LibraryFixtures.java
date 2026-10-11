package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.StudyFixtures;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.UUID;

import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;

/**
 * Builds decks with content through the production services and moves them through the access levels with the production {@link DeckPublications}.
 * The publication command itself is Share/8: here "publish" sets the published revision of the row to the deck's current head, the one write the
 * test needs from it.
 */
final class LibraryFixtures {
    /** A deck with one material and one FREE_RESPONSE exercise. */
    record Deck(UUID owner, UUID id, StudyFixtures.Material material, UUID exercise) { }

    static final String SECRET_ANSWER = "SECRET-ACCEPTED-ANSWER";
    static final String OBJECTIVE_TITLE = "SECRET-OBJECTIVE-TITLE";
    static final String SECRET_REFERENCE = "SECRET-REFERENCE-TEXT";

    private final DeckService decks;
    private final StudyFixtures study;
    private final DeckPublications publications;
    private final JdbcClient jdbc;

    LibraryFixtures(DeckService decks, ItemService items, ExerciseService exercises, StudySessionService sessions, MediaCatalog media,
                    JdbcClient jdbc, DeckPublications publications) {
        this.decks = decks;
        this.study = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        this.publications = publications;
        this.jdbc = jdbc;
    }

    StudyFixtures study() { return study; }

    Deck deck(UUID owner, String title) {
        UUID id = UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), title, "Описание " + title))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
        StudyFixtures.Material material = study.addMaterial(owner, id, "memory", "forgetting");
        var exercise = study.freeResponse(material, blocks(text("Что такое память?")), blocks(text(SECRET_REFERENCE)), SECRET_ANSWER);
        JsonNode published = study.publish(material, exercise, OBJECTIVE_TITLE);
        return new Deck(owner, id, material, UUID.fromString(published.path("exerciseId").stringValue(null)));
    }

    /** Moves the owner's deck to {@code level} (any current state) and returns its code. */
    String level(Deck deck, DeckVisibility level) {
        Optional<Publication> current = publications.find(deck.owner(), deck.id());
        return publications.setVisibility(deck.owner(), deck.id(), level,
                current.map(Publication::rowVersion).orElse(DeckPublications.UNPUBLISHED)).map(Publication::publicCode).orElse(null);
    }

    /** Publishes the deck's current head as the revision non-owners read. */
    void publishHead(UUID deck) {
        int updated = jdbc.sql("""
                UPDATE app_learning.deck_publication p
                   SET published_revision_id = d.head_revision_id, published_at = statement_timestamp(),
                       row_version = p.row_version + 1, updated_at = statement_timestamp()
                  FROM app_learning.deck d WHERE p.deck_id = d.deck_id AND d.deck_id = :deck
                """).param("deck", deck).update();
        if (updated != 1) throw new IllegalStateException("The deck has no publication row");
    }

    /** {@code level(...)} then {@code publishHead(...)}: a deck readable at the level. */
    String publishAt(Deck deck, DeckVisibility level) {
        String code = level(deck, level);
        publishHead(deck.id());
        return code;
    }
}
