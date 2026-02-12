package app.mnema.ai.repository;

import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.type.AiJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AiJobRepository extends JpaRepository<AiJobEntity, UUID> {
    Optional<AiJobEntity> findByJobIdAndUserId(UUID jobId, UUID userId);

    Optional<AiJobEntity> findByRequestId(UUID requestId);

    Optional<AiJobEntity> findByRequestIdAndUserId(UUID requestId, UUID userId);

    List<AiJobEntity> findByStatus(AiJobStatus status);

    Page<AiJobEntity> findByUserIdAndDeckIdOrderByCreatedAtDesc(UUID userId, UUID deckId, Pageable pageable);
}
