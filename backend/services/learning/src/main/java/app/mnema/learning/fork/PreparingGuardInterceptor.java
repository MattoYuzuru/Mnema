package app.mnema.learning.fork;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The HTTP net of the preparing guard: every non-GET route of the form {@code /decks/{deckId}/...} (and the metadata {@code PATCH} of {@code /decks/{deckId}}) is refused with
 * {@code 409 DECK_PREPARING} while the authenticated account's own copy {@code deckId} is not materialized. Not covered on purpose: the reads (the hub shows the preparing state),
 * the three routes that get a stuck copy out ({@code fork:retry}, {@code fork:detach}, {@code DELETE /decks/{deckId}}), and every route that does not name a deck in its path (the two
 * body-addressed ones call {@link ForkGuard} themselves). It decides by the route template the request was mapped to, so a new route under the prefix is guarded by default.
 */
@Component
final class PreparingGuardInterceptor implements HandlerInterceptor {
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> OPEN = Set.of("/decks/{deckId}/fork:retry", "/decks/{deckId}/fork:detach");

    /** Resolved when a request needs it: a web-layer slice of the application (no repositories) can still register the interceptor. */
    private final ObjectProvider<ForkGuard> guard;

    PreparingGuardInterceptor(ObjectProvider<ForkGuard> guard) { this.guard = guard; }

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!WRITES.contains(request.getMethod())) return true;
        Object template = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(template instanceof String pattern) || !pattern.startsWith("/decks/{deckId}") || OPEN.contains(pattern)) return true;
        if (pattern.equals("/decks/{deckId}") && request.getMethod().equals("DELETE")) return true;
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(variables instanceof Map<?, ?> map) || authentication == null) return true;
        UUID actor;
        UUID deck;
        try {
            actor = UUID.fromString(authentication.getName());
            deck = UUID.fromString(String.valueOf(((Map<String, String>) map).get("deckId")));
        } catch (IllegalArgumentException malformed) {
            return true;
        }
        guard.getObject().requireReady(actor, deck);
        return true;
    }
}
