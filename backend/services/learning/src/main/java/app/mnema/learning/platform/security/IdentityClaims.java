package app.mnema.learning.platform.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Claims of the Identity {@code /userinfo} answer that {@link CurrentIdentityFilter} already fetched to authorize the request, kept as request attributes so that
 * a service reads them without a second call to Identity. Learning never reads Identity tables: the facts it needs from the account arrive here.
 */
public final class IdentityClaims {
    /** {@code mnema_public_profile}: Identity's {@code publishReady} (the public-profile consent is on and the account has a login). */
    public static final String PUBLIC_PROFILE_CLAIM = "mnema_public_profile";
    private static final String PUBLIC_PROFILE_ATTRIBUTE = IdentityClaims.class.getName() + ".publicProfile";

    private IdentityClaims() { }

    static void publicProfile(HttpServletRequest request, boolean ready) { request.setAttribute(PUBLIC_PROFILE_ATTRIBUTE, ready); }

    /** Whether Identity reported the public profile ready for this request; an absent or malformed claim is not ready (fail closed). */
    public static boolean publicProfileReady(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(PUBLIC_PROFILE_ATTRIBUTE));
    }
}
