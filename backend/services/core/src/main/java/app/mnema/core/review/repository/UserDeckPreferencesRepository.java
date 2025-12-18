package app.mnema.core.review.repository;

import app.mnema.core.review.entity.UserDeckPreferencesEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserDeckPreferencesRepository extends JpaRepository<UserDeckPreferencesEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserDeckPreferencesEntity> findByUserDeckId(UUID userDeckId);
}
