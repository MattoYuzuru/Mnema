package app.mnema.ai.domain.composite;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class AiJobStepId implements Serializable {
    private UUID jobId;
    private String stepName;

    public AiJobStepId() {
    }

    public AiJobStepId(UUID jobId, String stepName) {
        this.jobId = jobId;
        this.stepName = stepName;
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

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        AiJobStepId that = (AiJobStepId) o;
        return Objects.equals(jobId, that.jobId) && Objects.equals(stepName, that.stepName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobId, stepName);
    }
}
