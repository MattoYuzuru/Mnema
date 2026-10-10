package app.mnema.learning.promo;

import java.security.SecureRandom;

/** Stands in for the browser's generator: the server accepts only a code the administrator supplies. */
final class PromoTestCodes {
    private static final SecureRandom RANDOM = new SecureRandom();

    private PromoTestCodes() { }

    static String generate() {
        StringBuilder code = new StringBuilder(PromoCodes.GENERATED_LENGTH);
        for (int index = 0; index < PromoCodes.GENERATED_LENGTH; index++) code.append(PromoCodes.ALPHABET.charAt(RANDOM.nextInt(PromoCodes.ALPHABET.length())));
        return code.toString();
    }
}
