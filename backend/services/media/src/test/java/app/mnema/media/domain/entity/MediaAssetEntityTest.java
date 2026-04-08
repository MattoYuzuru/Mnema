package app.mnema.media.domain.entity;

import app.mnema.media.domain.type.MediaKind;
import app.mnema.media.domain.type.MediaStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MediaAssetEntityTest {

    @Test
    void constructorAndMutatorsExposeAllFields() {
        UUID mediaId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Instant updatedAt = createdAt.plusSeconds(5);
        Instant deletedAt = createdAt.plusSeconds(10);

        MediaAssetEntity entity = new MediaAssetEntity(
                mediaId,
                ownerId,
                MediaKind.card_audio,
                MediaStatus.ready,
                "media/card_audio/" + mediaId,
                "audio/mpeg",
                1024L,
                42,
                640,
                480,
                "audio.mp3",
                createdAt,
                updatedAt,
                deletedAt
        );

        assertThat(entity.getMediaId()).isEqualTo(mediaId);
        assertThat(entity.getOwnerUserId()).isEqualTo(ownerId);
        assertThat(entity.getKind()).isEqualTo(MediaKind.card_audio);
        assertThat(entity.getStatus()).isEqualTo(MediaStatus.ready);
        assertThat(entity.getStorageKey()).contains("card_audio");
        assertThat(entity.getMimeType()).isEqualTo("audio/mpeg");
        assertThat(entity.getSizeBytes()).isEqualTo(1024L);
        assertThat(entity.getDurationSeconds()).isEqualTo(42);
        assertThat(entity.getWidth()).isEqualTo(640);
        assertThat(entity.getHeight()).isEqualTo(480);
        assertThat(entity.getOriginalFileName()).isEqualTo("audio.mp3");
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(entity.getDeletedAt()).isEqualTo(deletedAt);

        UUID newOwner = UUID.randomUUID();
        Instant newUpdated = updatedAt.plusSeconds(5);
        entity.setOwnerUserId(newOwner);
        entity.setKind(MediaKind.deck_icon);
        entity.setStatus(MediaStatus.deleted);
        entity.setStorageKey("media/deck_cover/new");
        entity.setMimeType("image/webp");
        entity.setSizeBytes(2048L);
        entity.setDurationSeconds(7);
        entity.setWidth(1200);
        entity.setHeight(630);
        entity.setOriginalFileName("cover.webp");
        entity.setUpdatedAt(newUpdated);
        entity.setDeletedAt(null);

        assertThat(entity.getOwnerUserId()).isEqualTo(newOwner);
        assertThat(entity.getKind()).isEqualTo(MediaKind.deck_icon);
        assertThat(entity.getStatus()).isEqualTo(MediaStatus.deleted);
        assertThat(entity.getStorageKey()).isEqualTo("media/deck_cover/new");
        assertThat(entity.getMimeType()).isEqualTo("image/webp");
        assertThat(entity.getSizeBytes()).isEqualTo(2048L);
        assertThat(entity.getDurationSeconds()).isEqualTo(7);
        assertThat(entity.getWidth()).isEqualTo(1200);
        assertThat(entity.getHeight()).isEqualTo(630);
        assertThat(entity.getOriginalFileName()).isEqualTo("cover.webp");
        assertThat(entity.getUpdatedAt()).isEqualTo(newUpdated);
        assertThat(entity.getDeletedAt()).isNull();
    }
}
