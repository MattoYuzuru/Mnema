package app.mnema.core.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "moderation_reports", schema = "app_core")
public class ModerationReportEntity {

    @Id
    @Column(name = "report_id", nullable = false, updatable = false)
    private UUID reportId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32)
    private ModerationReportTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "target_parent_id")
    private UUID targetParentId;

    @Column(name = "target_title", nullable = false, length = 160)
    private String targetTitle;

    @Column(name = "content_owner_id", nullable = false)
    private UUID contentOwnerId;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Column(name = "reporter_username", nullable = false, length = 80)
    private String reporterUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 48)
    private ModerationReportReason reason;

    @Column(name = "details", length = 500)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ModerationReportStatus status = ModerationReportStatus.OPEN;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by_user_id")
    private UUID closedByUserId;

    @Column(name = "closed_by_username", length = 80)
    private String closedByUsername;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    protected ModerationReportEntity() {
    }

    public ModerationReportEntity(UUID reportId,
                                  ModerationReportTargetType targetType,
                                  UUID targetId,
                                  UUID targetParentId,
                                  String targetTitle,
                                  UUID contentOwnerId,
                                  UUID reporterId,
                                  String reporterUsername,
                                  ModerationReportReason reason,
                                  String details) {
        this.reportId = reportId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetParentId = targetParentId;
        this.targetTitle = targetTitle;
        this.contentOwnerId = contentOwnerId;
        this.reporterId = reporterId;
        this.reporterUsername = reporterUsername;
        this.reason = reason;
        this.details = details;
        this.status = ModerationReportStatus.OPEN;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (reportId == null) {
            reportId = UUID.randomUUID();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getReportId() {
        return reportId;
    }

    public ModerationReportTargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public UUID getTargetParentId() {
        return targetParentId;
    }

    public String getTargetTitle() {
        return targetTitle;
    }

    public UUID getContentOwnerId() {
        return contentOwnerId;
    }

    public UUID getReporterId() {
        return reporterId;
    }

    public String getReporterUsername() {
        return reporterUsername;
    }

    public ModerationReportReason getReason() {
        return reason;
    }

    public String getDetails() {
        return details;
    }

    public ModerationReportStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public UUID getClosedByUserId() {
        return closedByUserId;
    }

    public String getClosedByUsername() {
        return closedByUsername;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void close(UUID adminId, String adminUsername, String resolutionNote) {
        this.status = ModerationReportStatus.CLOSED;
        this.closedAt = Instant.now();
        this.closedByUserId = adminId;
        this.closedByUsername = adminUsername;
        this.resolutionNote = resolutionNote;
    }
}
