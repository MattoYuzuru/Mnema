package app.mnema.learning.catalog.content;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.IDN;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Baseline editable capabilities only; later renderers must explicitly register their schemas. */
final class NativeNodeSchema {

    private static final Map<String, Set<String>> ATTRIBUTES = Map.ofEntries(
            Map.entry("doc", Set.of()),
            Map.entry("paragraph", Set.of("lang", "dir")),
            Map.entry("heading", Set.of("level", "lang", "dir")),
            Map.entry("blockquote", Set.of("lang", "dir")),
            Map.entry("bullet_list", Set.of()),
            Map.entry("ordered_list", Set.of("order")),
            Map.entry("list_item", Set.of()),
            Map.entry("divider", Set.of()),
            Map.entry("text", Set.of("text", "marks")),
            Map.entry("ruby", Set.of("base", "reading")),
            Map.entry("link", Set.of("href")));
    private static final Set<String> INLINE = Set.of("text", "ruby", "link");
    private static final Set<String> DIRECTIONS = Set.of("auto", "ltr", "rtl");
    private static final Set<String> MARKS = Set.of("strong", "em", "code");
    private static final Set<String> LANGUAGE_ATTRIBUTES = Set.of("lang", "dir");
    // An explicit shared BCP 47 core profile, not Java/browser locale canonicalization.
    // Preserve spelling; extensions/private use can be added with cross-client fixtures.
    private static final Pattern LANGUAGE = Pattern.compile(
            "[a-z]{2,3}(?:-[a-z]{4})?(?:-(?:[a-z]{2}|[0-9]{3}))?"
                    + "(?:-(?:[a-z0-9]{5,8}|[0-9][a-z0-9]{3}))*", Pattern.CASE_INSENSITIVE);
    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?",
            Pattern.CASE_INSENSITIVE);

    private NativeNodeSchema() {
    }

    static boolean supports(String type, int version) {
        return version == 1 && ATTRIBUTES.containsKey(type);
    }

    static Slot validate(JsonNode node, Slot parentSlot, boolean insideLink) {
        String type = node.path("type").textValue();
        JsonNode attrs = node.path("attrs");
        if (attrs.properties().stream().anyMatch(property -> !ATTRIBUTES.get(type).contains(property.getKey())
                    && !LANGUAGE_ATTRIBUTES.contains(property.getKey()))
                || ("doc".equals(type) && parentSlot != Slot.ROOT)
                || ("list_item".equals(type) && parentSlot != Slot.LIST_ITEM)
                || (parentSlot == Slot.INLINE && !INLINE.contains(type))
                || (parentSlot == Slot.BLOCK && INLINE.contains(type))
                || (parentSlot == Slot.LIST_ITEM && !"list_item".equals(type))
                || parentSlot == Slot.NONE) {
            throw NativeDocumentReader.invalid();
        }
        languageAndDirection(attrs);
        Slot children = switch (type) {
            case "doc", "blockquote", "list_item" -> Slot.BLOCK;
            case "paragraph", "heading", "link" -> Slot.INLINE;
            case "bullet_list", "ordered_list" -> Slot.LIST_ITEM;
            default -> Slot.NONE;
        };
        if ((children == Slot.NONE && !node.path("content").isEmpty())
                || ((children == Slot.BLOCK || children == Slot.LIST_ITEM || "link".equals(type))
                    && node.path("content").isEmpty())
                || ("list_item".equals(type)
                    && !"paragraph".equals(node.path("content").path(0).path("type").textValue()))) {
            throw NativeDocumentReader.invalid();
        }
        switch (type) {
            case "heading" -> {
                if (!NativeDocumentReader.positiveInt(attrs.path("level")) || attrs.path("level").intValue() > 6) {
                    throw NativeDocumentReader.invalid();
                }
            }
            case "ordered_list" -> {
                if (attrs.has("order") && !NativeDocumentReader.positiveInt(attrs.path("order"))) {
                    throw NativeDocumentReader.invalid();
                }
            }
            case "text" -> {
                requireText(attrs.path("text"));
                if (attrs.has("marks")) {
                    JsonNode marks = attrs.path("marks");
                    var seen = new HashSet<String>();
                    if (!marks.isArray()) {
                        throw NativeDocumentReader.invalid();
                    }
                    for (JsonNode mark : marks) {
                        if (!mark.isTextual() || !MARKS.contains(mark.textValue()) || !seen.add(mark.textValue())) {
                            throw NativeDocumentReader.invalid();
                        }
                    }
                }
            }
            case "ruby" -> {
                requireText(attrs.path("base"));
                requireText(attrs.path("reading"));
            }
            case "link" -> {
                if (insideLink || !safeHttps(attrs.path("href"))) {
                    throw NativeDocumentReader.invalid();
                }
            }
            default -> { }
        }
        return children;
    }

    private static void requireText(JsonNode node) {
        if (!node.isTextual() || node.textValue().isEmpty()) {
            throw NativeDocumentReader.invalid();
        }
    }

    private static void languageAndDirection(JsonNode attrs) {
        if (attrs.has("dir") && (!attrs.path("dir").isTextual() || !DIRECTIONS.contains(attrs.path("dir").textValue()))) {
            throw NativeDocumentReader.invalid();
        }
        if (attrs.has("lang")) {
            JsonNode lang = attrs.path("lang");
            if (!lang.isTextual() || lang.textValue().length() > 64 || !LANGUAGE.matcher(lang.textValue()).matches()) {
                throw NativeDocumentReader.invalid();
            }
            var variants = new HashSet<String>();
            for (String subtag : lang.textValue().split("-")) {
                boolean variant = subtag.length() >= 5 || (subtag.length() == 4 && Character.isDigit(subtag.charAt(0)));
                if (variant && !variants.add(subtag.toLowerCase(Locale.ROOT))) {
                    throw NativeDocumentReader.invalid();
                }
            }
        }
    }

    private static boolean safeHttps(JsonNode value) {
        if (!value.isTextual() || value.textValue().length() > 2048
                || value.textValue().chars().anyMatch(character -> character <= 32 || character >= 127)) {
            return false;
        }
        try {
            URI uri = new URI(value.textValue()).parseServerAuthority();
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && safeHost(uri.getHost()) && !uri.getRawAuthority().endsWith(":")
                    && uri.getRawUserInfo() == null && uri.getPort() != 0 && uri.getPort() <= 65_535;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static boolean safeHost(String host) {
        if (host.startsWith("[")) {
            // URI validates IPv6 syntax. Scoped IPv6 identifiers are not browser URLs.
            return !host.contains("%");
        }
        if (host.length() > 253) {
            return false;
        }
        String[] labels = host.split("\\.", -1);
        String last = labels[labels.length - 1];
        if (last.matches("(?i)(?:[0-9]+|0x[0-9a-f]*)")) {
            // WHATWG treats numeric-ending hosts as IPv4, including octal/hex/short
            // spellings. Only canonical dotted decimal is in the shared native profile.
            if (labels.length != 4) {
                return false;
            }
            for (String label : labels) {
                if (!label.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(label) > 255) {
                    return false;
                }
            }
            return true;
        }
        for (String label : labels) {
            if (!DNS_LABEL.matcher(label).matches()) {
                return false;
            }
            if (label.toLowerCase(Locale.ROOT).startsWith("xn--")) {
                String unicode = IDN.toUnicode(label, IDN.USE_STD3_ASCII_RULES);
                try {
                    if (unicode.equalsIgnoreCase(label)
                            || !IDN.toASCII(unicode, IDN.USE_STD3_ASCII_RULES).equalsIgnoreCase(label)) {
                        return false;
                    }
                } catch (IllegalArgumentException exception) {
                    return false;
                }
            }
        }
        return true;
    }

    enum Slot { ROOT, BLOCK, INLINE, LIST_ITEM, NONE, OPAQUE }
}
