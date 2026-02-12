package app.mnema.ai.repository;

import app.mnema.ai.domain.entity.AiUsageLedgerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiUsageLedgerRepository extends JpaRepository<AiUsageLedgerEntity, Long> {
}
