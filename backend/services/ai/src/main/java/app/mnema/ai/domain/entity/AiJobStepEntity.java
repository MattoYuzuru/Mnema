package app.mnema.ai.domain.entity;

import app.mnema.ai.domain.composite.AiJobStepId;
import app.mnema.ai.domain.type.AiJobStepStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_job_steps", schema = "app_ai")
@IdClass(AiJobStepId.class)
public class AiJobStepEntity {

    @Id
    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Id
    @Column(name = "step_name", nullable = false)
    private String stepName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "ai_job_step_status", nullable = false)
    private AiJobStepStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "error_summary")
    private String errorSummary;

    public AiJobStepEntity() {
    }

    public AiJobStepEntity(UUID jobId,
                           String stepName,
                           AiJobStepStatus status,
                           Instant startedAt,
                           Instant endedAt,
                           String errorSummary) {
        this.jobId = jobId;
        this.stepName = stepName;
        this.status = status;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.errorSummary = errorSummary;
    }

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }

    public AiJobStepStatus getStatus() {
        return status;
    }

    public void setStatus(AiJobStepStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public void setErrorSummary(String errorSummary) {
        this.errorSummary = errorSummary;
    }
}
