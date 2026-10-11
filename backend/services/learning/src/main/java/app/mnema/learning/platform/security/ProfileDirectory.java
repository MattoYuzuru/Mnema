package app.mnema.learning.platform.security;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who stands behind a public-profile login. Learning never reads Identity tables: the lookup is Identity's public-profile endpoint
 * ({@code GET /api/accounts/profiles/by-username/{u}}), called server-side without any credential. Only an account with a public profile
 * (consent on, active, login set) resolves; an unknown login, a malformed one and a hidden profile all give the same empty answer, so the lookup is
 * no existence oracle.
 */
public interface ProfileDirectory {
    Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,50}");

    /** Whether the text has the shape of a profile username; a text that does not never causes a call. */
    static boolean validUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    /**
     * @param username a login; anything that is not a valid profile username is empty without a call
     * @return the account id of a public profile, or empty
     * @throws app.mnema.learning.platform.api.RateLimitedException Identity limits lookups per network and refused this one
     * @throws app.mnema.learning.platform.api.IdentityUnavailableException Identity did not give a usable answer (never assumed to mean "no profile")
     */
    Optional<UUID> accountOf(String username);
}
