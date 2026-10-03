package app.mnema.learning.notification;

import app.mnema.learning.platform.api.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationUnitTest {
    @Test
    void cursorsRoundTripAndRejectEverythingElse() {
        for (boolean ascending : new boolean[] {true, false}) {
            var cursor = new NotificationCursor(ascending, 123);
            assertThat(NotificationCursor.decode(cursor.encode())).isEqualTo(cursor);
        }
        assertThat(NotificationCursor.decode(null)).isNull();
        for (String bad : new String[] {"", "!", "A", Base64.getUrlEncoder().withoutPadding().encodeToString("X1".getBytes()),
                Base64.getUrlEncoder().withoutPadding().encodeToString("A01".getBytes()),
                Base64.getUrlEncoder().withoutPadding().encodeToString("D-1".getBytes()), "x".repeat(65)}) {
            assertThatThrownBy(() -> NotificationCursor.decode(bad)).as(bad).isInstanceOf(InvalidRequestException.class);
        }
        assertThat(NotificationCursor.pageSize(null)).isEqualTo(20);
        assertThat(NotificationCursor.pageSize("100")).isEqualTo(100);
        assertThatThrownBy(() -> NotificationCursor.pageSize("101")).isInstanceOf(InvalidRequestException.class);
        assertThat(NotificationCursor.parseSeq("0")).isZero();
    }

    @Test
    void validatorsMatchAListOfStrongOrWeakTagsOrAWildcardAndNothingElse() {
        assertThat(NotificationService.matches(null, "\"a\"")).isFalse();
        assertThat(NotificationService.matches("\"a\"", "\"a\"")).isTrue();
        assertThat(NotificationService.matches("\"b\" , W/\"a\"", "\"a\"")).isTrue();
        assertThat(NotificationService.matches("*", "\"a\"")).isTrue();
        assertThat(NotificationService.matches("\"b\"", "\"a\"")).isFalse();
        var stats = new NotificationRepository.Stats(5, 2, 3, 1, Instant.parse("2026-11-01T09:00:00Z"));
        String etag = NotificationService.etag(stats, 1, 20, null, null);
        assertThat(etag).startsWith("\"n-5-2-3-1-1793523600-").endsWith("\"");
        assertThat(NotificationService.etag(stats, 1, 20, null, null)).isEqualTo(etag);
        assertThat(NotificationService.etag(stats, 2, 20, null, null)).isNotEqualTo(etag);
        assertThat(NotificationService.etag(stats, 1, 21, null, null)).isNotEqualTo(etag);
        assertThat(NotificationService.etag(stats, 1, 20, 3L, null)).isNotEqualTo(etag);
        assertThat(NotificationService.etag(stats, 1, 20, null, new NotificationCursor(false, 3))).isNotEqualTo(etag);
        assertThat(NotificationService.etag(new NotificationRepository.Stats(0, 0, 0, 0, null), 0, 20, null, null))
                .contains("-0-0-0-0-0-");
    }

    @Test
    void anAfterTogetherWithACursorIsRejectedByTheService() {
        var service = new NotificationService(mock(NotificationRepository.class), owner -> 0);
        assertThatThrownBy(() -> service.list(UUID.randomUUID(), 20, 1L, new NotificationCursor(false, 1), null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void thePolicyHasDefaultsAndBounds() {
        var settings = new NotificationSettings(Duration.ofDays(30), 200, 4096);
        assertThat(settings.retention).isEqualTo(Duration.ofDays(30));
        for (Object[] bad : new Object[][] {{Duration.ofHours(23), 200, 4096}, {Duration.ofDays(91), 200, 4096},
                {Duration.ofMillis(86_400_500L), 200, 4096}, {Duration.ofDays(30), 9, 4096},
                {Duration.ofDays(30), 1001, 4096}, {Duration.ofDays(30), 200, 255}, {Duration.ofDays(30), 200, 4097}}) {
            assertThatThrownBy(() -> new NotificationSettings((Duration) bad[0], (Integer) bad[1], (Integer) bad[2]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theKindsValidateExactlyTheirFieldSet() {
        Map<String, Object> usage = new HashMap<>();
        usage.put("bucket", "CREDITS");
        usage.put("window", "WEEK");
        usage.put("renewsAt", null);
        usage.put("plan", "FREE");
        NotificationKind.USAGE_EXHAUSTED.validate(usage);
        usage.put("renewsAt", Instant.parse("2026-10-04T21:00:00Z"));
        NotificationKind.USAGE_EXHAUSTED.validate(usage);
        usage.put("renewsAt", "2026-10-04T21:00:00Z");
        assertThatThrownBy(() -> NotificationKind.USAGE_EXHAUSTED.validate(usage)).isInstanceOf(IllegalArgumentException.class);
        usage.put("renewsAt", null);
        usage.put("bucket", "has space");
        assertThatThrownBy(() -> NotificationKind.USAGE_EXHAUSTED.validate(usage)).isInstanceOf(IllegalArgumentException.class);
        usage.put("bucket", "CREDITS");
        usage.put("window", 7L * Integer.MAX_VALUE);
        assertThatThrownBy(() -> NotificationKind.USAGE_EXHAUSTED.validate(usage)).isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> media = new HashMap<>();
        media.put("assetId", new UUID(0, 0));
        media.put("mediaKind", "IMAGE");
        media.put("deckId", null);
        media.put("sessionId", null);
        media.put("artifactId", null);
        media.put("slotKey", null);
        media.put("reason", "PROCESSING_FAILED");
        assertThatThrownBy(() -> NotificationKind.MEDIA_PROCESSING_FAILED.validate(media)).isInstanceOf(IllegalArgumentException.class);
        media.put("assetId", UUID.randomUUID());
        NotificationKind.MEDIA_PROCESSING_FAILED.validate(media);
        assertThat(NotificationKind.MEDIA_PROCESSING_FAILED.route(media)).isEqualTo(NotificationRoute.NONE);
    }

    @Test
    void theWorkerRepeatsFullBatchesUpToABoundAndStopsOnTheFirstShortOne() {
        var service = mock(NotificationRetentionService.class);
        when(service.purgeBatch()).thenReturn(NotificationRetentionService.BATCH_SIZE, 7);
        new NotificationRetentionWorker(service).purge();
        verify(service, times(2)).purgeBatch();

        var endless = mock(NotificationRetentionService.class);
        when(endless.purgeBatch()).thenReturn(NotificationRetentionService.BATCH_SIZE);
        new NotificationRetentionWorker(endless).purge();
        verify(endless, times(NotificationRetentionWorker.MAX_BATCHES_PER_TICK)).purgeBatch();
    }
}
