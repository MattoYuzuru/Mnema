package app.mnema.media.domain.entity;

import app.mnema.media.domain.type.MediaKind;
import app.mnema.media.domain.type.MediaStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "media_assets", schema = "app_media")
public class MediaAssetEntity {

    @Id
    @Column(name = "media_id", nullable = false)
    private UUID mediaId;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "kind", columnDefinition = "media_kind", nullable = false)
    private MediaKind kind;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "media_status", nullable = false)
    private MediaStatus status;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "original_file_name")
    private String originalFileName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected MediaAssetEntity() {
    }

    public MediaAssetEntity(UUID mediaId,
                            UUID ownerUserId,
                            MediaKind kind,
                            MediaStatus status,
                            String storageKey,
                            String mimeType,
                            Long sizeBytes,
                            Integer durationSeconds,
                            Integer width,
                            Integer height,
                            String originalFileName,
                            Instant createdAt,
                            Instant updatedAt,
                            Instant deletedAt) {
        this.mediaId = mediaId;
        this.ownerUserId = ownerUserId;
        this.kind = kind;
        this.status = status;
        this.storageKey = storageKey;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.durationSeconds = durationSeconds;
        this.width = width;
        this.height = height;
        this.originalFileName = originalFileName;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }

    public UUID getMediaId() {
        return mediaId;
    }

    public void setMediaId(UUID mediaId) {
        this.mediaId = mediaId;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(UUID ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public MediaKind getKind() {
        return kind;
    }

    public void setKind(MediaKind kind) {
        this.kind = kind;
    }

    public MediaStatus getStatus() {
        return status;
    }

    public void setStatus(MediaStatus status) {
        this.status = status;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
