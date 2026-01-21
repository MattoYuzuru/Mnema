package app.mnema.core.review.repository;

import app.mnema.core.review.entity.ReviewUserCardEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewUserCardRepository extends JpaRepository<ReviewUserCardEntity, UUID> {

    interface DeckCount {
        UUID getUserDeckId();
        long getCount();
    }

    @Query("""
        select distinct c.userDeckId
        from ReviewUserCardEntity c
        join UserDeckEntity d on d.userDeckId = c.userDeckId
        where d.userId = :userId
          and d.archived = false
          and c.deleted = false
        """)
    List<UUID> findActiveDeckIds(@Param("userId") UUID userId);

    @Query("""
            select c.userCardId
            from ReviewUserCardEntity c
            join SrCardStateEntity s on s.userCardId = c.userCardId
            where c.userDeckId = :deckId
              and c.userId = :userId
              and c.deleted = false
              and s.suspended = false
              and s.nextReviewAt <= :until
            order by s.nextReviewAt asc, c.userCardId asc
            """)
    List<UUID> findDueCardIds(@Param("userId") UUID userId,
                              @Param("deckId") UUID deckId,
                              @Param("until") Instant until,
                              org.springframework.data.domain.Pageable pageable);

    @Query(value = """
            select uc.user_card_id
            from app_core.user_cards uc
            left join app_core.sr_card_states s on s.user_card_id = uc.user_card_id
            left join app_core.public_cards pc on pc.card_id = uc.public_card_id
            where uc.subscription_id = :deckId
              and uc.user_id = :userId
              and uc.is_deleted = false
              and s.user_card_id is null
            order by
              case when pc.order_index is null then 1 else 0 end,
              pc.order_index asc,
              uc.created_at asc,
              uc.user_card_id asc
            """, nativeQuery = true)
    List<UUID> findNewCardIds(@Param("userId") UUID userId,
                              @Param("deckId") UUID deckId,
                              org.springframework.data.domain.Pageable pageable);

    @Query("""
        select count(c.userCardId)
        from ReviewUserCardEntity c
        join SrCardStateEntity s on s.userCardId = c.userCardId
        where c.userDeckId = :deckId
          and c.userId = :userId
          and c.deleted = false
          and s.suspended = false
          and s.nextReviewAt <= :until
        """)
    long countDue(@Param("userId") UUID userId,
                  @Param("deckId") UUID deckId,
                  @Param("until") Instant until);

    @Query("""
        select count(c.userCardId)
        from ReviewUserCardEntity c
        left join SrCardStateEntity s on s.userCardId = c.userCardId
        where c.userDeckId = :deckId
          and c.userId = :userId
          and c.deleted = false
          and s.userCardId is null
        """)
    long countNew(@Param("userId") UUID userId,
                  @Param("deckId") UUID deckId);

    @Query("""
        select c.userDeckId as userDeckId, count(c.userCardId) as count
        from ReviewUserCardEntity c
        join SrCardStateEntity s on s.userCardId = c.userCardId
        where c.userId = :userId
          and c.deleted = false
          and s.suspended = false
          and s.nextReviewAt <= :until
          and c.userDeckId in :deckIds
        group by c.userDeckId
        """)
    List<DeckCount> countDueByDeck(@Param("userId") UUID userId,
                                   @Param("deckIds") List<UUID> deckIds,
                                   @Param("until") Instant until);

    @Query("""
        select c.userDeckId as userDeckId, count(c.userCardId) as count
        from ReviewUserCardEntity c
        left join SrCardStateEntity s on s.userCardId = c.userCardId
        where c.userId = :userId
          and c.deleted = false
          and s.userCardId is null
          and c.userDeckId in :deckIds
        group by c.userDeckId
        """)
    List<DeckCount> countNewByDeck(@Param("userId") UUID userId,
                                   @Param("deckIds") List<UUID> deckIds);

    @Query("""
        select count(c.userCardId)
        from ReviewUserCardEntity c
        where c.userDeckId = :deckId
          and c.userId = :userId
          and c.deleted = false
        """)
    long countActive(@Param("userId") UUID userId,
                     @Param("deckId") UUID deckId);

    Optional<ReviewUserCardEntity> findByUserCardIdAndUserId(UUID userCardId, UUID userId);
}
