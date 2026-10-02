package app.mnema.learning.ai;

/**
 * Approximate token counter for budgets and for estimates when a provider reports no usage. It deliberately avoids a
 * tokenizer dependency and errs on the high side: Latin text and punctuation count 1 token per 3.5 characters,
 * Cyrillic and other alphabetic scripts 1 per 2.2 (the prompt-library sizes in the research put Russian at about two
 * characters per token), and every CJK ideograph, kana and hangul character is one token. The result is an estimate for
 * budgets and capacity planning, never for billing: billing uses the provider's reported usage.
 */
public final class TokenCounter {
    private static final double LATIN_CHARACTERS_PER_TOKEN = 3.5;
    private static final double ALPHABETIC_CHARACTERS_PER_TOKEN = 2.2;

    private TokenCounter() { }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        double tokens = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            tokens += weight(codePoint);
        }
        return (int) Math.ceil(tokens - 1e-9);
    }

    private static double weight(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) {
            return 1.0;
        }
        if (script != Character.UnicodeScript.LATIN && script != Character.UnicodeScript.COMMON
                && script != Character.UnicodeScript.INHERITED && Character.isLetter(codePoint)) {
            return 1.0 / ALPHABETIC_CHARACTERS_PER_TOKEN;
        }
        return 1.0 / LATIN_CHARACTERS_PER_TOKEN;
    }
}
