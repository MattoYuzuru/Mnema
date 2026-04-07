package app.mnema.core.review.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserDeckPreferencesSnapshotTest {

    @Test
    void snapshotCalculatesRemainingQuotaAndReviewDayBounds() {
        UUID userDeckId = UUID.randomUUID();
        UserDeckPreferencesService.PreferencesSnapshot snapshot =
                new UserDeckPreferencesService.PreferencesSnapshot(userDeckId, Duration.ofHours(2), 20, 50, 3, 10, "UTC", 60);

        assertThat(snapshot.remainingNewQuota()).isEqualTo(17);
        assertThat(snapshot.remainingReviewQuota()).isEqualTo(40);

        var bounds = snapshot.reviewDay(Instant.parse("2026-04-07T00:30:00Z"));
        assertThat(bounds.date()).isEqualTo(LocalDate.of(2026, 4, 6));
        assertThat(bounds.start()).isEqualTo(LocalDate.of(2026, 4, 6).atTime(1, 0).toInstant(ZoneOffset.UTC));
    }
}
