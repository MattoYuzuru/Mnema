package app.mnema.core.moderation;

import app.mnema.core.security.CurrentUserProvider;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/moderation/reports")
public class ModerationReportController {

    private final ModerationReportService moderationReportService;
    private final CurrentUserProvider currentUserProvider;

    public ModerationReportController(ModerationReportService moderationReportService,
                                      CurrentUserProvider currentUserProvider) {
        this.moderationReportService = moderationReportService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public ModerationReportService.ModerationReportDto createReport(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateModerationReportRequest request
    ) {
        return moderationReportService.createReport(
                currentUserProvider.getUserId(jwt),
                currentUserProvider.getUsername(jwt),
                request.targetType(),
                request.targetId(),
                request.reason(),
                request.details()
        );
    }

    @GetMapping("/admin/open")
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public Page<ModerationReportService.ModerationReportDto> getOpenReports(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return moderationReportService.getOpenReports(currentUserProvider.getUserId(jwt), page, limit);
    }

    @GetMapping("/admin/closed")
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public Page<ModerationReportService.ModerationReportDto> getClosedReports(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return moderationReportService.getClosedReports(currentUserProvider.getUserId(jwt), page, limit);
    }

    @GetMapping("/admin/stats")
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public ModerationReportService.ModerationReportStatsDto getStats(@AuthenticationPrincipal Jwt jwt) {
        return moderationReportService.getStats(currentUserProvider.getUserId(jwt));
    }

    @PostMapping("/admin/{reportId}/close")
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public ModerationReportService.ModerationReportDto closeReport(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID reportId,
            @RequestBody(required = false) CloseModerationReportRequest request
    ) {
        return moderationReportService.closeReport(
                currentUserProvider.getUserId(jwt),
                currentUserProvider.getUsername(jwt),
                reportId,
                request == null ? null : request.resolutionNote()
        );
    }

    public record CreateModerationReportRequest(
            ModerationReportTargetType targetType,
            UUID targetId,
            ModerationReportReason reason,
            String details
    ) {
    }

    public record CloseModerationReportRequest(String resolutionNote) {
    }
}
