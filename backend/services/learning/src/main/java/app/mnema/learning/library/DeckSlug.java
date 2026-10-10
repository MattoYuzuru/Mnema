package app.mnema.learning.library;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * The computed slug of a public deck URL ({@code /d/{code}/{slug}}, architecture section 9): the title transliterated to ASCII (Russian and Ukrainian
 * Cyrillic by the ICAO-like table below, other Latin scripts by stripping marks), lower case, every run of other characters one hyphen, at most 80
 * characters, no leading or trailing hyphen. It is never stored: a title change moves it and the old URL redirects to the canonical one. A title
 * with nothing to transliterate gives {@value #FALLBACK}.
 */
public final class DeckSlug {
    public static final int MAX_LENGTH = 80;
    static final String FALLBACK = "deck";
    private static final Map<Character, String> CYRILLIC = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "g"), Map.entry('д', "d"),
            Map.entry('е', "e"), Map.entry('ё', "e"), Map.entry('ж', "zh"), Map.entry('з', "z"), Map.entry('и', "i"),
            Map.entry('й', "y"), Map.entry('к', "k"), Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"),
            Map.entry('о', "o"), Map.entry('п', "p"), Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"),
            Map.entry('у', "u"), Map.entry('ф', "f"), Map.entry('х', "kh"), Map.entry('ц', "ts"), Map.entry('ч', "ch"),
            Map.entry('ш', "sh"), Map.entry('щ', "shch"), Map.entry('ъ', ""), Map.entry('ы', "y"), Map.entry('ь', ""),
            Map.entry('э', "e"), Map.entry('ю', "yu"), Map.entry('я', "ya"),
            Map.entry('і', "i"), Map.entry('ї', "yi"), Map.entry('є', "ye"), Map.entry('ґ', "g"),
            Map.entry('ß', "ss"), Map.entry('æ', "ae"), Map.entry('œ', "oe"), Map.entry('ø', "o"), Map.entry('ł', "l"),
            Map.entry('đ', "d"), Map.entry('ð', "d"), Map.entry('þ', "th"));

    private DeckSlug() { }

    public static String of(String title) {
        String lower = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < lower.length(); index++) {
            char value = lower.charAt(index);
            String mapped = CYRILLIC.get(value);
            if (mapped != null) {
                out.append(mapped);
            } else if (value < 128) {
                out.append(value);
            } else {
                // Latin letters with marks: decompose and keep the base letter; anything else becomes a separator.
                for (char part : Normalizer.normalize(String.valueOf(value), Normalizer.Form.NFD).toCharArray()) {
                    if (part < 128) out.append(part);
                    else if (Character.getType(part) != Character.NON_SPACING_MARK) out.append(' ');
                }
            }
        }
        StringBuilder slug = new StringBuilder();
        boolean separator = false;
        for (int index = 0; index < out.length(); index++) {
            char value = out.charAt(index);
            if ((value >= 'a' && value <= 'z') || (value >= '0' && value <= '9')) {
                if (separator && slug.length() > 0) slug.append('-');
                separator = false;
                slug.append(value);
            } else {
                separator = true;
            }
        }
        if (slug.length() > MAX_LENGTH) slug.setLength(MAX_LENGTH);
        while (slug.length() > 0 && slug.charAt(slug.length() - 1) == '-') slug.setLength(slug.length() - 1);
        return slug.isEmpty() ? FALLBACK : slug.toString();
    }
}
