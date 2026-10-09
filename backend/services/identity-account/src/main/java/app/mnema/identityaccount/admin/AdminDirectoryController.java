package app.mnema.identityaccount.admin;

import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.security.BrowserSessions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts/admin")
public final class AdminDirectoryController {
    private final AdminOwnerAccess access;
    private final AdminDirectory directory;

    public AdminDirectoryController(AdminOwnerAccess access, AdminDirectory directory) {
        this.access = access;
        this.directory = directory;
    }

    @GetMapping("/directory")
    ResponseEntity<AdminDirectory.Page> page(Authentication actor, HttpServletRequest request) {
        access.require(BrowserSessions.access(actor));
        query(request, Set.of("query", "status", "after"));
        return privateResponse(directory.page(request.getParameter("query"), request.getParameter("status"), request.getParameter("after")));
    }

    @GetMapping("/directory/{id}")
    ResponseEntity<AdminDirectory.Account> account(Authentication actor, @PathVariable UUID id, HttpServletRequest request) {
        access.require(BrowserSessions.access(actor));
        query(request, Set.of());
        return privateResponse(directory.account(id));
    }

    @GetMapping("/audit")
    ResponseEntity<AdminDirectory.AuditPage> audit(Authentication actor, HttpServletRequest request) {
        access.require(BrowserSessions.access(actor));
        query(request, Set.of("before"));
        return privateResponse(directory.audit(request.getParameter("before")));
    }

    private static void query(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap().forEach((key, values) -> {
            if (!allowed.contains(key) || values.length != 1) throw new AccountFailure(400, "invalid_admin_query");
        });
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(body);
    }
}
