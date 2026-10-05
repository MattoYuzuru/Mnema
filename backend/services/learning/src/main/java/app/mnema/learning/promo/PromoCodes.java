package app.mnema.learning.promo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Optional;

/**
 * The shape of a promo code. A code is normalized (upper case, without spaces, dashes or underscores) before it is hashed, so
 * {@code "spring-26 plus"} and {@code "SPRING26PLUS"} are the same code. Only the SHA-256 of the normalized code is stored.
 */
final class PromoCodes {
    /** No 0/O, 1/I/L: a code read from a screen or a poster cannot be mistyped into another one. */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int GENERATED_LENGTH = 10;
    private static final int MIN_LENGTH = 4;
    private static final int MAX_LENGTH = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PromoCodes() { }

    /** @return the normalized code, or empty when the text cannot be a code ({@code [A-Z0-9]}, 4 to 24 characters after normalization) */
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

    static byte[] hash(String normalized) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    /** First two and last two characters: enough for an admin to tell codes apart, too little to guess one. */
    static String hint(String normalized) {
        return normalized.substring(0, 2) + "…" + normalized.substring(normalized.length() - 2);
    }

    /** A random normalized code of {@value #GENERATED_LENGTH} characters of the unambiguous alphabet (about 49 bits). */
    static String generate() {
        StringBuilder code = new StringBuilder(GENERATED_LENGTH);
        for (int index = 0; index < GENERATED_LENGTH; index++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return code.toString();
    }

    /** The form the admin reads once and hands out: a generated code in two groups of five. */
    static String display(String normalized) {
        return normalized.length() == GENERATED_LENGTH ? normalized.substring(0, 5) + "-" + normalized.substring(5) : normalized;
    }
}
