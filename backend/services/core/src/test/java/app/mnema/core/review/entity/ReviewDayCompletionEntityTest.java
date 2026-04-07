package app.mnema.core.review.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewDayCompletionEntityTest {

    @Test
    void entityAndCompositeIdExposeFieldsAndIdentity() {
        UUID userId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 4, 7);
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        ReviewDayCompletionEntity entity = new ReviewDayCompletionEntity();
        entity.setUserId(userId);
        entity.setReviewDay(day);
        entity.setFirstCompletedAt(now);
        entity.setLastCompletedAt(now.plusSeconds(60));
        entity.setCompletionsCount(2);

        ReviewDayCompletionEntity.ReviewDayCompletionId id =
                new ReviewDayCompletionEntity.ReviewDayCompletionId(userId, day);

        assertThat(entity.getUserId()).isEqualTo(userId);
        assertThat(entity.getReviewDay()).isEqualTo(day);
        assertThat(entity.getFirstCompletedAt()).isEqualTo(now);
        assertThat(entity.getLastCompletedAt()).isEqualTo(now.plusSeconds(60));
        assertThat(entity.getCompletionsCount()).isEqualTo(2);
        assertThat(id.getUserId()).isEqualTo(userId);
        assertThat(id.getReviewDay()).isEqualTo(day);
        assertThat(id).isEqualTo(new ReviewDayCompletionEntity.ReviewDayCompletionId(userId, day));
    }
}
