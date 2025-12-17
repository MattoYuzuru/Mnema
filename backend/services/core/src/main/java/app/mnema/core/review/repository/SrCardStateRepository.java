package app.mnema.core.review.repository;

import app.mnema.core.review.entity.SrCardStateEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SrCardStateRepository extends JpaRepository<SrCardStateEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SrCardStateEntity s where s.userCardId = :id")
    Optional<SrCardStateEntity> findByIdForUpdate(@Param("id") UUID id);
}
