package app.mnema.media.domain.entity;

import app.mnema.media.domain.type.UploadStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MediaUploadEntityTest {

    @Test
    void constructorAndMutatorsExposeAllFields() {
        UUID uploadId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plusSeconds(3600);
        Instant completedAt = createdAt.plusSeconds(10);

        MediaUploadEntity entity = new MediaUploadEntity(
                uploadId,
                mediaId,
                UploadStatus.initiated,
                4096L,
                "application/pdf",
                true,
                3,
                2048L,
                "s3-upload-1",
                createdAt,
                expiresAt,
                completedAt,
                "boom"
        );

        assertThat(entity.getUploadId()).isEqualTo(uploadId);
        assertThat(entity.getMediaId()).isEqualTo(mediaId);
        assertThat(entity.getStatus()).isEqualTo(UploadStatus.initiated);
        assertThat(entity.getExpectedSizeBytes()).isEqualTo(4096L);
        assertThat(entity.getExpectedMimeType()).isEqualTo("application/pdf");
        assertThat(entity.isMultipart()).isTrue();
        assertThat(entity.getPartsCount()).isEqualTo(3);
        assertThat(entity.getPartSizeBytes()).isEqualTo(2048L);
        assertThat(entity.getS3UploadId()).isEqualTo("s3-upload-1");
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(entity.getCompletedAt()).isEqualTo(completedAt);
        assertThat(entity.getErrorMessage()).isEqualTo("boom");

        Instant newExpiresAt = expiresAt.plusSeconds(60);
        entity.setStatus(UploadStatus.completed);
        entity.setExpectedSizeBytes(512L);
        entity.setExpectedMimeType("image/png");
        entity.setMultipart(false);
        entity.setPartsCount(1);
        entity.setPartSizeBytes(512L);
        entity.setS3UploadId(null);
        entity.setExpiresAt(newExpiresAt);
        entity.setCompletedAt(null);
        entity.setErrorMessage(null);

        assertThat(entity.getStatus()).isEqualTo(UploadStatus.completed);
        assertThat(entity.getExpectedSizeBytes()).isEqualTo(512L);
        assertThat(entity.getExpectedMimeType()).isEqualTo("image/png");
        assertThat(entity.isMultipart()).isFalse();
        assertThat(entity.getPartsCount()).isEqualTo(1);
        assertThat(entity.getPartSizeBytes()).isEqualTo(512L);
        assertThat(entity.getS3UploadId()).isNull();
        assertThat(entity.getExpiresAt()).isEqualTo(newExpiresAt);
        assertThat(entity.getCompletedAt()).isNull();
        assertThat(entity.getErrorMessage()).isNull();
    }
}
