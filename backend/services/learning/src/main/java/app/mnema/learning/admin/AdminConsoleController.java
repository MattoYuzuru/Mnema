package app.mnema.learning.admin;

import app.mnema.learning.admin.support.AdminSupportSettings;
import app.mnema.learning.events.EventAdminAccess;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.security.AccountStandings;
import app.mnema.learning.usage.UsageClock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/admin/console")
public class AdminConsoleController {
    public record Permissions(boolean events, boolean promos, boolean moderation, boolean support) { }
    public record Access(boolean owner, Permissions permissions) { }
    private final AdminConsoleAccess access;
    private final EventAdminAccess events;
    private final AccountStandings standings;
    private final AdminSupportSettings support;
    private final AdminReports reports;
    private final AdminAudit audit;
    private final UsageClock clock;

    public AdminConsoleController(AdminConsoleAccess access, EventAdminAccess events, AccountStandings standings,
                                  AdminSupportSettings support, AdminReports reports, AdminAudit audit, UsageClock clock) {
        this.access = access;
        this.events = events;
        this.standings = standings;
        this.support = support;
        this.reports = reports;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping("/access")
    ResponseEntity<Access> access(@AuthenticationPrincipal Jwt token, HttpServletRequest request) {
        UUID actor = owner(token);
        AdminReportRange.query(request, Set.of());
        boolean admin = standings.fresh(token).map(AccountStandings.Standing::admin).orElse(false);
        return privateResponse(new Access(true, new Permissions(events.allows(actor), admin, admin, support.enabled())));
    }

    @GetMapping("/report")
    ResponseEntity<tools.jackson.databind.node.ObjectNode> report(@AuthenticationPrincipal Jwt token, HttpServletRequest request) {
        owner(token);
        return privateResponse(reports.report(range(request)));
    }

    @GetMapping("/users/{id}")
    ResponseEntity<tools.jackson.databind.node.ObjectNode> user(@AuthenticationPrincipal Jwt token, @PathVariable UUID id, HttpServletRequest request) {
        owner(token);
        try { UuidPolicy.requireEntityId(id, "account"); }
        catch (IllegalArgumentException failure) { throw new InvalidRequestException(); }
        return privateResponse(reports.user(id, range(request)));
    }

    @GetMapping("/audit")
    ResponseEntity<AdminAudit.Page> audit(@AuthenticationPrincipal Jwt token, HttpServletRequest request) {
        owner(token);
        AdminReportRange.query(request, Set.of("before"));
        return privateResponse(audit.page(request.getParameter("before")));
    }

    private UUID owner(Jwt token) { return access.require(token); }

    private AdminReportRange range(HttpServletRequest request) {
        AdminReportRange.query(request, Set.of("from", "to"));
        return AdminReportRange.parse(request.getParameter("from"), request.getParameter("to"), clock.now());
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(body);
    }
}
