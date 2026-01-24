package app.mnema.ai.domain.entity;

import app.mnema.ai.domain.composite.AiQuotaId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "ai_quota", schema = "app_ai")
@IdClass(AiQuotaId.class)
public class AiQuotaEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "tokens_limit")
    private Integer tokensLimit;

    @Column(name = "tokens_used", nullable = false)
    private Integer tokensUsed;

    @Column(name = "cost_limit")
    private BigDecimal costLimit;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AiQuotaEntity() {
    }

    public AiQuotaEntity(UUID userId,
                         LocalDate periodStart,
                         Integer tokensLimit,
                         Integer tokensUsed,
                         BigDecimal costLimit,
                         Instant updatedAt) {
        this.userId = userId;
        this.periodStart = periodStart;
        this.tokensLimit = tokensLimit;
        this.tokensUsed = tokensUsed;
        this.costLimit = costLimit;
        this.updatedAt = updatedAt;
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

    public Integer getTokensLimit() {
        return tokensLimit;
    }

    public void setTokensLimit(Integer tokensLimit) {
        this.tokensLimit = tokensLimit;
    }

    public Integer getTokensUsed() {
        return tokensUsed;
    }

    public void setTokensUsed(Integer tokensUsed) {
        this.tokensUsed = tokensUsed;
    }

    public BigDecimal getCostLimit() {
        return costLimit;
    }

    public void setCostLimit(BigDecimal costLimit) {
        this.costLimit = costLimit;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
