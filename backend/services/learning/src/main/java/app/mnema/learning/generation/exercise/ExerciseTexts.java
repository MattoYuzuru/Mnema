package app.mnema.learning.generation.exercise;

import app.mnema.learning.platform.text.TitleNormalizer;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Text comparison of the exercise lint: the one normalization of {@code lint.json} (NFC, trimmed, case folded, whitespace
 * collapsed) and the whole-word search. Scripts written without spaces (Han, kana, Thai, Lao, Khmer, Myanmar) have no word
 * boundaries, so for them a substring is the only meaningful match.
 */
final class ExerciseTexts {
    private static final Pattern SPACES = Pattern.compile("[\\p{Z}\\s]+");
    private static final Pattern UNSPACED = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsThai}\\p{IsLao}\\p{IsKhmer}\\p{IsMyanmar}]");

    private ExerciseTexts() { }

    /** NFC, trimmed, lower-cased and with every run of whitespace collapsed to one space. */
    static String normalize(String text) {
        return TitleNormalizer.normalize(text);
    }

    /** Whitespace collapsed and trimmed, case and form kept: a fragment of a pinned block is compared verbatim. */
    static String collapse(String text) {
        return SPACES.matcher(Normalizer.normalize(text, Normalizer.Form.NFC).strip()).replaceAll(" ");
    }

    /**
     * Whether {@code needle} occurs in {@code haystack} as whole words (both normalized by the caller); a needle or text in a
     * script without spaces matches as a substring.
     */
    static boolean containsWord(String haystack, String needle) {
        if (needle.isEmpty()) return false;
        if (UNSPACED.matcher(needle).find() || UNSPACED.matcher(haystack).find()) return haystack.contains(needle);
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(needle) + "(?![\\p{L}\\p{N}])").matcher(haystack).find();
    }
}
