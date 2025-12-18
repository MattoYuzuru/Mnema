package app.mnema.core.review.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sr_card_states", schema = "app_core")
public class SrCardStateEntity {

    @Id
    @Column(name = "user_card_id")
    private UUID userCardId;

    @Column(name = "algorithm_id", nullable = false)
    private String algorithmId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state", columnDefinition = "jsonb", nullable = false)
    private JsonNode state;

    @Column(name = "last_review_at")
    private Instant lastReviewAt;

    @Column(name = "next_review_at")
    private Instant nextReviewAt;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "is_suspended", nullable = false)
    private boolean suspended;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public UUID getUserCardId() {
        return userCardId;
    }

    public void setUserCardId(UUID userCardId) {
        this.userCardId = userCardId;
    }

    public String getAlgorithmId() {
        return algorithmId;
    }

    public void setAlgorithmId(String algorithmId) {
        this.algorithmId = algorithmId;
    }

    public JsonNode getState() {
        return state;
    }

    public void setState(JsonNode state) {
        this.state = state;
    }

    public Instant getLastReviewAt() {
        return lastReviewAt;
    }

    public void setLastReviewAt(Instant lastReviewAt) {
        this.lastReviewAt = lastReviewAt;
    }

    public Instant getNextReviewAt() {
        return nextReviewAt;
    }

    public void setNextReviewAt(Instant nextReviewAt) {
        this.nextReviewAt = nextReviewAt;
    }

    public int getReviewCount() {
        return reviewCount;
    }

    public void setReviewCount(int reviewCount) {
        this.reviewCount = reviewCount;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }

    public long getRowVersion() {
        return rowVersion;
    }

}
