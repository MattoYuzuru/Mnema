package app.mnema.ai.repository;

import app.mnema.ai.domain.composite.AiQuotaId;
import app.mnema.ai.domain.entity.AiQuotaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AiQuotaRepository extends JpaRepository<AiQuotaEntity, AiQuotaId> {
    Optional<AiQuotaEntity> findByUserIdAndPeriodStart(UUID userId, LocalDate periodStart);
}
