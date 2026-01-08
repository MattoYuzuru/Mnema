package app.mnema.media.repository;

import app.mnema.media.domain.entity.MediaAssetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MediaAssetRepository extends JpaRepository<MediaAssetEntity, UUID> {
    List<MediaAssetEntity> findByMediaIdIn(List<UUID> mediaIds);
}
