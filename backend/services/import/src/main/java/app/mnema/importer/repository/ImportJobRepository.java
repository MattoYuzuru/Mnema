package app.mnema.importer.repository;

import app.mnema.importer.domain.ImportJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ImportJobRepository extends JpaRepository<ImportJobEntity, UUID> {
    Optional<ImportJobEntity> findByJobIdAndUserId(UUID jobId, UUID userId);
}
