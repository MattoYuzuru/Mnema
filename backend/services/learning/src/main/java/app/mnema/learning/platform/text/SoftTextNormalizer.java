package app.mnema.learning.platform.text;

import java.text.Normalizer;

/** Canonical soft matching shared by answer validation and attempt evaluation. */
public final class SoftTextNormalizer {
    private SoftTextNormalizer() { }

    public static String normalize(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        StringBuilder result = new StringBuilder(decomposed.length());
        for (int index = 0; index < decomposed.length();) {
            int point = decomposed.codePointAt(index);
            index += Character.charCount(point);
            int category = Character.getType(point);
            if (category == Character.NON_SPACING_MARK || category == Character.COMBINING_SPACING_MARK
                    || category == Character.ENCLOSING_MARK) continue;
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)
                    || category == Character.DASH_PUNCTUATION || point == 0x2212) continue;
            if (category == Character.CONNECTOR_PUNCTUATION || category == Character.START_PUNCTUATION
                    || category == Character.END_PUNCTUATION || category == Character.OTHER_PUNCTUATION
                    || category == Character.INITIAL_QUOTE_PUNCTUATION
                    || category == Character.FINAL_QUOTE_PUNCTUATION) continue;
            result.appendCodePoint(point);
        }
        return result.toString();
    }
}
