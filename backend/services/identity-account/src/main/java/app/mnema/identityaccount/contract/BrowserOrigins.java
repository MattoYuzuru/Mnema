package app.mnema.identityaccount.contract;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

/** Exact browser origins and callbacks; no wildcard subdomain or request-derived redirects. */
@Component
public final class BrowserOrigins {
    private final String main;
    private final String admin;

    public BrowserOrigins(@Value("${identity.frontend-origin}") String main,
                          @Value("${identity.admin-origin:}") String admin) {
        this.main = origin(main);
        this.admin = admin.isBlank() ? null : origin(admin);
        if (this.main.equals(this.admin)) throw new IllegalArgumentException("Admin origin must be distinct");
    }

    private static String origin(String value) {
        URI uri = URI.create(new IssuerContract(URI.create(value)).issuer());
        if (!uri.getPath().isEmpty() || value.endsWith("/") || !value.equals(uri.toASCIIString()))
            throw new IllegalArgumentException("Browser origin must contain only scheme, host and optional port");
        return uri.toASCIIString();
    }

    public List<String> all() { return admin == null ? List.of(main) : List.of(main, admin); }
    public String admin() { return admin; }
    public String main() { return main; }

    public String forClient(String client) {
        if (client == null || "mnema-web".equals(client)) return main;
        if (admin != null && "mnema-admin-web".equals(client)) return admin;
        throw AccountFailure.forbidden();
    }

    public String loginOrigin(HttpServletRequest request) {
        Object candidate = request.getAttribute("identity.login-origin");
        if (!(candidate instanceof String)) {
            var session = request.getSession(false);
            candidate = session == null ? null : session.getAttribute("identity.login-origin");
        }
        return candidate instanceof String value && all().contains(value) ? value : main;
    }
}
