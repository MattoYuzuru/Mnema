package app.mnema.identityaccount.federation;

import app.mnema.identityaccount.contract.BrowserOrigins;
import app.mnema.identityaccount.contract.AccountFailure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.time.Clock;

/**
 * A session-bound, one-use state with an independent short expiry and exact provider callback binding.
 */
public final class FederationRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private final HttpSessionOAuth2AuthorizationRequestRepository delegate = new HttpSessionOAuth2AuthorizationRequestRepository();
    private final Clock clock;
    private final BrowserOrigins origins;

    public FederationRequests(Clock clock, BrowserOrigins origins) {
        this.clock = clock;
        this.origins = origins;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        var value = delegate.loadAuthorizationRequest(request);
        if (value == null) return null;
        var session = request.getSession(false);
        Object expiry = session.getAttribute("identity.oauth-expiry");
        String provider = value.getAttribute("registration_id");
        if (!(expiry instanceof Long timestamp) || clock.instant().getEpochSecond() >= timestamp ||
                !request.getRequestURI().equals("/login/oauth2/code/" + provider))
            return null;
        return value;
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest value, HttpServletRequest request,
                                         HttpServletResponse response) {
        if (value != null) {
            Object intent = request.getSession().getAttribute("identity.intent");
            request.getSession().removeAttribute("identity.intent");
            if (intent != null)
                value = OAuth2AuthorizationRequest.from(value)
                        .attributes(attributes -> attributes.put("identity.intent", intent)).build();
            else {
                String[] states = request.getParameterValues("mnema_state");
                if (states != null && states.length == 1 && states[0].matches("[A-Za-z0-9_-]{43}")) {
                    String[] clients = request.getParameterValues("mnema_client_id");
                    if (clients != null && clients.length != 1) throw AccountFailure.forbidden();
                    String browserOrigin = origins.forClient(clients == null ? null : clients[0]);
                    request.getSession().setAttribute("identity.login-origin", browserOrigin);
                    value = OAuth2AuthorizationRequest.from(value)
                            .attributes(attributes -> {
                                attributes.put("identity.login-state", states[0]);
                                attributes.put("identity.login-origin", browserOrigin);
                            }).build();
                }
            }
        }
        delegate.saveAuthorizationRequest(value, request, response);
        if (value != null)
            request.getSession()
                    .setAttribute("identity.oauth-expiry", clock.instant().plusSeconds(300).getEpochSecond());
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        var value = loadAuthorizationRequest(request);
        delegate.removeAuthorizationRequest(request, response);
        if (value != null) {
            request.setAttribute("identity.intent", value.getAttribute("identity.intent"));
            request.setAttribute("identity.login-state", value.getAttribute("identity.login-state"));
            request.setAttribute("identity.login-origin", value.getAttribute("identity.login-origin"));
        }
        var session = request.getSession(false);
        if (session != null) session.removeAttribute("identity.oauth-expiry");
        return value;
    }
}
