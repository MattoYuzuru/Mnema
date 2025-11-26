package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.UserDeckEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserDeckRepository extends JpaRepository<UserDeckEntity, UUID> {

    Page<UserDeckEntity> findByUserIdAndArchivedFalse(UUID userId, Pageable pageable);

    // findByUserDeckId можно оставить, но есть findById()
    UserDeckEntity findByUserDeckId(UUID userDeckId);
}
