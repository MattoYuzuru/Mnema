package app.mnema.media.service;

import app.mnema.media.controller.dto.ResolvedMedia;
import app.mnema.media.domain.entity.MediaAssetEntity;
import app.mnema.media.domain.type.MediaKind;
import app.mnema.media.domain.type.MediaStatus;
import app.mnema.media.service.policy.MediaPolicy;
import app.mnema.media.storage.ObjectStorage;
import app.mnema.media.storage.PresignedUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaResolveCacheTest {

    @Mock
    ObjectStorage storage;

    MediaPolicy policy = new MediaPolicy();

    @Test
    void resolve_buildsPresignedResolvedMedia() {
        MediaResolveCache cache = new MediaResolveCache(storage, policy);
        UUID mediaId = UUID.randomUUID();
        MediaAssetEntity asset = new MediaAssetEntity(
                mediaId,
                UUID.randomUUID(),
                MediaKind.card_image,
                MediaStatus.ready,
                "media/card_image/" + mediaId,
                "image/png",
                42L,
                12,
                100,
                200,
                "image.png",
                Instant.parse("2026-04-07T10:00:00Z"),
                null,
                null
        );
        when(storage.presignGet(asset.getStorageKey(), policy.presignTtl(), "image.png"))
                .thenReturn(new PresignedUrl("https://cdn.example/image", Map.of("x", "1")));

        ResolvedMedia resolved = cache.resolvePublic(asset);

        assertThat(resolved.mediaId()).isEqualTo(mediaId);
        assertThat(resolved.url()).isEqualTo("https://cdn.example/image");
        assertThat(resolved.mimeType()).isEqualTo("image/png");
        assertThat(resolved.sizeBytes()).isEqualTo(42L);
        assertThat(resolved.height()).isEqualTo(200);
        assertThat(resolved.expiresAt()).isAfter(Instant.now().minusSeconds(1));
        verify(storage).presignGet(asset.getStorageKey(), policy.presignTtl(), "image.png");
    }

    @Test
    void resolveInternal_buildsInternalPresignedResolvedMedia() {
        MediaResolveCache cache = new MediaResolveCache(storage, policy);
        UUID mediaId = UUID.randomUUID();
        MediaAssetEntity asset = new MediaAssetEntity(
                mediaId,
                UUID.randomUUID(),
                MediaKind.import_file,
                MediaStatus.ready,
                "media/import_file/" + mediaId,
                "application/zip",
                42L,
                null,
                null,
                null,
                "deck.zip",
                Instant.parse("2026-04-07T10:00:00Z"),
                null,
                null
        );
        when(storage.presignGetInternal(asset.getStorageKey(), policy.presignTtl(), "deck.zip"))
                .thenReturn(new PresignedUrl("http://minio:9000/mnema-bucket/media/import_file/" + mediaId, Map.of()));

        ResolvedMedia resolved = cache.resolveInternal(asset);

        assertThat(resolved.mediaId()).isEqualTo(mediaId);
        assertThat(resolved.url()).startsWith("http://minio:9000/");
        verify(storage).presignGetInternal(asset.getStorageKey(), policy.presignTtl(), "deck.zip");
    }
}
