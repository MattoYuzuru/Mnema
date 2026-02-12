package app.mnema.ai.repository;

import app.mnema.ai.domain.entity.AiProviderCredentialEntity;
import app.mnema.ai.domain.type.AiProviderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AiProviderCredentialRepository extends JpaRepository<AiProviderCredentialEntity, UUID> {
    List<AiProviderCredentialEntity> findByUserIdAndStatus(UUID userId, AiProviderStatus status);

    Optional<AiProviderCredentialEntity> findByIdAndUserId(UUID id, UUID userId);

    Optional<AiProviderCredentialEntity> findFirstByUserIdAndProviderAndStatusOrderByCreatedAtAsc(
            UUID userId,
            String provider,
            AiProviderStatus status
    );
}
