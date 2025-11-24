package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.UserDeckEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserDeckRepository extends JpaRepository<UserDeckEntity, UUID> {
    List<UserDeckEntity> findByUserIdAndArchivedFalse(UUID userId);
}
