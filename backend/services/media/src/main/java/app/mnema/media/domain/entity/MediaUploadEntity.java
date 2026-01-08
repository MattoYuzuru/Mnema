package app.mnema.media.domain.entity;

import app.mnema.media.domain.type.UploadStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "media_uploads", schema = "app_media")
public class MediaUploadEntity {

    @Id
    @Column(name = "upload_id", nullable = false)
    private UUID uploadId;

    @Column(name = "media_id", nullable = false)
    private UUID mediaId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "upload_status", nullable = false)
    private UploadStatus status;

    @Column(name = "expected_size_bytes", nullable = false)
    private Long expectedSizeBytes;

    @Column(name = "expected_mime_type", nullable = false)
    private String expectedMimeType;

    @Column(name = "multipart", nullable = false)
    private boolean multipart;

    @Column(name = "parts_count")
    private Integer partsCount;

    @Column(name = "part_size_bytes")
    private Long partSizeBytes;

    @Column(name = "s3_upload_id")
    private String s3UploadId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message")
    private String errorMessage;

    protected MediaUploadEntity() {
    }

    public MediaUploadEntity(UUID uploadId,
                             UUID mediaId,
                             UploadStatus status,
                             Long expectedSizeBytes,
                             String expectedMimeType,
                             boolean multipart,
                             Integer partsCount,
                             Long partSizeBytes,
                             String s3UploadId,
                             Instant createdAt,
                             Instant expiresAt,
                             Instant completedAt,
                             String errorMessage) {
        this.uploadId = uploadId;
        this.mediaId = mediaId;
        this.status = status;
        this.expectedSizeBytes = expectedSizeBytes;
        this.expectedMimeType = expectedMimeType;
        this.multipart = multipart;
        this.partsCount = partsCount;
        this.partSizeBytes = partSizeBytes;
        this.s3UploadId = s3UploadId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.completedAt = completedAt;
        this.errorMessage = errorMessage;
    }

    public UUID getUploadId() {
        return uploadId;
    }

    public void setUploadId(UUID uploadId) {
        this.uploadId = uploadId;
    }

    public UUID getMediaId() {
        return mediaId;
    }

    public void setMediaId(UUID mediaId) {
        this.mediaId = mediaId;
    }

    public UploadStatus getStatus() {
        return status;
    }

    public void setStatus(UploadStatus status) {
        this.status = status;
    }

    public Long getExpectedSizeBytes() {
        return expectedSizeBytes;
    }

    public void setExpectedSizeBytes(Long expectedSizeBytes) {
        this.expectedSizeBytes = expectedSizeBytes;
    }

    public String getExpectedMimeType() {
        return expectedMimeType;
    }

    public void setExpectedMimeType(String expectedMimeType) {
        this.expectedMimeType = expectedMimeType;
    }

    public boolean isMultipart() {
        return multipart;
    }

    public void setMultipart(boolean multipart) {
        this.multipart = multipart;
    }

    public Integer getPartsCount() {
        return partsCount;
    }

    public void setPartsCount(Integer partsCount) {
        this.partsCount = partsCount;
    }

    public Long getPartSizeBytes() {
        return partSizeBytes;
    }

    public void setPartSizeBytes(Long partSizeBytes) {
        this.partSizeBytes = partSizeBytes;
    }

    public String getS3UploadId() {
        return s3UploadId;
    }

    public void setS3UploadId(String s3UploadId) {
        this.s3UploadId = s3UploadId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
