package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CardTemplateRepository extends JpaRepository<CardTemplateEntity, UUID> {

    Page<CardTemplateEntity> findByIsPublicTrue(Pageable pageable);

    Optional<CardTemplateEntity> findByOwnerIdAndTemplateId(UUID ownerId, UUID templateId);
}
