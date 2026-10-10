package app.mnema.learning.library;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/** The public code of a deck: 10 characters of the Bitcoin base58 alphabet (no {@code 0 O I l}) from a CSPRNG, about 58 bits. */
final class PublicCodes {
    static final int LENGTH = 10;
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final Pattern SHAPE = Pattern.compile("[1-9A-HJ-NP-Za-km-z]{10}");

    private PublicCodes() { }

    static String next(SecureRandom random) {
        char[] code = new char[LENGTH];
        for (int index = 0; index < LENGTH; index++) code[index] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        return new String(code);
    }

    /** Whether the text can be a code at all; anything else is answered like an unknown code without a lookup. */
    static boolean valid(String value) {
        return value != null && SHAPE.matcher(value).matches();
    }
}
