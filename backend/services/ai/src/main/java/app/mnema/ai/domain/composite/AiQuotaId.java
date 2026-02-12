package app.mnema.ai.domain.composite;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public class AiQuotaId implements Serializable {
    private UUID userId;
    private LocalDate periodStart;

    public AiQuotaId() {
    }

    public AiQuotaId(UUID userId, LocalDate periodStart) {
        this.userId = userId;
        this.periodStart = periodStart;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(LocalDate periodStart) {
        this.periodStart = periodStart;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        AiQuotaId that = (AiQuotaId) o;
        return Objects.equals(userId, that.userId) && Objects.equals(periodStart, that.periodStart);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, periodStart);
    }
}
