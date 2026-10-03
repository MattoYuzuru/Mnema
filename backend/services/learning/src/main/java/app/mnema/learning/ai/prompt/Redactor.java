package app.mnema.learning.ai.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mandatory redaction of personal-data patterns in all user text before it enters a prompt: e-mail addresses, payment
 * card numbers (13-19 digits passing the Luhn check, also glued to other numbers) and telephone numbers (international {@code +} numbers, numbers
 * with an area code in parentheses, 11-digit Russian/NANP numbers starting with 7 or 8, and the common grouped forms).
 * Digit groups right after a URL path or query character ({@code / = _ # ? & %}) are not telephone numbers: an address keeps its digits.
 * It is a deliberately narrow safety net that avoids mangling ordinary numbers, dates and decimals; the preflight
 * warning that lets the user exclude other personal fragments is a separate, user-facing step.
 */
public final class Redactor {
    /** Largest text accepted per value (UTF-16 units, 64 KiB of text): bounds the cost of redaction and rendering. */
    public static final int MAX_CHARS = 64 * 1024;
    // Every quantifier is bounded, so a hostile value without an '@' costs at most 64 steps per position (linear overall).
    private static final Pattern EMAIL = Pattern.compile(
            "[\\p{L}\\p{N}._%+-]{1,64}@[\\p{L}\\p{N}-]{1,63}(?:\\.[\\p{L}\\p{N}-]{1,63}){0,8}\\.\\p{L}{2,24}");
    /** Usual card lengths, the common 16 first so that a card glued to another number is found as a 16-digit window. */
    private static final int[] CARD_LENGTHS = {16, 15, 14, 13, 19, 18, 17};
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\p{L}\\p{N}/=_#?&%])(?:\\+\\d[\\d\\s().-]{8,16}\\d"
                    + "|(?:[78]|\\+7)?[\\s-]?\\(\\d{3,4}\\)[\\s-]?\\d{2,3}[\\s-]?\\d{2}[\\s-]?\\d{2}"
                    + "|[78]\\d{10}"
                    + "|\\d{3}[ -]\\d{3}[ -]\\d{2}[ -]\\d{2}"
                    + "|\\d{3}[ -]\\d{3}[ -]\\d{4})(?![\\p{L}\\p{N}])");

    private Redactor() { }

    /** @throws PromptException when {@code text} exceeds {@link #MAX_CHARS}; the message names {@code what}, never the text */
    public static String requireWithin(String text, String what) {
        if (text != null && text.length() > MAX_CHARS) throw new PromptException("Value of " + what + " exceeds 64 KiB");
        return text;
    }

    /** Redacts without a size check; callers on the prompt path use {@link #requireWithin} first. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = EMAIL.matcher(text).replaceAll("[email]");
        out = replaceCards(out);
        return PHONE.matcher(out).replaceAll("[phone]");
    }

    /**
     * Finds card numbers inside runs of digit groups ("4111 1111 1111 1111"), also when glued to other numbers: windows of
     * whole groups of the usual lengths (16 first) are tried, so a card next to a phone number is still found.
     */
    private static String replaceCards(String text) {
        StringBuilder out = new StringBuilder();
        int copied = 0;
        int length = text.length();
        for (int position = 0; position < length; ) {
            if (!isDigit(text.charAt(position))) {
                position++;
                continue;
            }
            // A run is digit groups separated by one space or hyphen. Scanned by hand: a regex group repeated per digit group
            // recurses once per group and overflows the stack on a hostile megabyte of "1 1 1 1 ...".
            List<int[]> groups = new ArrayList<>();
            int groupStart = position;
            while (true) {
                int groupEnd = groupStart;
                while (groupEnd < length && isDigit(text.charAt(groupEnd))) groupEnd++;
                groups.add(new int[] {groupStart, groupEnd});
                position = groupEnd;
                boolean separated = groupEnd + 1 < length && (text.charAt(groupEnd) == ' ' || text.charAt(groupEnd) == '-')
                        && isDigit(text.charAt(groupEnd + 1));
                if (!separated) break;
                groupStart = groupEnd + 1;
            }
            boolean[] used = new boolean[groups.size()];
            List<int[]> spans = new ArrayList<>();
            for (int cardLength : CARD_LENGTHS) {
                for (int first = 0; first < groups.size(); first++) {
                    if (used[first]) continue;
                    int digits = 0;
                    for (int last = first; last < groups.size() && !used[last] && digits < cardLength; last++) {
                        digits += groups.get(last)[1] - groups.get(last)[0];
                        if (digits == cardLength && luhn(digitsOf(text, groups, first, last))) {
                            for (int mark = first; mark <= last; mark++) used[mark] = true;
                            spans.add(new int[] {groups.get(first)[0], groups.get(last)[1]});
                        }
                    }
                }
            }
            spans.sort((left, right) -> Integer.compare(left[0], right[0]));
            for (int[] span : spans) {
                out.append(text, copied, span[0]).append("[card]");
                copied = span[1];
            }
        }
        return out.append(text, copied, length).toString();
    }

    private static boolean isDigit(char character) { return character >= '0' && character <= '9'; }

    private static String digitsOf(String text, List<int[]> groups, int first, int last) {
        var digits = new StringBuilder();
        for (int index = first; index <= last; index++) digits.append(text, groups.get(index)[0], groups.get(index)[1]);
        return digits.toString();
    }

    private static boolean luhn(String digits) {
        if (digits.length() < 13 || digits.length() > 19) return false;
        int sum = 0;
        boolean doubled = false;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = digits.charAt(index) - '0';
            if (doubled) {
                digit *= 2;
                if (digit > 9) digit -= 9;
            }
            sum += digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }
}
