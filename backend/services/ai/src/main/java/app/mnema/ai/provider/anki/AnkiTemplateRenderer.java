package app.mnema.ai.provider.anki;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AnkiTemplateRenderer {

    private static final Pattern CLOZE_PATTERN = Pattern.compile("\\{\\{c(\\d+)::(.*?)(?:::(.*?))?}}",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TEMPLATE_FIELD_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");
    private static final Pattern MIGAKU_FURIGANA_PATTERN = Pattern.compile("([\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]+)\\[([^\\];]+);[^\\]]*]");
    private static final Pattern MIGAKU_MARKER_PATTERN = Pattern.compile("\\[;[^\\]]*]");
    private static final Set<String> BUILTIN_FIELDS = Set.of(
            "frontside",
            "tags",
            "deck",
            "subdeck",
            "card",
            "cardid",
            "note",
            "noteid",
            "notetype",
            "type"
    );

    public AnkiRendered render(AnkiTemplate template, Map<String, String> fields) {
        if (template == null || template.frontTemplate() == null || template.frontTemplate().isBlank()) {
            return null;
        }
        String front = renderTemplate(template.frontTemplate(), fields, RenderSide.FRONT, null);
        String backTemplate = template.backTemplate() == null ? "" : template.backTemplate();
        String back = renderTemplate(backTemplate, fields, RenderSide.BACK, front);
        return new AnkiRendered(front, back, template.css(), template.modelId(), template.modelName(), template.templateName());
    }

    private String renderTemplate(String template,
                                  Map<String, String> fields,
                                  RenderSide side,
                                  String frontHtml) {
        if (template == null || template.isBlank()) {
            return "";
        }
        String resolved = applyConditionals(template, fields);
        resolved = replaceTemplateTokens(resolved, fields, side, frontHtml);
        return resolved;
    }

    private String applyConditionals(String template, Map<String, String> fields) {
        Pattern conditional = Pattern.compile("\\{\\{([#^])\\s*([^}]+)}}(.*?)\\{\\{/\\s*\\2\\s*}}", Pattern.DOTALL);
        String current = template;
        boolean changed;
        do {
            Matcher matcher = conditional.matcher(current);
            StringBuffer buffer = new StringBuffer();
            changed = false;
            while (matcher.find()) {
                changed = true;
                String token = cleanTemplateToken(matcher.group(2));
                boolean hasValue = hasFieldValue(fields, token);
                boolean include = matcher.group(1).equals("#") ? hasValue : !hasValue;
                String replacement = include ? matcher.group(3) : "";
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(buffer);
            current = buffer.toString();
        } while (changed);
        return current;
    }

    private String replaceTemplateTokens(String template,
                                         Map<String, String> fields,
                                         RenderSide side,
                                         String frontHtml) {
        Matcher matcher = TEMPLATE_FIELD_PATTERN.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement = renderTokenValue(token, fields, side, frontHtml);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String renderTokenValue(String token,
                                    Map<String, String> fields,
                                    RenderSide side,
                                    String frontHtml) {
        if (token == null) {
            return "";
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        char first = trimmed.charAt(0);
        if (first == '#' || first == '^' || first == '/') {
            return "";
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("frontside")) {
            return frontHtml == null ? "" : frontHtml;
        }
        if (BUILTIN_FIELDS.contains(lower)) {
            return "";
        }

        String filter = null;
        String fieldName = trimmed;
        int idx = trimmed.indexOf(':');
        if (idx > 0 && idx < trimmed.length() - 1) {
            filter = trimmed.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            fieldName = trimmed.substring(idx + 1).trim();
        }

        String raw = resolveFieldValue(fields, fieldName);
        if (raw == null) {
            return "";
        }

        String value = normalizeMigakuText(raw);
        if (filter != null) {
            value = switch (filter) {
                case "furigana" -> renderFurigana(value);
                case "text" -> stripHtml(value);
                case "cloze" -> renderCloze(value, side);
                case "type" -> stripHtml(value);
                default -> value;
            };
        }

        return value.replace("\n", "<br>");
    }

    private boolean hasFieldValue(Map<String, String> fields, String fieldName) {
        String raw = resolveFieldValue(fields, fieldName);
        return raw != null && !raw.isBlank();
    }

    private String resolveFieldValue(Map<String, String> fields, String fieldName) {
        if (fields == null || fieldName == null) {
            return null;
        }
        String direct = fields.get(fieldName);
        if (direct != null) {
            return direct;
        }
        String normalized = normalize(fieldName);
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (normalize(entry.getKey()).equals(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String cleanTemplateToken(String token) {
        if (token == null) {
            return "";
        }
        String cleaned = token.trim();
        while (!cleaned.isEmpty()) {
            char c = cleaned.charAt(0);
            if (c == '#' || c == '^' || c == '/') {
                cleaned = cleaned.substring(1).trim();
            } else {
                break;
            }
        }
        int idx = cleaned.lastIndexOf(':');
        if (idx >= 0 && idx < cleaned.length() - 1) {
            cleaned = cleaned.substring(idx + 1).trim();
        }
        return cleaned;
    }

    private String renderFurigana(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Pattern pattern = Pattern.compile("([\\p{IsHan}]+)\\[([^\\];]+)(?:;[^\\]]*)?]");
        Matcher matcher = pattern.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String kanji = matcher.group(1);
            String reading = matcher.group(2);
            String replacement = "<ruby>" + kanji + "<rt>" + reading + "</rt></ruby>";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String normalizeMigakuText(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        String normalized = value;
        Matcher matcher = MIGAKU_FURIGANA_PATTERN.matcher(normalized);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String base = matcher.group(1);
            String reading = matcher.group(2);
            String replacement = "<ruby>" + base + "<rt>" + reading + "</rt></ruby>";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        normalized = buffer.toString();
        normalized = MIGAKU_MARKER_PATTERN.matcher(normalized).replaceAll("");
        return normalized;
    }

    private String renderCloze(String value, RenderSide side) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Matcher matcher = CLOZE_PATTERN.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String text = matcher.group(2);
            String replacement = side == RenderSide.FRONT
                    ? "<span class=\"cloze\">[...]</span>"
                    : "<span class=\"cloze\">" + text + "</span>";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String stripHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("<[^>]+>", "");
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private enum RenderSide {
        FRONT,
        BACK
    }
}
