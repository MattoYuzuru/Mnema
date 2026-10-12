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

    /** {@code mnema_trusted}: the account's e-mail is verified and the account is at least 3 days old (contracts/identity/user-info-claims.json). */
    public static final String TRUSTED_CLAIM = "mnema_trusted";
    /** {@code mnema_new_account}: the account is younger than 48 hours. */
    public static final String NEW_ACCOUNT_CLAIM = "mnema_new_account";
    private static final String SIGNALS_ATTRIBUTE = IdentityClaims.class.getName() + ".signals";

    private IdentityClaims() { }

    /**
     * The two anti-abuse signals of the account behind a request (internal: they weigh a copy in the community rank and feed the owner console's anomaly flag, and are
     * shown to nobody). Absent or malformed claims are {@code trusted = false, newAccount = false}: a missing claim lowers the weight of a copy but never raises a flag.
     */
    public record AccountSignals(boolean trusted, boolean newAccount) {
        /** What is assumed when Identity said nothing. */
        public static final AccountSignals UNKNOWN = new AccountSignals(false, false);
    }

    static void signals(HttpServletRequest request, AccountSignals signals) { request.setAttribute(SIGNALS_ATTRIBUTE, signals); }

    /** The signals Identity reported for this request; {@link AccountSignals#UNKNOWN} when the request was not checked against Identity. */
    public static AccountSignals signals(HttpServletRequest request) {
        return request.getAttribute(SIGNALS_ATTRIBUTE) instanceof AccountSignals signals ? signals : AccountSignals.UNKNOWN;
    }

    static void publicProfile(HttpServletRequest request, boolean ready) { request.setAttribute(PUBLIC_PROFILE_ATTRIBUTE, ready); }

    /** Whether Identity reported the public profile ready for this request; an absent or malformed claim is not ready (fail closed). */
    public static boolean publicProfileReady(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(PUBLIC_PROFILE_ATTRIBUTE));
    }
}
