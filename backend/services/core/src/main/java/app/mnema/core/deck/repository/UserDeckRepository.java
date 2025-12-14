package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.UserDeckEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Repository
public interface UserDeckRepository extends JpaRepository<UserDeckEntity, UUID> {

    Page<UserDeckEntity> findByUserIdAndArchivedFalse(UUID userId, Pageable pageable);

    Optional<UserDeckEntity> findByUserIdAndPublicDeckId(UUID userId, UUID publicDeckId);

    List<UserDeckEntity> findByUserIdAndArchivedTrue(UUID userId);

}
