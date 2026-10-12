package app.mnema.learning.community;

import app.mnema.learning.library.DeckVisibility;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.jobs.LearningJobs;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * The write side of the catalogue projection (community decks architecture sections 3 and 10): what a command that can change whether, or how, a deck is in
 * the catalogue does in its OWN transaction. It never touches {@code public_deck}; it only says "look at this deck again", as a job row, so the projection is never built in a
 * publication transaction (that would be O(N) against bounded transactions) and a change is never lost: the job exists exactly when the change committed.
 *
 * <p>Two things can change the catalogue entry of a deck. A change of the publication (a level, the published revision, the metadata, the moderation flag) appends one
 * append-only {@code deck_publication_event} row (the journal that also holds {@code first_listed_at}) AND enqueues {@code catalog.project}, in
 * {@link #publicationChanged}. The deletion of a deck leaves no journal row (the deck row says it is deleted) and enqueues only when the deck has a row to remove,
 * in {@link #deckDeleted}.
 *
 * <p><b>The job key is unique per change</b> ({@code deckId:eventId}, or {@code deckId:deleted}), because the idempotency horizon of a job is its retention (7 days): a key
 * that repeated would be swallowed by an earlier finished job and the new change would never be looked at. The job reads the CURRENT state of the deck, never the event, so
 * it does not matter in which order, or how often, the jobs of one deck run.
 */
@Component
public class CatalogOutbox {
    /** The queue of the projector. */
    public static final String PROJECT_QUEUE = "catalog.project";
    /** The queue of the rebuild (the backfill of V67 and the repair tool). */
    public static final String REBUILD_QUEUE = "catalog.rebuild";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final LearningJobs jobs;

    CatalogOutbox(JdbcClient jdbc, LearningJobs jobs) {
        this.jdbc = jdbc;
        this.jobs = jobs;
    }

    /**
     * Appends the outbox row of a publication change and enqueues the projection of the deck, in the caller's transaction.
     *
     * @param revision the published revision as of this change (never null: a deck without a published revision has nothing to project)
     * @param visibility the level the deck has after the change
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publicationChanged(UUID deck, UUID revision, DeckVisibility visibility) {
        UuidPolicy.requireEntityId(deck, "deckId");
        UuidPolicy.requireEntityId(revision, "revisionId");
        UUID event = jdbc.sql("""
                INSERT INTO app_learning.deck_publication_event(deck_id, published_revision_id, visibility, occurred_at)
                VALUES (:deck, :revision, :visibility, statement_timestamp())
                RETURNING event_id
                """).param("deck", deck).param("revision", revision).param("visibility", visibility.name()).query(UUID.class).single();
        jobs.enqueue(PROJECT_QUEUE, deck + ":" + event, null, payload(deck));
    }

    /**
     * Called after a deck was tombstoned, in the same transaction. Enqueues the projection only when the catalogue holds a row of the deck (a private deck, which is
     * almost every deck, costs one primary-key probe). The projector locks the deck row, so a projection running at this moment either sees the deletion or has
     * committed its row before this statement reads it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deckDeleted(UUID deck) {
        UuidPolicy.requireEntityId(deck, "deckId");
        boolean listedSomewhere = jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_learning.public_deck WHERE deck_id = :deck)").param("deck", deck)
                .query(Boolean.class).single();
        if (listedSomewhere) jobs.enqueue(PROJECT_QUEUE, deck + ":deleted", null, payload(deck));
    }

    static ObjectNode payload(UUID deck) {
        return JSON.createObjectNode().put("deckId", deck.toString());
    }
}
