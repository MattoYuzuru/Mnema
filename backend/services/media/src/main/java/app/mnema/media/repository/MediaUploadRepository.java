package app.mnema.media.repository;

import app.mnema.media.domain.entity.MediaUploadEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MediaUploadRepository extends JpaRepository<MediaUploadEntity, UUID> {
    Optional<MediaUploadEntity> findByUploadId(UUID uploadId);
}
