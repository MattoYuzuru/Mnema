package app.mnema.learning.promo;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.UUID;

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
    /** Domain separation of the derived personal codes; a normalized code never contains {@code -} or {@code :}, so no hashed code can equal this message. */
    private static final String PERSONAL_PREFIX = "path-reward-code:";
    /** The largest multiple of the alphabet size within a byte (31 * 8): a byte at or above it is skipped, so every letter is exactly equally likely. */
    private static final int UNBIASED_LIMIT = 256 - 256 % ALPHABET.length();

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

    /**
     * The 12-character code of a personal code row, derived from its id so that the plain code is never stored: the bytes of HMAC-SHA256(promo secret,
     * {@code "path-reward-code:" + codeId}) choose the letters of {@link #ALPHABET} by rejection sampling (bytes of 248 and above are skipped, so there is no modulo bias; a
     * 32-byte block yields about 31 letters, and in the practically impossible case that it yields fewer than twelve the next block is the HMAC of the same message with
     * {@code ":1"}, {@code ":2"}... appended). The code is as strong as the id and the secret: about 59 bits, and only a holder of the secret can compute it.
     *
     * @return the normalized code (upper case, no dashes); {@link #hash} and {@link #hint} of it are what the row stores
     */
    static String derive(byte[] secret, UUID codeId) {
        StringBuilder code = new StringBuilder(GENERATED_LENGTH);
        for (int block = 0; code.length() < GENERATED_LENGTH; block++) {
            byte[] bytes = hash(secret, PERSONAL_PREFIX + codeId + (block == 0 ? "" : ":" + block));
            for (int index = 0; index < bytes.length && code.length() < GENERATED_LENGTH; index++) {
                int value = bytes[index] & 0xFF;
                if (value < UNBIASED_LIMIT) code.append(ALPHABET.charAt(value % ALPHABET.length()));
            }
        }
        return code.toString();
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
