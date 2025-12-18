package app.mnema.core.review.repository;

import app.mnema.core.review.entity.SrCardStateEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface SrCardStateRepository extends JpaRepository<SrCardStateEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SrCardStateEntity s where s.userCardId = :id")
    Optional<SrCardStateEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        select count(s.userCardId)
        from SrCardStateEntity s
        join ReviewUserCardEntity c on c.userCardId = s.userCardId
        where c.userDeckId = :deckId
          and c.userId = :userId
          and c.deleted = false
        """)
    long countTrackedCards(@Param("userId") UUID userId,
                           @Param("deckId") UUID deckId);

    @Query("""
        select count(s.userCardId)
        from SrCardStateEntity s
        join ReviewUserCardEntity c on c.userCardId = s.userCardId
        where c.userDeckId = :deckId
          and c.userId = :userId
          and c.deleted = false
          and (s.algorithmId is null or s.algorithmId <> :algorithmId)
        """)
    long countPendingMigration(@Param("userId") UUID userId,
                               @Param("deckId") UUID deckId,
                               @Param("algorithmId") String algorithmId);
}
