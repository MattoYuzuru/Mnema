package app.mnema.learning.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code learning.billing.npd.*}: the «Мой налог» (НПД) receipts of paid orders ({@code contracts/billing}, #392). {@code OFF} (the default) enqueues
 * receipts all the same, so no payment is forgotten, but the worker sends nothing and the deadline alarm still fires; {@code ON} sends them. Unlike the bank
 * credentials, {@code ON} without a usable INN and password stops the start with a message that names the settings and never a value: an enabled
 * receipt flow that silently cannot send is a legal risk, not a degraded feature.
 *
 * <p>The password of the taxpayer's personal account is held as text for the login only and is never logged, returned or part of {@code toString}; neither
 * is the INN (it identifies the seller and is not logged either).
 */
@Component
final class NpdSettings {
    private static final Logger log = LoggerFactory.getLogger(NpdSettings.class);
    private static final Pattern INN = Pattern.compile("[0-9]{12}");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    /** Whether the worker sends receipts. */
    enum Mode { OFF, ON }

    final Mode mode;
    private final String inn;
    private final String password;
    /** The API base ({@code https://lknpd.nalog.ru/api/v1}): an origin plus a path, https (http only for a loopback host), no trailing slash. */
    final String baseUrl;
    /** The delay between two worker passes. */
    final Duration interval;
    final Duration connectTimeout;
    /** The whole exchange with «Мой налог», connect and body included. */
    final Duration requestTimeout;

    NpdSettings(@Value("${learning.billing.npd.receipts:OFF}") String receipts,
                @Value("${learning.billing.npd.inn:}") String inn,
                @Value("${learning.billing.npd.password-base64:}") String passwordBase64,
                @Value("${learning.billing.npd.base-url:https://lknpd.nalog.ru/api/v1}") String baseUrl,
                @Value("${learning.billing.npd.interval:PT1M}") Duration interval,
                @Value("${learning.billing.npd.connect-timeout:PT5S}") Duration connectTimeout,
                @Value("${learning.billing.npd.request-timeout:PT20S}") Duration requestTimeout) {
        this.mode = mode(receipts);
        String number = inn == null ? "" : inn.strip();
        if (!number.isEmpty() && !INN.matcher(number).matches()) throw new IllegalArgumentException("Invalid learning.billing.npd.inn (12 digits)");
        String decoded = decode(passwordBase64);
        if (passwordBase64 != null && !passwordBase64.isBlank() && decoded == null) {
            throw new IllegalArgumentException("Invalid learning.billing.npd.password-base64 (base64 of the password)");
        }
        if (mode == Mode.ON && (number.isEmpty() || decoded == null)) {
            throw new IllegalStateException("MNEMA_NPD_RECEIPTS=ON needs MNEMA_NPD_INN (12 digits) and MNEMA_NPD_PASSWORD_BASE64 (base64 of the password)");
        }
        this.inn = number.isEmpty() ? null : number;
        this.password = decoded;
        this.baseUrl = baseUrl(baseUrl);
        this.interval = within(interval, Duration.ofSeconds(1), Duration.ofHours(1));
        this.connectTimeout = within(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(30));
        this.requestTimeout = within(requestTimeout, Duration.ofMillis(100), Duration.ofSeconds(60));
        if (mode == Mode.OFF) {
            log.info("npd receipts are off: paid orders queue a receipt, nothing is sent to Moy Nalog");
        }
    }

    /** The worker sends receipts and the credentials exist. */
    boolean sending() {
        return mode == Mode.ON && inn != null && password != null;
    }

    /** The taxpayer's INN, or null when none is configured; for the login and the receipt link only, never for a log line. */
    String inn() {
        return inn;
    }

    /** The personal-account password, for the login only. */
    String password() {
        return password;
    }

    /**
     * A device id that is the same after every restart: «Мой налог» treats a changing device as a new one and may block it. 21 characters, derived from
     * the INN so that no random value is stored and the INN itself is not exposed.
     */
    String deviceId() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(("mnema-npd:" + inn).getBytes(StandardCharsets.UTF_8));
            return "mnema" + HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Mode mode(String value) {
        if (value == null || value.isBlank()) return Mode.OFF;
        try {
            return Mode.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid learning.billing.npd.receipts (OFF or ON)");
        }
    }

    private static Duration within(Duration value, Duration min, Duration max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) throw new IllegalArgumentException("Invalid learning.billing.npd duration");
        return value;
    }

    private static String baseUrl(String value) {
        try {
            URI uri = new URI(value == null ? "" : value.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Invalid learning.billing.npd.base-url");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !(scheme.equals("http") && LOOPBACK.contains(host))) {
                throw new IllegalArgumentException("Invalid learning.billing.npd.base-url (https required)");
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
            return scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + path;
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Invalid learning.billing.npd.base-url");
        }
    }

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
        return "NpdSettings[mode=" + mode + ", sending=" + sending() + "]";
    }
}
