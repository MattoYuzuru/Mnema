package app.mnema.core.deck.repository;


import app.mnema.core.deck.domain.composite.PublicDeckId;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PublicDeckRepository extends JpaRepository<PublicDeckEntity, PublicDeckId> {
    List<PublicDeckEntity> findByAuthorId(UUID authorId);

    Optional<PublicDeckEntity> findByDeckIdAndVersion(UUID deckId, Integer version);

    Optional<PublicDeckEntity> findTopByDeckIdOrderByVersionDesc(UUID deckId);

    Page<PublicDeckEntity> findByPublicFlagTrueAndListedTrue(Pageable pageable);

}
