package app.mnema.ai.repository;

import app.mnema.ai.domain.entity.AiUsageLedgerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AiUsageLedgerRepository extends JpaRepository<AiUsageLedgerEntity, Long> {
    List<AiUsageLedgerEntity> findByJobIdInOrderByCreatedAtDesc(Collection<UUID> jobIds);
}
