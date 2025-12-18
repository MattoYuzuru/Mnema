package app.mnema.core.review.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "user_deck_preferences", schema = "app_core")
public class UserDeckPreferencesEntity {

    @Id
    @Column(name = "user_deck_id", nullable = false)
    private UUID userDeckId;

    @Column(name = "learning_horizon_minutes", nullable = false)
    private int learningHorizonMinutes;

    @Column(name = "max_new_per_day")
    private Integer maxNewPerDay;

    @Column(name = "max_review_per_day")
    private Integer maxReviewPerDay;

    @Column(name = "new_seen_today", nullable = false)
    private int newSeenToday;

    @Column(name = "review_seen_today", nullable = false)
    private int reviewSeenToday;

    @Column(name = "counter_date")
    private LocalDate counterDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public UUID getUserDeckId() {
        return userDeckId;
    }

    public void setUserDeckId(UUID userDeckId) {
        this.userDeckId = userDeckId;
    }

    public int getLearningHorizonMinutes() {
        return learningHorizonMinutes;
    }

    public void setLearningHorizonMinutes(int learningHorizonMinutes) {
        this.learningHorizonMinutes = learningHorizonMinutes;
    }

    public Integer getMaxNewPerDay() {
        return maxNewPerDay;
    }

    public void setMaxNewPerDay(Integer maxNewPerDay) {
        this.maxNewPerDay = maxNewPerDay;
    }

    public Integer getMaxReviewPerDay() {
        return maxReviewPerDay;
    }

    public void setMaxReviewPerDay(Integer maxReviewPerDay) {
        this.maxReviewPerDay = maxReviewPerDay;
    }

    public int getNewSeenToday() {
        return newSeenToday;
    }

    public void setNewSeenToday(int newSeenToday) {
        this.newSeenToday = newSeenToday;
    }

    public int getReviewSeenToday() {
        return reviewSeenToday;
    }

    public void setReviewSeenToday(int reviewSeenToday) {
        this.reviewSeenToday = reviewSeenToday;
    }

    public LocalDate getCounterDate() {
        return counterDate;
    }

    public void setCounterDate(LocalDate counterDate) {
        this.counterDate = counterDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getRowVersion() {
        return rowVersion;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
