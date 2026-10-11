package app.mnema.learning.library;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The secret of an invitation link: 32 random bytes from a CSPRNG, base64url without padding (43 characters). Only its SHA-256 digest is stored, so a
 * database read cannot reconstruct a link. The token is high-entropy, so a plain digest (no salt, no stretching) is the right tool: there is nothing to brute force.
 */
final class InviteTokens {
    static final int BYTES = 32;
    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private InviteTokens() { }

    static String generate(SecureRandom random) {
        byte[] bytes = new byte[BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Whether the text can be a token at all; a text that is not never reaches the database. */
    static boolean wellFormed(String token) {
        if (token == null || !SHAPE.matcher(token).matches()) return false;
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(token)).equals(token);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", failure);
        }
    }
}
