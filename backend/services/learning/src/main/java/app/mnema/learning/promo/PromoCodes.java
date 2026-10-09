package app.mnema.learning.promo;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Optional;

/**
 * The shape of a promo code. A code is normalized (upper case, without spaces, dashes or underscores) before it is hashed, so
 * {@code "spring-26 plus"} and {@code "SPRING26PLUS"} are the same code. Only {@code HMAC-SHA256(MNEMA_PROMO_HASH_SECRET, normalized code)} is
 * stored, so a database copy alone does not allow an offline guess of a short code.
 */
final class PromoCodes {
    /** No 0/O, 1/I/L: a code read from a screen or a poster cannot be mistyped into another one. The console generates its 12-character codes from it. */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int GENERATED_LENGTH = 12;
    /** A vanity code is at least this long: with a 31 character alphabet a generated code has about 60 bits, eight characters are the floor of a chosen one. */
    static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 24;

    private PromoCodes() { }

    /** @return the normalized code, or empty when the text cannot be a code ({@code [A-Z0-9]}, 8 to 24 characters after normalization) */
    static Optional<String> normalize(String raw) {
        if (raw == null || raw.length() > 64) return Optional.empty();
        StringBuilder normalized = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (Character.isWhitespace(current) || current == '-' || current == '_') continue;
            if (current >= 128) return Optional.empty();
            char upper = Character.toUpperCase(current);
            if (!((upper >= 'A' && upper <= 'Z') || (upper >= '0' && upper <= '9'))) return Optional.empty();
            normalized.append(upper);
        }
        String code = normalized.toString();
        return code.length() < MIN_LENGTH || code.length() > MAX_LENGTH ? Optional.empty() : Optional.of(code);
    }

    /** @return the 32 bytes stored for a code: HMAC-SHA256 of the normalized code under the promo secret */
    static byte[] hash(byte[] secret, String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", failure);
        }
    }

    /** First two and last two characters: enough for an admin to tell codes apart, too little to guess one. */
    static String hint(String normalized) {
        return normalized.substring(0, 2) + "…" + normalized.substring(normalized.length() - 2);
    }

    /** The form the admin reads once and hands out: a generated code in three groups of four. */
    static String display(String normalized) {
        return normalized.length() == GENERATED_LENGTH ? normalized.substring(0, 4) + "-" + normalized.substring(4, 8) + "-" + normalized.substring(8) : normalized;
    }
}
