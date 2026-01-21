package app.mnema.core.review.entity;

import app.mnema.core.review.domain.ReviewSource;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sr_review_logs", schema = "app_core")
public class SrReviewLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "user_card_id", nullable = false)
    private UUID userCardId;

    @Column(name = "algorithm_id", nullable = false)
    private String algorithmId;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Column(name = "rating")
    private Short rating;

    @Column(name = "response_ms")
    private Integer responseMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features", columnDefinition = "jsonb")
    private JsonNode features;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_before", columnDefinition = "jsonb")
    private JsonNode stateBefore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_after", columnDefinition = "jsonb")
    private JsonNode stateAfter;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "source", columnDefinition = "review_source")
    private ReviewSource source;

    public Long getId() {
        return id;
    }

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

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Short getRating() {
        return rating;
    }

    public void setRating(Short rating) {
        this.rating = rating;
    }

    public Integer getResponseMs() {
        return responseMs;
    }

    public void setResponseMs(Integer responseMs) {
        this.responseMs = responseMs;
    }

    public JsonNode getFeatures() {
        return features;
    }

    public void setFeatures(JsonNode features) {
        this.features = features;
    }

    public JsonNode getStateBefore() {
        return stateBefore;
    }

    public void setStateBefore(JsonNode stateBefore) {
        this.stateBefore = stateBefore;
    }

    public JsonNode getStateAfter() {
        return stateAfter;
    }

    public void setStateAfter(JsonNode stateAfter) {
        this.stateAfter = stateAfter;
    }

    public ReviewSource getSource() {
        return source;
    }

    public void setSource(ReviewSource source) {
        this.source = source;
    }
}
