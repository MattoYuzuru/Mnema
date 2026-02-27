package app.mnema.core.review.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "review_day_completions", schema = "app_core")
@IdClass(ReviewDayCompletionEntity.ReviewDayCompletionId.class)
public class ReviewDayCompletionEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "review_day", nullable = false)
    private LocalDate reviewDay;

    @Column(name = "first_completed_at", nullable = false)
    private Instant firstCompletedAt;

    @Column(name = "last_completed_at", nullable = false)
    private Instant lastCompletedAt;

    @Column(name = "completions_count", nullable = false)
    private int completionsCount;

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public LocalDate getReviewDay() {
        return reviewDay;
    }

    public void setReviewDay(LocalDate reviewDay) {
        this.reviewDay = reviewDay;
    }

    public Instant getFirstCompletedAt() {
        return firstCompletedAt;
    }

    public void setFirstCompletedAt(Instant firstCompletedAt) {
        this.firstCompletedAt = firstCompletedAt;
    }

    public Instant getLastCompletedAt() {
        return lastCompletedAt;
    }

    public void setLastCompletedAt(Instant lastCompletedAt) {
        this.lastCompletedAt = lastCompletedAt;
    }

    public int getCompletionsCount() {
        return completionsCount;
    }

    public void setCompletionsCount(int completionsCount) {
        this.completionsCount = completionsCount;
    }

    public static class ReviewDayCompletionId implements Serializable {
        private UUID userId;
        private LocalDate reviewDay;

        public ReviewDayCompletionId() {
        }

        public ReviewDayCompletionId(UUID userId, LocalDate reviewDay) {
            this.userId = userId;
            this.reviewDay = reviewDay;
        }

        public UUID getUserId() {
            return userId;
        }

        public LocalDate getReviewDay() {
            return reviewDay;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ReviewDayCompletionId that)) {
                return false;
            }
            return Objects.equals(userId, that.userId) && Objects.equals(reviewDay, that.reviewDay);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, reviewDay);
        }
    }
}
