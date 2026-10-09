package app.mnema.identityaccount.security;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Production cannot disable abuse protection; {@code blocked} is the operational kill switch for password auth. */
final class TurnstilePolicy {
    enum Mode { DISABLED, BLOCKED, REQUIRED }

    final Mode mode;
    final String siteKey;
    final String secret;
    final Set<String> hostnames;

    TurnstilePolicy(String mode, String environment, String siteKey, String secret,
                    URI frontend, URI issuer) {
        var requested = Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        boolean development = Set.of("dev", "development", "test", "local", "local-blackbox",
                "local-browser-fixture", "local-full-stack").contains(environment);
        this.mode = requested == Mode.DISABLED && !development ? Mode.BLOCKED : requested;
        this.siteKey = siteKey;
        this.secret = secret;
        this.hostnames = Set.copyOf(java.util.List.of(frontend.getHost(), issuer.getHost()));
        if (this.mode == Mode.REQUIRED && (!key(siteKey) || !key(secret)))
            throw new IllegalArgumentException("Turnstile requires configured keys");
        if (!development && this.mode == Mode.REQUIRED && (testKey(siteKey) || testKey(secret)))
            throw new IllegalArgumentException("Turnstile test keys are forbidden outside development");
    }

    private static boolean key(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{10,128}");
    }

    private static boolean testKey(String value) {
        return value.matches("[123]x0+[A-Za-z]{2}");
    }
}
