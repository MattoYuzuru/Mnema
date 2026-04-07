package app.mnema.core.review.entity;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewUserCardEntityTest {

    @Test
    void gettersExposeMappedReviewCardFields() {
        ReviewUserCardEntity entity = new ReviewUserCardEntity();
        UUID userCardId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-04-07T10:15:30Z");

        ReflectionTestUtils.setField(entity, "userCardId", userCardId);
        ReflectionTestUtils.setField(entity, "userId", userId);
        ReflectionTestUtils.setField(entity, "userDeckId", userDeckId);
        ReflectionTestUtils.setField(entity, "deleted", true);
        ReflectionTestUtils.setField(entity, "createdAt", createdAt);

        assertThat(entity.getUserCardId()).isEqualTo(userCardId);
        assertThat(entity.getUserId()).isEqualTo(userId);
        assertThat(entity.getUserDeckId()).isEqualTo(userDeckId);
        assertThat(entity.isDeleted()).isTrue();
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
    }
}
