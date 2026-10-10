package app.mnema.learning.platform.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

/**
 * What Identity says about the caller's own account, read on behalf of the caller (the same bearer token, {@code GET /api/accounts/me}). Learning
 * keeps no copy of these facts: a decision that needs one asks here and fails closed when Identity cannot answer.
 */
public interface AccountStandings {
    /** The facts of the token's account that promo codes depend on. */
    record Standing(boolean emailVerified, boolean admin) { }

    /**
     * @return the standing of the token's account, or empty when Identity is not configured, does not answer within its deadline, rejects the
     *         token or answers for another account; the caller must refuse, never assume
     */
    Optional<Standing> of(Jwt token);

    /** A current role decision for privileged operations; implementations with caches must bypass them. */
    default Optional<Standing> fresh(Jwt token) { return of(token); }
}
