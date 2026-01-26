package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.composite.CardTemplateVersionId;
import app.mnema.core.deck.domain.entity.CardTemplateVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CardTemplateVersionRepository extends JpaRepository<CardTemplateVersionEntity, CardTemplateVersionId> {

    Optional<CardTemplateVersionEntity> findTopByTemplateIdOrderByVersionDesc(UUID templateId);

    Optional<CardTemplateVersionEntity> findByTemplateIdAndVersion(UUID templateId, Integer version);

    List<CardTemplateVersionEntity> findByTemplateIdIn(Collection<UUID> templateIds);

    List<CardTemplateVersionEntity> findByTemplateIdOrderByVersionDesc(UUID templateId);
}
