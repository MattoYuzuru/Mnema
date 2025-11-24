package app.mnema.core.deck.domain.entity;

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

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_card_id", insertable = false, updatable = false)
    private UserCardEntity userCard;

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

    public UUID getUserCardId() {
        return userCardId;
    }

    public void setUserCardId(UUID userCardId) {
        this.userCardId = userCardId;
    }

    public UserCardEntity getUserCard() {
        return userCard;
    }

    public void setUserCard(UserCardEntity userCard) {
        this.userCard = userCard;
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
}
