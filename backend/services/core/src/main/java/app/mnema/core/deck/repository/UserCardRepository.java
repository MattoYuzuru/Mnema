package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.UserCardEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserCardRepository extends JpaRepository<UserCardEntity, UUID> {
    Page<UserCardEntity> findByUserDeckIdAndDeletedFalseOrderByCreatedAtAsc(
            UUID userDeckId,
            Pageable pageable
    );

    long countByUserDeckIdAndDeletedFalse(UUID userDeckId);

    List<UserCardEntity> findByUserDeckId(UUID userDeckId);
}
