package app.mnema.learning.ai;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain-text and URL hygiene for what an image source sends: HTML is stripped, text is bounded, only https URLs survive. */
final class ImageText {
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});");
    private static final Pattern SPACE = Pattern.compile("[\\s\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}\\p{Cs}\\p{Co}]+");

    private ImageText() { }

    /** HTML to plain text, whitespace collapsed, at most {@code maxCodePoints} code points; never null. */
    static String plain(String html, int maxCodePoints) {
        if (html == null) return "";
        String text = TAGS.matcher(html).replaceAll(" ");
        Matcher entity = ENTITY.matcher(text);
        StringBuilder decoded = new StringBuilder();
        while (entity.find()) entity.appendReplacement(decoded, Matcher.quoteReplacement(decode(entity.group(1))));
        entity.appendTail(decoded);
        // after the entities: a decoded "<" is text, not markup, and stays
        String collapsed = SPACE.matcher(decoded).replaceAll(" ").strip();
        return bound(collapsed, maxCodePoints);
    }

    static String bound(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) return text;
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints)).strip();
    }

    private static String decode(String name) {
        try {
            if (name.startsWith("#x")) return new String(Character.toChars(Integer.parseInt(name.substring(2), 16)));
            if (name.startsWith("#")) return new String(Character.toChars(Integer.parseInt(name.substring(1))));
        } catch (IllegalArgumentException invalid) {
            return " ";
        }
        return switch (name) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "nbsp" -> " ";
            default -> " ";
        };
    }

    /** The URL when it is an absolute https URL without user info (at most 2000 characters), else null. */
    static String https(String value) {
        if (value == null || value.length() > 2_000) return null;
        try {
            URI uri = URI.create(value.strip());
            if (!"https".equals(uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT)) || uri.getHost() == null
                    || uri.getRawUserInfo() != null) {
                return null;
            }
            return uri.toString();
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
