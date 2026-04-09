package app.mnema.ai.repository;

import app.mnema.ai.domain.composite.AiJobStepId;
import app.mnema.ai.domain.entity.AiJobStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AiJobStepRepository extends JpaRepository<AiJobStepEntity, AiJobStepId> {
    List<AiJobStepEntity> findByJobIdOrderByStartedAtAscStepNameAsc(UUID jobId);

    void deleteByJobId(UUID jobId);
}
