package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.FieldTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FieldTemplateRepository extends JpaRepository<FieldTemplateEntity, UUID> {

    List<FieldTemplateEntity> findByTemplateId(UUID templateId);

    List<FieldTemplateEntity> findByTemplateIdIn(Collection<UUID> templateIds);

    List<FieldTemplateEntity> findByTemplateIdOrderByOrderIndexAsc(UUID templateId);

    Optional<FieldTemplateEntity> findByFieldIdAndTemplateId(UUID fieldId, UUID templateId);
}
