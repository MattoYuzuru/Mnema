package app.mnema.learning.fork;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The preparing guard (contract {@code contracts/decks/forks.json}): until a copy is materialized every mutation of it and every Study start answer {@code 409 DECK_PREPARING}.
 * The HTTP net is {@link PreparingGuardInterceptor} (every non-GET route under {@code /decks/{deckId}}); the routes that name the deck in their body call {@link #requireReady} from
 * their service; and the database refuses a head write on a preparing copy outside the materialization job (trigger {@code deck_fork_preparing_guard}). The owner predicate is inside
 * the statement: a stranger's deck id is "not preparing" and the route answers its own opaque 404.
 */
@Component
public class ForkGuard {
    private final ForkRepository repository;

    ForkGuard(ForkRepository repository) { this.repository = repository; }

    /** @throws DeckPreparingException when {@code deck} is the actor's live copy and it is not materialized yet */
    public void requireReady(UUID actor, UUID deck) {
        if (repository.preparing(actor, deck)) throw new DeckPreparingException();
    }

    /** Whether {@code deck} is the actor's live copy and it is not materialized yet. */
    public boolean preparing(UUID actor, UUID deck) {
        return repository.preparing(actor, deck);
    }
}
