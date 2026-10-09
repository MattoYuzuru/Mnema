package app.mnema.learning.billing;

import app.mnema.learning.platform.api.CapabilityUnavailableException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.usage.CheckoutAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code learning.billing.*}: who may check out, the bank's address and credentials, the public origin of the return URLs and the limits of the flow
 * ({@code contracts/billing}). Invalid values stop the start, with one exception: credentials. A missing or unusable terminal key, password or public
 * origin makes billing {@link #configured() not configured} (checkout answers {@code 409 CAPABILITY_UNAVAILABLE}, notifications {@code 403}) and is
 * logged once as an ERROR that names the settings and never a value, so a half-finished production configuration cannot take the whole service down.
 *
 * <p>The password is held as text for the signature and is never logged, returned or part of {@code toString}.
 */
@Component
final class BillingSettings implements CheckoutAvailability {
    private static final Logger log = LoggerFactory.getLogger(BillingSettings.class);
    private static final Pattern TERMINAL_KEY = Pattern.compile("[A-Za-z0-9]{1,40}");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    /** Who may start a payment. */
    enum Mode { OFF, TESTERS, ON }

    final Mode mode;
    final Set<UUID> testers;
    /** The https origin (no path) the return and notification URLs are built on; null when not set. */
    final String publicBaseUrl;
    final String bankBaseUrl;
    final String terminalKey;
    private final String password;
    final Duration paymentTtl;
    final Duration refreshInterval;
    final int checkoutPerHour;
    final Duration reconcileInterval;
    final Duration connectTimeout;
    final Duration requestTimeout;
    /** The whole {@code GetState} exchange a notification waits for: the bank waits about 10 s for {@code OK}, so this stays at 5 s or less. */
    final Duration notificationTimeout;

    BillingSettings(@Value("${learning.billing.checkout:OFF}") String checkout,
                    @Value("${learning.billing.testers:}") String testers,
                    @Value("${learning.billing.public-base-url:}") String publicBaseUrl,
                    @Value("${learning.billing.tbank.base-url:https://securepay.tinkoff.ru/v2}") String bankBaseUrl,
                    @Value("${learning.billing.tbank.terminal-key:}") String terminalKey,
                    @Value("${learning.billing.tbank.password-base64:}") String passwordBase64,
                    @Value("${learning.billing.payment-ttl:PT1H}") Duration paymentTtl,
                    @Value("${learning.billing.refresh-interval:PT10S}") Duration refreshInterval,
                    @Value("${learning.billing.checkout-per-hour:10}") int checkoutPerHour,
                    @Value("${learning.billing.reconcile-interval:PT2M}") Duration reconcileInterval,
                    @Value("${learning.billing.connect-timeout:PT5S}") Duration connectTimeout,
                    @Value("${learning.billing.request-timeout:PT15S}") Duration requestTimeout,
                    @Value("${learning.billing.notification-timeout:PT5S}") Duration notificationTimeout) {
        this.mode = mode(checkout);
        this.testers = testers(testers);
        this.bankBaseUrl = bankBaseUrl(bankBaseUrl);
        this.paymentTtl = within(paymentTtl, Duration.ofMinutes(10), Duration.ofDays(1));
        this.refreshInterval = within(refreshInterval, Duration.ofSeconds(1), Duration.ofMinutes(5));
        this.reconcileInterval = within(reconcileInterval, Duration.ofSeconds(1), Duration.ofHours(1));
        this.connectTimeout = within(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(30));
        this.requestTimeout = within(requestTimeout, Duration.ofMillis(100), Duration.ofSeconds(60));
        this.notificationTimeout = within(notificationTimeout, Duration.ofMillis(100), Duration.ofSeconds(5));
        if (checkoutPerHour < 1 || checkoutPerHour > 1_000) throw new IllegalArgumentException("Invalid learning.billing.checkout-per-hour");
        this.checkoutPerHour = checkoutPerHour;
        String origin = publicOrigin(publicBaseUrl);
        String key = terminalKey == null ? "" : terminalKey.strip();
        String decoded = decode(passwordBase64);
        boolean keyUsable = TERMINAL_KEY.matcher(key).matches();
        this.publicBaseUrl = origin;
        this.terminalKey = keyUsable ? key : null;
        this.password = keyUsable ? decoded : null;
        if (!configured()) {
            if (mode != Mode.OFF || !(key.isEmpty() && decoded == null && origin == null)) {
                log.error("billing is not configured: set MNEMA_TBANK_TERMINAL_KEY, MNEMA_TBANK_PASSWORD_BASE64 (base64 of the password) and MNEMA_PUBLIC_BASE_URL (https origin); checkout mode={}", mode);
            } else {
                log.info("billing is off and not configured");
            }
        }
    }

    /** Terminal key, password and public origin are all present and usable. */
    boolean configured() {
        return terminalKey != null && password != null && publicBaseUrl != null;
    }

    /** The terminal password, for signing and verifying only. */
    String password() {
        return password;
    }

    /** Whether {@code owner} is let through the mode: {@code ON}, or {@code TESTERS} and on the list. */
    boolean permits(UUID owner) {
        return mode == Mode.ON || (mode == Mode.TESTERS && testers.contains(owner));
    }

    @Override
    public boolean available(UUID owner) {
        return configured() && permits(owner);
    }

    /**
     * @throws CapabilityUnavailableException {@code capability: billing}, {@code reason: DISABLED} when the mode refuses the owner, else
     *                                          {@code NOT_CONFIGURED} when credentials are missing
     */
    void requireAvailable(UUID owner) {
        if (!permits(owner)) throw unavailable("DISABLED");
        if (!configured()) throw unavailable("NOT_CONFIGURED");
    }

    private static CapabilityUnavailableException unavailable(String reason) {
        return new CapabilityUnavailableException(ProblemExtension.builder().put("capability", "billing").put("reason", reason).build());
    }

    /** Constant-time comparison of a terminal key received from the bank with the configured one. */
    boolean isOwnTerminal(String received) {
        return terminalKey != null && received != null
                && MessageDigest.isEqual(terminalKey.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
    }

    /** A blank value (a variable declared but empty by the deployment) is {@code OFF}: nothing can be bought by accident. */
    private static Mode mode(String value) {
        if (value == null || value.isBlank()) return Mode.OFF;
        try {
            return Mode.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid learning.billing.checkout (OFF, TESTERS or ON)");
        }
    }

    private static Set<UUID> testers(String value) {
        Set<UUID> testers = new HashSet<>();
        for (String part : value.split(",")) {
            if (part.isBlank()) continue;
            try {
                testers.add(UuidPolicy.requireEntityId(UUID.fromString(part.strip()), "tester"));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid learning.billing.testers");
            }
        }
        return Set.copyOf(testers);
    }

    private static Duration within(Duration value, Duration min, Duration max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) throw new IllegalArgumentException("Invalid learning.billing duration");
        return value;
    }

    /** The https origin of a URL, or null for a blank value; http is accepted for a loopback host only (local runs and tests). */
    private static String publicOrigin(String value) {
        if (value == null || value.isBlank()) return null;
        URI uri = uri(value.strip(), "learning.billing.public-base-url");
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) throw new IllegalArgumentException("learning.billing.public-base-url must be an origin without a path");
        return origin(uri, "learning.billing.public-base-url");
    }

    /** The bank API base: an origin plus an optional path, https (http only for a loopback host), no trailing slash. */
    private static String bankBaseUrl(String value) {
        URI uri = uri(value == null ? "" : value.strip(), "learning.billing.tbank.base-url");
        String origin = origin(uri, "learning.billing.tbank.base-url");
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
        return origin + path;
    }

    private static URI uri(String value, String name) {
        try {
            URI uri = new URI(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Invalid " + name);
            }
            return uri;
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }

    private static String origin(URI uri, String name) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean secure = scheme.equals("https");
        if (!secure && !(scheme.equals("http") && LOOPBACK.contains(host))) throw new IllegalArgumentException("Invalid " + name + " (https required)");
        return scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    /** The password text, or null when the value is blank or not base64; the value itself is never reported. */
    private static String decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(value.strip());
            return bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "BillingSettings[mode=" + mode + ", configured=" + configured() + "]";
    }
}
