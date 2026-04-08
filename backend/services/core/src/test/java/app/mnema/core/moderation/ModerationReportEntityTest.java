package app.mnema.core.moderation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModerationReportEntityTest {

    @Test
    void lifecycleAndCloseUpdateTimestampsAndResolutionFields() {
        UUID reportId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        ModerationReportEntity entity = new ModerationReportEntity(
                reportId,
                ModerationReportTargetType.DECK,
                targetId,
                null,
                "Shared deck",
                ownerId,
                reporterId,
                "reporter",
                ModerationReportReason.SPAM,
                "details"
        );

        entity.onCreate();
        Instant createdAt = entity.getCreatedAt();
        entity.close(adminId, "root", "resolved");
        entity.onUpdate();

        assertThat(entity.getReportId()).isEqualTo(reportId);
        assertThat(entity.getTargetType()).isEqualTo(ModerationReportTargetType.DECK);
        assertThat(entity.getTargetId()).isEqualTo(targetId);
        assertThat(entity.getContentOwnerId()).isEqualTo(ownerId);
        assertThat(entity.getReporterId()).isEqualTo(reporterId);
        assertThat(entity.getReporterUsername()).isEqualTo("reporter");
        assertThat(entity.getReason()).isEqualTo(ModerationReportReason.SPAM);
        assertThat(entity.getDetails()).isEqualTo("details");
        assertThat(entity.getStatus()).isEqualTo(ModerationReportStatus.CLOSED);
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getUpdatedAt()).isAfterOrEqualTo(createdAt);
        assertThat(entity.getClosedAt()).isNotNull();
        assertThat(entity.getClosedByUserId()).isEqualTo(adminId);
        assertThat(entity.getClosedByUsername()).isEqualTo("root");
        assertThat(entity.getResolutionNote()).isEqualTo("resolved");
    }
}
