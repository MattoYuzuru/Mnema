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
import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface AiJobRepository extends JpaRepository<AiJobEntity, UUID> {
    Optional<AiJobEntity> findByJobIdAndUserId(UUID jobId, UUID userId);

    Optional<AiJobEntity> findByRequestId(UUID requestId);

    Optional<AiJobEntity> findByRequestIdAndUserId(UUID requestId, UUID userId);

    List<AiJobEntity> findByStatus(AiJobStatus status);

    Page<AiJobEntity> findByUserIdAndDeckIdOrderByCreatedAtDesc(UUID userId, UUID deckId, Pageable pageable);

    @Query("""
            select count(j)
            from AiJobEntity j
            where j.jobId <> :jobId
              and j.status in :statuses
              and j.createdAt < :createdAt
              and (j.nextRunAt is null or j.nextRunAt <= :now)
            """)
    long countRunnableJobsAhead(@Param("jobId") UUID jobId,
                                @Param("statuses") List<AiJobStatus> statuses,
                                @Param("createdAt") Instant createdAt,
                                @Param("now") Instant now);

    @Query("""
            select count(j)
            from AiJobEntity j
            where j.status in :statuses
              and (j.nextRunAt is null or j.nextRunAt <= :now)
            """)
    long countRunnableJobs(@Param("statuses") List<AiJobStatus> statuses,
                           @Param("now") Instant now);
}
