package app.mnema.learning.generation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * {@code learning.runtime.roles} ({@code api|worker|all}, default {@code all}): which halves of the AI layer this process
 * runs (architecture A1). {@code api} serves HTTP and creates work; {@code worker} executes steps and holds the provider
 * keys; {@code all} is the local and first-release topology. The step dispatcher exists only for {@code worker} and
 * {@code all}. An unknown value stops the start instead of silently running as something else.
 */
@Component
final class RuntimeRoles {
    private static final Set<String> KNOWN = Set.of("api", "worker", "all");

    private final String roles;

    RuntimeRoles(@Value("${learning.runtime.roles:all}") String roles) {
        String normalized = roles == null ? "" : roles.strip().toLowerCase(Locale.ROOT);
        if (!KNOWN.contains(normalized)) throw new IllegalStateException("learning.runtime.roles must be api, worker or all");
        this.roles = normalized;
    }

    boolean runsWorker() { return !roles.equals("api"); }

    String value() { return roles; }
}
