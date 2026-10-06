package app.mnema.learning.ai.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Text measures of the golden eval: size, and the longest run of tokens two texts share (the copy check). */
public final class GoldenText {
    private GoldenText() { }

    /** Japanese and Chinese have no spaces between words: size and copy runs are counted in characters. */
    public static boolean characterScript(String language) { return language.equals("ja") || language.equals("zh"); }

    /**
     * Lower-cased tokens: runs of letters and digits, and in {@code characters} mode every Han, Hiragana or Katakana character on its own.
     * Markup characters and punctuation separate tokens and are dropped.
     */
    public static List<String> tokens(String text, boolean characters) {
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (characters && cjk(codePoint)) {
                flush(word, tokens);
                tokens.add(new String(Character.toChars(codePoint)));
            } else if (Character.isLetterOrDigit(codePoint)) {
                word.appendCodePoint(codePoint);
            } else {
                flush(word, tokens);
            }
        }
        flush(word, tokens);
        return tokens;
    }

    public static int units(String text, boolean characters) { return tokens(text, characters).size(); }

    /** The length of the longest run of consecutive tokens that occurs in both lists. */
    public static int longestCommonRun(List<String> left, List<String> right) {
        int best = 0;
        int[] previous = new int[right.size() + 1];
        for (String token : left) {
            int[] current = new int[right.size() + 1];
            for (int column = 1; column <= right.size(); column++) {
                if (token.equals(right.get(column - 1))) {
                    current[column] = previous[column - 1] + 1;
                    best = Math.max(best, current[column]);
                }
            }
            previous = current;
        }
        return best;
    }

    private static void flush(StringBuilder word, List<String> tokens) {
        if (word.isEmpty()) return;
        tokens.add(word.toString().toLowerCase(Locale.ROOT));
        word.setLength(0);
    }

    private static boolean cjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA;
    }
}
