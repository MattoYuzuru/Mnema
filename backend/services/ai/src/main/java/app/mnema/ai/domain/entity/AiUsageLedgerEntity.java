package app.mnema.ai.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_usage_ledger", schema = "app_ai")
public class AiUsageLedgerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "provider")
    private String provider;

    @Column(name = "model")
    private String model;

    @Column(name = "tokens_in")
    private Integer tokensIn;

    @Column(name = "tokens_out")
    private Integer tokensOut;

    @Column(name = "cost_estimate")
    private BigDecimal costEstimate;

    @Column(name = "prompt_hash")
    private String promptHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AiUsageLedgerEntity() {
    }

    public AiUsageLedgerEntity(Long id,
                               UUID requestId,
                               UUID jobId,
                               UUID userId,
                               String provider,
                               String model,
                               Integer tokensIn,
                               Integer tokensOut,
                               BigDecimal costEstimate,
                               String promptHash,
                               Instant createdAt) {
        this.id = id;
        this.requestId = requestId;
        this.jobId = jobId;
        this.userId = userId;
        this.provider = provider;
        this.model = model;
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
        this.costEstimate = costEstimate;
        this.promptHash = promptHash;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getTokensIn() {
        return tokensIn;
    }

    public void setTokensIn(Integer tokensIn) {
        this.tokensIn = tokensIn;
    }

    public Integer getTokensOut() {
        return tokensOut;
    }

    public void setTokensOut(Integer tokensOut) {
        this.tokensOut = tokensOut;
    }

    public BigDecimal getCostEstimate() {
        return costEstimate;
    }

    public void setCostEstimate(BigDecimal costEstimate) {
        this.costEstimate = costEstimate;
    }

    public String getPromptHash() {
        return promptHash;
    }

    public void setPromptHash(String promptHash) {
        this.promptHash = promptHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
