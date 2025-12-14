package app.mnema.core.deck.repository;


import app.mnema.core.deck.domain.composite.PublicCardId;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Repository
public interface PublicCardRepository extends JpaRepository<PublicCardEntity, PublicCardId> {

    List<PublicCardEntity> findByDeckIdAndDeckVersion(UUID deckId, Integer deckVersion);

    Page<PublicCardEntity> findByDeckIdAndDeckVersionOrderByOrderIndex(
            UUID deckId,
            Integer deckVersion,
            Pageable pageable
    );

    Optional<PublicCardEntity> findByCardId(UUID cardId);

    long countByDeckIdAndDeckVersion(UUID deckId, Integer deckVersion);

    List<PublicCardEntity> findByCardIdIn(List<UUID> cardIds);
}
