package app.mnema.core.review.repository;

import app.mnema.core.review.entity.ReviewUserCardEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReviewUserCardRepository extends JpaRepository<ReviewUserCardEntity, UUID> {

    @Query("""
            select c.userCardId
            from ReviewUserCardEntity c
            join SrCardStateEntity s on s.userCardId = c.userCardId
            where c.userDeckId = :deckId
              and c.userId = :userId
              and c.deleted = false
              and s.suspended = false
              and s.nextReviewAt <= :now
            order by s.nextReviewAt asc
            """)
    List<UUID> findDueCardIds(@Param("userId") UUID userId,
                              @Param("deckId") UUID deckId,
                              @Param("now") Instant now,
                              org.springframework.data.domain.Pageable pageable);

    @Query("""
            select c.userCardId
            from ReviewUserCardEntity c
            left join SrCardStateEntity s on s.userCardId = c.userCardId
            where c.userDeckId = :deckId
              and c.userId = :userId
              and c.deleted = false
              and s.userCardId is null
            order by c.userCardId asc
            """)
    List<UUID> findNewCardIds(@Param("userId") UUID userId,
                              @Param("deckId") UUID deckId,
                              org.springframework.data.domain.Pageable pageable);
}
