package app.mnema.ai.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscriptions", schema = "app_ai")
public class SubscriptionEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plan_id")
    private String planId;

    @Column(name = "subscription_status")
    private String subscriptionStatus;

    @Column(name = "period_end")
    private Instant periodEnd;

    @Column(name = "billing_anchor")
    private Integer billingAnchor;

    public SubscriptionEntity() {
    }

    public SubscriptionEntity(UUID userId,
                              String planId,
                              String subscriptionStatus,
                              Instant periodEnd,
                              Integer billingAnchor) {
        this.userId = userId;
        this.planId = planId;
        this.subscriptionStatus = subscriptionStatus;
        this.periodEnd = periodEnd;
        this.billingAnchor = billingAnchor;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getSubscriptionStatus() {
        return subscriptionStatus;
    }

    public void setSubscriptionStatus(String subscriptionStatus) {
        this.subscriptionStatus = subscriptionStatus;
    }

    public Instant getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(Instant periodEnd) {
        this.periodEnd = periodEnd;
    }

    public Integer getBillingAnchor() {
        return billingAnchor;
    }

    public void setBillingAnchor(Integer billingAnchor) {
        this.billingAnchor = billingAnchor;
    }
}
