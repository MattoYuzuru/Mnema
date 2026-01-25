package app.mnema.ai.repository;

import app.mnema.ai.domain.composite.AiJobStepId;
import app.mnema.ai.domain.entity.AiJobStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiJobStepRepository extends JpaRepository<AiJobStepEntity, AiJobStepId> {
}
