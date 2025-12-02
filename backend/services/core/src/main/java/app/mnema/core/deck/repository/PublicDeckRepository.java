package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PublicDeckRepository extends JpaRepository<PublicDeckEntity, app.mnema.core.deck.domain.composite.PublicDeckId> {

    // Все версии автора, если нужно
    Page<PublicDeckEntity> findByAuthorId(UUID authorId, Pageable pageable);

    Optional<PublicDeckEntity> findByDeckIdAndVersion(UUID deckId, Integer version);

    Optional<PublicDeckEntity> findTopByDeckIdOrderByVersionDesc(UUID deckId);

    // Публичный каталог: только последняя версия каждой публичной и листингуемой колоды
    @Query("""
            select d
            from PublicDeckEntity d
            where d.publicFlag = true
              and d.listed = true
              and d.version = (
                select max(d2.version)
                from PublicDeckEntity d2
                where d2.deckId = d.deckId
              )
            """)
    Page<PublicDeckEntity> findLatestPublicVisibleDecks(Pageable pageable);

    // Последняя версия конкретной колоды
    @Query("""
            select d
            from PublicDeckEntity d
            where d.deckId = :deckId
              and d.version = (
                select max(d2.version)
                from PublicDeckEntity d2
                where d2.deckId = :deckId
              )
            """)
    Optional<PublicDeckEntity> findLatestByDeckId(UUID deckId);
}
