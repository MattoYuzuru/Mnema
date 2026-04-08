package app.mnema.core.moderation;

import app.mnema.core.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModerationReportControllerTest {

    private final ModerationReportService moderationReportService = mock(ModerationReportService.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final ModerationReportController controller =
            new ModerationReportController(moderationReportService, currentUserProvider);

    @Test
    void createReport_andAdminEndpoints_delegateClaimsAndPayload() {
        UUID userId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("user_id", userId.toString()).build();
        ModerationReportService.ModerationReportDto dto = new ModerationReportService.ModerationReportDto(
                reportId,
                ModerationReportTargetType.CARD,
                targetId,
                UUID.randomUUID(),
                "Card from deck",
                UUID.randomUUID(),
                userId,
                "reporter",
                ModerationReportReason.SPAM,
                "details",
                ModerationReportStatus.OPEN,
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                null
        );
        ModerationReportService.ModerationReportStatsDto statsDto = new ModerationReportService.ModerationReportStatsDto(
                5,
                7,
                List.of(new ModerationReportService.CountSliceDto("CARD", 3)),
                List.of(new ModerationReportService.CountSliceDto("SPAM", 4)),
                List.of(new ModerationReportService.ResolverStatsDto(userId, "root", 2))
        );
        when(currentUserProvider.getUserId(jwt)).thenReturn(userId);
        when(currentUserProvider.getUsername(jwt)).thenReturn("root");
        when(moderationReportService.createReport(
                userId,
                "root",
                ModerationReportTargetType.CARD,
                targetId,
                ModerationReportReason.SPAM,
                "details"
        )).thenReturn(dto);
        when(moderationReportService.getOpenReports(userId, 1, 20)).thenReturn(new PageImpl<>(List.of(dto)));
        when(moderationReportService.getClosedReports(userId, 2, 10)).thenReturn(new PageImpl<>(List.of(dto)));
        when(moderationReportService.getStats(userId)).thenReturn(statsDto);
        when(moderationReportService.closeReport(userId, "root", reportId, "resolved")).thenReturn(dto);

        ModerationReportController.CreateModerationReportRequest createRequest =
                new ModerationReportController.CreateModerationReportRequest(
                        ModerationReportTargetType.CARD,
                        targetId,
                        ModerationReportReason.SPAM,
                        "details"
                );
        ModerationReportController.CloseModerationReportRequest closeRequest =
                new ModerationReportController.CloseModerationReportRequest("resolved");

        assertThat(controller.createReport(jwt, createRequest)).isEqualTo(dto);
        assertThat(controller.getOpenReports(jwt, 1, 20).getContent()).containsExactly(dto);
        assertThat(controller.getClosedReports(jwt, 2, 10).getContent()).containsExactly(dto);
        assertThat(controller.getStats(jwt)).isEqualTo(statsDto);
        assertThat(controller.closeReport(jwt, reportId, closeRequest)).isEqualTo(dto);

        verify(moderationReportService).closeReport(userId, "root", reportId, "resolved");
    }

    @Test
    void closeReport_acceptsNullRequestBody() {
        UUID userId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("user_id", userId.toString()).build();
        ModerationReportService.ModerationReportDto dto = new ModerationReportService.ModerationReportDto(
                reportId,
                ModerationReportTargetType.DECK,
                UUID.randomUUID(),
                null,
                "Deck",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "reporter",
                ModerationReportReason.OTHER,
                "custom",
                ModerationReportStatus.CLOSED,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                userId,
                "root",
                null
        );
        when(currentUserProvider.getUserId(jwt)).thenReturn(userId);
        when(currentUserProvider.getUsername(jwt)).thenReturn("root");
        when(moderationReportService.closeReport(userId, "root", reportId, null)).thenReturn(dto);

        assertThat(controller.closeReport(jwt, reportId, null)).isEqualTo(dto);
    }
}
