package app.mnema.learning.library;

/**
 * Plain text for a link preview. {@link #plain} makes one line of any input (every run of whitespace and control characters is one space; the bidirectional
 * formatting characters that can reorder the text around them in a messenger card are dropped) and
 * {@link #limit} shortens it on a word boundary. Nothing here knows HTML: escaping belongs to the one place that writes markup ({@link PreviewHead}).
 */
final class PreviewText {
    private PreviewText() { }

    /** One line: runs of whitespace and control characters become a single space, the ends are stripped. {@code null} is the empty text. */
    static String plain(String value) {
        if (value == null) return "";
        StringBuilder line = new StringBuilder(value.length());
        boolean space = false;
        for (int index = 0; index < value.length(); ) {
            int point = value.codePointAt(index);
            index += Character.charCount(point);
            if (isBidiFormat(point)) {
                continue;
            } else if (Character.isISOControl(point) || Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                space = true;
            } else {
                if (space && !line.isEmpty()) line.append(' ');
                space = false;
                line.appendCodePoint(point);
            }
        }
        return line.toString();
    }

    /** Directional marks, embeddings, overrides and isolates (U+200E, U+200F, U+202A to U+202E, U+2066 to U+2069) and the Arabic letter mark (U+061C). */
    static boolean isBidiFormat(int point) {
        return point == 0x200E || point == 0x200F || point == 0x061C || (point >= 0x202A && point <= 0x202E) || (point >= 0x2066 && point <= 0x2069);
    }

    /**
     * At most {@code max} characters (code points) of {@code text}, cut after a whole word when one ends in the second half of the allowance, with an
     * ellipsis when anything was cut. Text that fits is returned as it is. A cut never splits a surrogate pair.
     */
    static String limit(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) return text;
        int end = text.offsetByCodePoints(0, max);
        String head = text.substring(0, end);
        // The cut fell between words when the next character is a space: keep every word of the head.
        if (text.charAt(end) != ' ') {
            int space = head.lastIndexOf(' ');
            if (space >= head.length() / 2) head = head.substring(0, space);
        }
        int last = head.length();
        while (last > 0 && isTrailing(head.charAt(last - 1))) last--;
        return head.substring(0, last) + "…";
    }

    private static boolean isTrailing(char value) {
        return value == ' ' || value == ',' || value == ';' || value == ':' || value == '-' || value == '–' || value == '—';
    }
}
