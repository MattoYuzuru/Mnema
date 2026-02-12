package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.composite.DeckUpdateSessionId;
import app.mnema.core.deck.domain.entity.DeckUpdateSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeckUpdateSessionRepository extends JpaRepository<DeckUpdateSessionEntity, DeckUpdateSessionId> {
    Optional<DeckUpdateSessionEntity> findByDeckIdAndOperationId(UUID deckId, UUID operationId);
}
