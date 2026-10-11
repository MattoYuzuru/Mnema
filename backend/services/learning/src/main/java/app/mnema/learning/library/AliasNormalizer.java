package app.mnema.learning.library;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The one normalization of the topic directory (architecture section 10, {@code topic_alias.alias_norm}): Unicode NFKC, lower case, {@code ё} to {@code е}, marks
 * stripped from Latin, Greek and Arabic letters (an unaccent-like step that needs no database extension), runs of white space collapsed to one space,
 * ends trimmed. Marks of other scripts are kept on purpose: they are part of the letter ({@code й} is not {@code и}, the voicing marks of kana and the jamo of
 * Hangul decide the word). The same function normalizes the seed aliases, the text that is searched for them and the tags, so a lookup is an equality.
 */
public final class AliasNormalizer {
    private AliasNormalizer() { }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return "";
        String lower = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('ё', 'е');
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        boolean stripMarks = false;
        for (int index = 0; index < decomposed.length(); ) {
            int point = decomposed.codePointAt(index);
            index += Character.charCount(point);
            if (Character.getType(point) == Character.NON_SPACING_MARK) {
                if (!stripMarks) out.appendCodePoint(point);
                continue;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(point);
            stripMarks = script == Character.UnicodeScript.LATIN || script == Character.UnicodeScript.GREEK || script == Character.UnicodeScript.ARABIC;
            out.appendCodePoint(point);
        }
        String composed = Normalizer.normalize(out, Normalizer.Form.NFC);
        StringBuilder result = new StringBuilder(composed.length());
        boolean space = false;
        for (int index = 0; index < composed.length(); ) {
            int point = composed.codePointAt(index);
            index += Character.charCount(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                space = !result.isEmpty();
            } else {
                if (space) result.append(' ');
                space = false;
                result.appendCodePoint(point);
            }
        }
        return result.toString();
    }
}
