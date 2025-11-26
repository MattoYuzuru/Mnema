package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.UserCardEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserCardRepository extends JpaRepository<UserCardEntity, UUID> {
    List<UserCardEntity> findByUserDeck_UserDeckIdAndDeletedFalseAndSuspendedFalseOrderByCreatedAtAsc(
            UUID userDeckId,
            Pageable pageable
    );

    @Query("""
            select c
            from UserCardEntity c
            join SrCardStateEntity s on s.userCardId = c.userCardId
            where c.userDeck.userDeckId = :deckId
                and c.deleted = false
                and c.suspended = false
                and c.nextReviewAt <= :now
            order by s.nextReviewAt asc
            """)
    List<UserCardEntity> findDueCardsForDeck(UUID deckId, Instant now);
}
